package io.floci.az.services.keyvault;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import jakarta.ws.rs.core.Response;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

/** Self-signed certificate lifecycle, with addressable matching keys and secrets. */
final class KeyVaultCertificates {
    private static final Logger LOG = Logger.getLogger(KeyVaultCertificates.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final StorageBackend<String, StoredObject> store;

    KeyVaultCertificates(StorageBackend<String, StoredObject> store) {
        this.store = store;
    }

    synchronized Response handle(AzureRequest request) {
        try {
            String[] parts = request.resourcePath().replaceAll("/+$", "").split("/");
            String account = request.accountName();
            String method = request.method();
            boolean deleted = "deletedcertificates".equals(parts[0]);
            if (parts.length == 1) {
                return "GET".equals(method) ? list(account, deleted) : error(405, "MethodNotAllowed", "Method not allowed");
            }
            String name = parts[1];
            if (!name.matches("[a-zA-Z0-9-]{1,127}")) {
                return error(400, "BadParameter", "Invalid certificate name");
            }
            if (deleted) {
                return deleted(account, name, parts, method);
            }
            if (parts.length == 3 && "create".equals(parts[2]) && "POST".equals(method)) {
                return create(request, account, name);
            }
            if (parts.length > 3) {
                return missing(name);
            }
            StoredObject current = store.get(base(account, "certificates", name)).orElse(null);
            if (current == null) {
                return missing(name);
            }
            if (parts.length == 2 && "DELETE".equals(method)) {
                return delete(account, name, current);
            }
            String operation = parts.length == 3 ? parts[2] : "";
            if ("GET".equals(method)) {
                return switch (operation) {
                    case "" -> ok(read(current));
                    case "versions" -> ok(Map.of("value", versions(account, name).stream()
                            .map(value -> item(read(value))).toList()));
                    case "pending" -> ok(operation(account, name, read(current)));
                    case "policy" -> ok(read(current).get("policy"));
                    default -> store.get(base(account, "certificates", name) + "/versions/" + operation)
                            .map(value -> ok(read(value))).orElseGet(() -> missing(name));
                };
            }
            return error(405, "MethodNotAllowed", "Method not allowed");
        } catch (IllegalArgumentException | KeyVaultCrypto.CryptoException e) {
            return error(400, "BadParameter", e.getMessage());
        } catch (Exception e) {
            LOG.error("Certificate operation failed", e);
            return error(500, "InternalServerError", "Certificate operation failed");
        }
    }

    private Response create(AzureRequest request, String account, String name) throws Exception {
        if (store.get(base(account, "deletedcertificates", name)).isPresent()
                || store.get(base(account, "deletedkeys", name)).isPresent()
                || store.get(base(account, "deletedsecrets", name)).isPresent()) {
            return error(409, "Conflict", "Recover or purge the deleted certificate, key, or secret first");
        }
        Map<String, Object> body;
        try {
            body = JSON.readValue(request.bodyStream(), new TypeReference<>() {});
            if (body == null) {
                throw new IllegalArgumentException("Certificate request must be a JSON object");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Certificate request must be a JSON object", e);
        }
        Map<String, Object> policy = map(body.get("policy"));
        if (policy.isEmpty()) {
            var previous = store.get(base(account, "certificates", name));
            if (previous.isEmpty()) {
                throw new IllegalArgumentException("policy is required for the first certificate version");
            }
            policy = map(read(previous.get()).get("policy"));
        }
        Map<String, Object> issuer = map(policy.get("issuer"));
        if (!"Self".equalsIgnoreCase(String.valueOf(issuer.get("name")))) {
            throw new IllegalArgumentException("Only the Self issuer is supported; external certificate issuance is not emulated");
        }
        Map<String, Object> keyPolicy = map(policy.get("key_props"));
        Map<String, Object> x509 = map(policy.get("x509_props"));
        String subject = String.valueOf(x509.getOrDefault("subject", ""));
        if (subject.isBlank()) {
            throw new IllegalArgumentException("x509_props.subject is required");
        }
        String type = String.valueOf(keyPolicy.getOrDefault("kty", "RSA"));
        if (!Set.of("RSA", "EC").contains(type)) {
            throw new IllegalArgumentException("Certificates support RSA and EC keys");
        }
        int months = integer(x509.getOrDefault("validity_months", 12), "validity_months");
        if (months < 1 || months > 1200) {
            throw new IllegalArgumentException("validity_months must be between 1 and 1200");
        }
        String contentType = String.valueOf(map(policy.get("secret_props"))
                .getOrDefault("contentType", "application/x-pkcs12"));
        if (!Set.of("application/x-pkcs12", "application/x-pem-file").contains(contentType)) {
            throw new IllegalArgumentException("Unsupported certificate secret contentType");
        }
        boolean exportable = bool(keyPolicy, "exportable", true);
        boolean reuse = bool(keyPolicy, "reuse_key", false);
        Map<String, Object> jwk;
        var previousKey = store.get(base(account, "keys", name));
        if (reuse && previousKey.isPresent()) {
            jwk = read(previousKey.get());
            if (!type.equals(jwk.get("kty"))) {
                throw new IllegalArgumentException("Cannot reuse a key of a different type");
            }
        } else {
            jwk = KeyVaultCrypto.generateJwk(type, integer(keyPolicy.getOrDefault("key_size", 2048), "key_size"),
                    String.valueOf(keyPolicy.getOrDefault("crv", "P-256")), List.of("sign", "verify"));
        }
        PrivateKey privateKey = "RSA".equals(type) ? KeyVaultCrypto.reconstructRsaPrivate(jwk) : KeyVaultCrypto.reconstructEcPrivate(jwk);
        PublicKey publicKey = "RSA".equals(type) ? KeyVaultCrypto.reconstructRsaPublic(jwk) : KeyVaultCrypto.reconstructEcPublic(jwk);
        Instant now = Instant.now();
        Instant expires = now.atZone(ZoneOffset.UTC).plusMonths(months).toInstant();
        X500Name distinguishedName = new X500Name(subject);
        var builder = new JcaX509v3CertificateBuilder(distinguishedName,
                new BigInteger(UUID.randomUUID().toString().replace("-", ""), 16),
                Date.from(now.minusSeconds(60)), Date.from(expires), distinguishedName, publicKey);
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        addExtensions(builder, x509);
        var signer = new JcaContentSignerBuilder("RSA".equals(type) ? "SHA256WithRSA" : "SHA256withECDSA").build(privateKey);
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        certificate.verify(publicKey);
        byte[] der = certificate.getEncoded();
        byte[] secretBytes;
        if ("application/x-pkcs12".equals(contentType)) {
            KeyStore pfx = KeyStore.getInstance("PKCS12");
            pfx.load(null, new char[0]);
            if (exportable) {
                pfx.setKeyEntry(name, privateKey, new char[0], new java.security.cert.Certificate[]{certificate});
            } else {
                pfx.setCertificateEntry(name, certificate);
            }
            var output = new ByteArrayOutputStream();
            pfx.store(output, new char[0]);
            secretBytes = output.toByteArray();
        } else {
            String pem = pem("CERTIFICATE", der) + (exportable ? pem("PRIVATE KEY", privateKey.getEncoded()) : "");
            secretBytes = pem.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        String version = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> attributes = new LinkedHashMap<>(Map.of("enabled", bool(map(body.get("attributes")), "enabled", true),
                "created", now.getEpochSecond(), "updated", now.getEpochSecond(), "nbf", certificate.getNotBefore().toInstant().getEpochSecond(),
                "exp", expires.getEpochSecond(), "recoveryLevel", "Purgeable", "recoverableDays", 7));
        Map<String, Object> tags = map(body.get("tags"));
        var bundle = new LinkedHashMap<String, Object>();
        bundle.put("id", id(account, "certificates", name, version));
        bundle.put("kid", id(account, "keys", name, version));
        bundle.put("sid", id(account, "secrets", name, version));
        bundle.put("cer", Base64.getEncoder().encodeToString(der));
        bundle.put("x5t", KeyVaultCrypto.b64Url(MessageDigest.getInstance("SHA-1").digest(der)));
        bundle.put("attributes", attributes);
        bundle.put("tags", tags);
        bundle.put("policy", policy);
        Map<String, String> metadata = new HashMap<>();
        attributes.forEach((key, value) -> metadata.put(key, String.valueOf(value)));
        metadata.put("version", version);
        metadata.put("latestVersion", version);
        jwk.put("tags", tags);
        String secret = "application/x-pkcs12".equals(contentType) ? Base64.getEncoder().encodeToString(secretBytes)
                : new String(secretBytes, java.nio.charset.StandardCharsets.UTF_8);
        persist(account, "keys", name, version, jwk, metadata);
        persist(account, "secrets", name, version, Map.of("value", secret, "contentType", contentType, "tags", tags), metadata);
        persist(account, "certificates", name, version, bundle, metadata);
        return Response.status(202).entity(operation(account, name, bundle)).build();
    }

    private static void addExtensions(JcaX509v3CertificateBuilder builder, Map<String, Object> x509) throws Exception {
        Map<String, Integer> usages = Map.of("digitalSignature", KeyUsage.digitalSignature, "keyEncipherment", KeyUsage.keyEncipherment,
                "dataEncipherment", KeyUsage.dataEncipherment, "keyAgreement", KeyUsage.keyAgreement,
                "keyCertSign", KeyUsage.keyCertSign, "cRLSign", KeyUsage.cRLSign, "nonRepudiation", KeyUsage.nonRepudiation,
                "encipherOnly", KeyUsage.encipherOnly, "decipherOnly", KeyUsage.decipherOnly);
        int flags = 0;
        for (Object usage : list(x509.get("key_usage"))) {
            if (!usages.containsKey(usage)) {
                throw new IllegalArgumentException("Unsupported certificate key usage: " + usage);
            }
            flags |= usages.get(usage);
        }
        if (flags != 0) {
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(flags));
        }
        List<KeyPurposeId> purposes = new ArrayList<>();
        for (Object eku : list(x509.get("ekus"))) {
            purposes.add(KeyPurposeId.getInstance(new ASN1ObjectIdentifier(String.valueOf(eku))));
        }
        if (!purposes.isEmpty()) {
            builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(purposes.toArray(KeyPurposeId[]::new)));
        }
        List<GeneralName> names = new ArrayList<>();
        Map<String, Object> sans = map(x509.get("sans"));
        for (var field : Map.of("dns_names", GeneralName.dNSName, "emails", GeneralName.rfc822Name, "upns", GeneralName.otherName).entrySet()) {
            if (field.getValue() == GeneralName.otherName && !list(sans.get(field.getKey())).isEmpty()) {
                throw new IllegalArgumentException("UPN subject alternative names are not yet supported");
            }
            for (Object value : list(sans.get(field.getKey()))) {
                names.add(new GeneralName(field.getValue(), String.valueOf(value)));
            }
        }
        if (!names.isEmpty()) {
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names.toArray(GeneralName[]::new)));
        }
    }

    private Response list(String account, boolean deleted) {
        String prefix = account + (deleted ? "/deletedcertificates/" : "/certificates/");
        return ok(Map.of("value", store.scan(key -> key.startsWith(prefix) && !key.substring(prefix.length()).contains("/"))
                .stream().map(value -> deleted ? read(value) : item(read(value))).toList()));
    }

    private Response delete(String account, String name, StoredObject current) throws Exception {
        Map<String, Object> deleted = read(current);
        deleted.put("recoveryId", id(account, "deletedcertificates", name, ""));
        deleted.put("deletedDate", Instant.now().getEpochSecond());
        deleted.put("scheduledPurgeDate", Instant.now().plusSeconds(7 * 86400).getEpochSecond());
        put(base(account, "deletedcertificates", name), deleted, current.metadata());
        for (String kind : List.of("certificates", "keys", "secrets")) {
            String root = base(account, kind, name);
            for (StoredObject object : store.scan(key -> key.equals(root) || key.startsWith(root + "/"))) {
                String tombstone = base(account, "deletedcertificates", name) + "/objects/" + object.key();
                store.put(tombstone, new StoredObject(tombstone, object.data(), object.metadata(), object.lastModified(), object.etag()));
                store.delete(object.key());
            }
        }
        return ok(deleted);
    }

    private Response deleted(String account, String name, String[] parts, String method) throws Exception {
        String root = base(account, "deletedcertificates", name);
        var deleted = store.get(root);
        if (deleted.isEmpty()) {
            return missing(name);
        }
        if (parts.length == 2 && "GET".equals(method)) {
            return ok(read(deleted.get()));
        }
        boolean recover = parts.length == 3 && "recover".equals(parts[2]) && "POST".equals(method);
        if (!recover && !(parts.length == 2 && "DELETE".equals(method))) {
            return error(405, "MethodNotAllowed", "Method not allowed");
        }
        if (recover && (store.get(base(account, "keys", name)).isPresent()
                || store.get(base(account, "secrets", name)).isPresent())) {
            return error(409, "Conflict", "A key or secret with this name already exists");
        }
        for (String key : new ArrayList<>(store.keys())) {
            if (key.startsWith(root + "/objects/")) {
                if (recover) {
                    StoredObject object = store.get(key).orElseThrow();
                    String originalKey = key.substring((root + "/objects/").length());
                    store.put(originalKey, new StoredObject(originalKey, object.data(), object.metadata(), object.lastModified(), object.etag()));
                }
                store.delete(key);
            }
        }
        store.delete(root);
        return recover ? ok(read(store.get(base(account, "certificates", name)).orElseThrow())) : Response.noContent().build();
    }

    private List<StoredObject> versions(String account, String name) {
        String prefix = base(account, "certificates", name) + "/versions/";
        return store.scan(key -> key.startsWith(prefix));
    }

    private static Map<String, Object> item(Map<String, Object> certificate) {
        var item = new LinkedHashMap<String, Object>();
        for (String key : List.of("id", "x5t", "attributes", "tags")) {
            item.put(key, certificate.get(key));
        }
        return item;
    }

    private static Map<String, Object> operation(String account, String name, Map<String, Object> certificate) {
        return Map.of("id", id(account, "certificates", name, "pending"), "status", "completed",
                "status_details", "Certificate created", "target", certificate.get("id"),
                "issuer", Map.of("name", "Self"), "cancellation_requested", false);
    }

    private void persist(String account, String kind, String name, String version, Map<String, Object> body, Map<String, String> metadata) throws Exception {
        put(base(account, kind, name) + "/versions/" + version, body, metadata);
        put(base(account, kind, name), body, metadata);
    }

    private void put(String key, Map<String, Object> body, Map<String, String> metadata) throws Exception {
        store.put(key, new StoredObject(key, JSON.writeValueAsBytes(body), metadata, Instant.now(), UUID.randomUUID().toString()));
    }

    private static Map<String, Object> read(StoredObject object) {
        try {
            return JSON.readValue(object.data(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Stored certificate is invalid", e);
        }
    }

    private static Map<String, Object> map(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        var result = new LinkedHashMap<String, Object>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<?> list(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list;
        }
        throw new IllegalArgumentException("Expected a JSON array");
    }

    private static boolean bool(Map<String, Object> values, String key, boolean fallback) {
        Object value = values.getOrDefault(key, fallback);
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new IllegalArgumentException(key + " must be boolean");
    }

    private static int integer(Object value, String name) {
        if (value instanceof Number number && number.doubleValue() == number.intValue()) {
            return number.intValue();
        }
        throw new IllegalArgumentException(name + " must be an integer");
    }

    private static String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(bytes)
                + "\n-----END " + type + "-----\n";
    }

    private static String base(String account, String kind, String name) { return account + "/" + kind + "/" + name; }
    private static String id(String account, String kind, String name, String version) {
        return "https://" + account + ".vault.azure.net/" + kind + "/" + name + (version.isEmpty() ? "" : "/" + version);
    }
    private static Response ok(Object value) { return Response.ok(value).build(); }
    private static Response missing(String name) { return error(404, "CertificateNotFound", "Certificate not found: " + name); }
    private static Response error(int status, String code, String message) {
        return Response.status(status).entity(Map.of("error", Map.of("code", code, "message", message))).build();
    }
}
