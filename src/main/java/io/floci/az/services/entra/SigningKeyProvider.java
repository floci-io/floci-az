package io.floci.az.services.entra;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.tls.CertificateGenerator;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the stable RSA signing key used to mint and verify Entra ID JWTs.
 *
 * <p>The keypair is generated once and persisted under the data directory so the {@code kid}
 * (and therefore every previously issued token's key reference) stays valid across restarts.
 * Uses the JDK's built-in RSA provider — see {@link io.floci.az.core.tls.CertificateGenerator}
 * for why BC's RSA JCA registration is avoided in native image.
 */
@ApplicationScoped
public class SigningKeyProvider {

    private static final Logger LOG = Logger.getLogger(SigningKeyProvider.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Encoder STD = Base64.getEncoder();

    private final EmulatorConfig config;
    private final CertificateGenerator certificateGenerator;

    private PrivateKey privateKey;
    private RSAPublicKey publicKey;
    private String kid;
    private String x5c;
    private String x5t;

    @Inject
    public SigningKeyProvider(EmulatorConfig config, CertificateGenerator certificateGenerator) {
        this.config = config;
        this.certificateGenerator = certificateGenerator;
    }

    @PostConstruct
    void init() {
        try {
            Path keyFile = signingKeyFile();
            if (Files.exists(keyFile)) {
                loadFrom(keyFile);
                LOG.infov("Loaded Entra signing key (kid={0})", kid);
            } else {
                generateAndPersist(keyFile);
                LOG.infov("Generated Entra signing key (kid={0}) at {1}", kid, keyFile);
            }
        } catch (Exception e) {
            // Non-fatal: fall back to an ephemeral in-memory key so token issuance still works.
            LOG.errorf(e, "Failed to load/persist Entra signing key; using ephemeral key");
            generateInMemory();
        }
    }

    public PrivateKey privateKey() {
        return privateKey;
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    public String kid() {
        return kid;
    }

    /** JWKS document: {@code {"keys":[{kty,use,alg,kid,n,e,x5c,x5t}]}}. */
    public Map<String, Object> jwks() {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("kty", "RSA");
        key.put("use", "sig");
        key.put("alg", "RS256");
        key.put("kid", kid);
        key.put("n", URL.encodeToString(toUnsignedBytes(publicKey.getModulus())));
        key.put("e", URL.encodeToString(toUnsignedBytes(publicKey.getPublicExponent())));
        if (x5c != null) {
            key.put("x5c", List.of(x5c));
            key.put("x5t", x5t);
        }
        return Map.of("keys", List.of(key));
    }

    private Path signingKeyFile() {
        String dir = config.services().entra().signingKeyPath()
                .orElse(Path.of(config.storage().persistentPath(), "entra").toString());
        return Path.of(dir, "signing-key.pkcs8");
    }

    private void loadFrom(Path keyFile) throws Exception {
        byte[] pkcs8 = Files.readAllBytes(keyFile);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        this.privateKey = priv;
        this.publicKey = (RSAPublicKey) kf.generatePublic(
                new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
        this.kid = deriveKid(this.publicKey);
        buildCertificate();
    }

    private void generateAndPersist(Path keyFile) throws Exception {
        KeyPair pair = newKeyPair();
        Files.createDirectories(keyFile.getParent());
        Files.write(keyFile, pair.getPrivate().getEncoded());
        adopt(pair);
    }

    private void generateInMemory() {
        adopt(newKeyPair());
    }

    private void adopt(KeyPair pair) {
        this.privateKey = pair.getPrivate();
        this.publicKey = (RSAPublicKey) pair.getPublic();
        this.kid = deriveKid(this.publicKey);
        buildCertificate();
    }

    /**
     * Wraps the signing key in a self-signed X.509 certificate and derives the JWKS
     * {@code x5c} (base64 DER, per RFC 7517) and {@code x5t} (base64url SHA-1 thumbprint).
     * Best-effort: on failure the JWKS simply omits both fields.
     */
    private void buildCertificate() {
        try {
            X509Certificate cert = certificateGenerator.certify(
                    new KeyPair(publicKey, privateKey), "floci-az-entra");
            byte[] der = cert.getEncoded();
            this.x5c = STD.encodeToString(der);
            this.x5t = URL.encodeToString(MessageDigest.getInstance("SHA-1").digest(der));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to build x5c/x5t for Entra signing key; JWKS will omit them");
            this.x5c = null;
            this.x5t = null;
        }
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048, SECURE_RANDOM);
            return keyGen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("RSA key generation failed", e);
        }
    }

    /** Stable kid derived from a SHA-256 thumbprint of the public key (base64url, 24 chars). */
    private static String deriveKid(RSAPublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return URL.encodeToString(digest).substring(0, 24);
        } catch (Exception e) {
            throw new IllegalStateException("kid derivation failed", e);
        }
    }

    /** Big-endian, sign-bit-free encoding required by JWK {@code n}/{@code e}. */
    static byte[] toUnsignedBytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }
}
