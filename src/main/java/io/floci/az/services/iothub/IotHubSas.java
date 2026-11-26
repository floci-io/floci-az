package io.floci.az.services.iothub;

import io.floci.az.core.AzureRequest;
import io.floci.az.services.iothub.IotHubModels.AccessPolicy;
import io.floci.az.services.iothub.IotHubModels.IotHubResource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks the shared access signature a service client signs a registry request with, as IoT Hub documents it:
 * {@code SharedAccessSignature sr={resource URI}&sig={signature}&se={expiry}&skn={policy}}, where the signature is
 * HMAC-SHA256 of the URL-encoded, lower-case resource URI, a newline and the expiry, keyed with a policy key decoded
 * from base64.
 *
 * <p>Every registry request is checked, whatever the parity overrides: the hub's keys are what tell its callers
 * apart. Nothing here logs the token, its signature or a key.
 */
final class IotHubSas {

    private static final String SCHEME = "SharedAccessSignature ";
    private static final String HMAC = "HmacSHA256";

    /** What a registry operation needs from the policy that signed it. */
    enum Access {
        /** Reading identities: {@code RegistryRead}, or {@code RegistryWrite}, which includes it. */
        READ,
        /** Creating, changing and deleting identities: {@code RegistryWrite}. */
        WRITE
    }

    private IotHubSas() {
    }

    /**
     * Why the request may not perform {@code access} on {@code hub}, or empty when its token is good: signed with a
     * key of a policy of the hub that has the rights, for this hub, and not expired.
     *
     * @param hostName the hub's host name, which {@code sr} names; the host the request was sent to passes too, so a
     *                 client that addresses the hub by another name floci-az serves can sign for that name
     */
    static Optional<String> refusal(AzureRequest req, IotHubResource hub, String hostName, Access access,
                                    Instant now) {
        String header = req.headers() == null ? null : req.headers().getHeaderString("Authorization");
        if (header == null || !header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            return Optional.of("The request carries no shared access signature.");
        }
        Map<String, String> raw = fields(header.substring(SCHEME.length()));
        Map<String, String> fields = new HashMap<>();
        for (Map.Entry<String, String> field : raw.entrySet()) {
            Optional<String> decoded = decode(field.getValue());
            if (decoded.isEmpty()) {
                return Optional.of("The shared access signature is malformed.");
            }
            fields.put(field.getKey(), decoded.get());
        }
        String resource = fields.get("sr");
        String signature = fields.get("sig");
        String expiry = fields.get("se");
        String keyName = fields.get("skn");
        if (resource == null || signature == null || expiry == null) {
            return Optional.of("The shared access signature is malformed.");
        }
        if (keyName == null) {
            return Optional.of("A device's token cannot manage the identity registry.");
        }
        if (!addressesHub(resource, hostName, requestHost(req))) {
            return Optional.of("The shared access signature is for another resource.");
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(expiry);
        } catch (NumberFormatException malformed) {
            return Optional.of("The shared access signature's expiry is malformed.");
        }
        if (expiresAt <= now.getEpochSecond()) {
            return Optional.of("The shared access signature has expired.");
        }
        Optional<AccessPolicy> policy = hub.policies().stream()
                // Policy names match ignoring case, as the ARM side matches them.
                .filter(candidate -> candidate.keyName().equalsIgnoreCase(keyName))
                .findFirst();
        if (policy.isEmpty() || !signedBy(policy.get(), raw.get("sr"), expiry, signature)) {
            return Optional.of("The shared access signature is not valid for this hub.");
        }
        if (!permits(policy.get().rights(), access)) {
            return Optional.of("The policy '" + keyName + "' does not allow this operation.");
        }
        return Optional.empty();
    }

    /** Whether {@code rights}, in the spec's {@code AccessRights} spelling, allow {@code access}. */
    static boolean permits(String rights, Access access) {
        Set<String> granted = Arrays.stream(rights == null ? new String[0] : rights.split(","))
                .map(String::trim)
                .collect(Collectors.toSet());
        boolean write = granted.contains("RegistryWrite");
        return access == Access.WRITE ? write : write || granted.contains("RegistryRead");
    }

    /**
     * {@code value} percent-decoded, or empty when it holds a malformed escape. A {@code +} stays a {@code +}: in a
     * {@code sig} it is base64, which a client may leave unescaped, not an encoded space.
     */
    private static Optional<String> decode(String value) {
        try {
            return Optional.of(URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /** The token's fields as sent: {@code sr} is signed in that form, and {@code sig} is URL-encoded base64. */
    private static Map<String, String> fields(String token) {
        Map<String, String> fields = new HashMap<>();
        for (String pair : token.trim().split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                fields.put(pair.substring(0, equals).trim().toLowerCase(Locale.ROOT), pair.substring(equals + 1));
            }
        }
        return fields;
    }

    /** Whether {@code resource} is the hub itself or something under it, such as {@code {host}/devices/d1}. */
    private static boolean addressesHub(String resource, String hostName, String requestHost) {
        String host = resource.toLowerCase(Locale.ROOT);
        int scheme = host.indexOf("://");
        if (scheme >= 0) {
            host = host.substring(scheme + 3);
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        host = withoutPort(host);
        return host.equals(hostName.toLowerCase(Locale.ROOT))
                || (requestHost != null && host.equals(requestHost));
    }

    private static String requestHost(AzureRequest req) {
        String host = req.host() != null ? req.host() : req.authority();
        return host == null ? null : withoutPort(host.toLowerCase(Locale.ROOT));
    }

    private static String withoutPort(String host) {
        int colon = host.lastIndexOf(':');
        return colon > 0 && host.indexOf(']') < colon ? host.substring(0, colon) : host;
    }

    /**
     * Whether {@code signature} is the policy's primary or secondary key signing the resource until the expiry. The
     * resource is signed as the token carries it, URL-encoded: the documentation asks for it in lower case, and the
     * az CLI signs the host name as the hub answers it, so both forms pass.
     */
    private static boolean signedBy(AccessPolicy policy, String encodedResource, String expiry, String signature) {
        byte[] sent;
        try {
            sent = Base64.getDecoder().decode(signature);
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
        // The caller has decoded sr already, so it decodes here too.
        String decoded = decode(encodedResource).orElseThrow();
        List<String> signed = List.of(encodedResource + "\n" + expiry,
                URLEncoder.encode(decoded.toLowerCase(Locale.ROOT), StandardCharsets.UTF_8) + "\n" + expiry);
        return signed.stream().anyMatch(toSign -> matches(policy.primaryKey(), toSign, sent)
                || matches(policy.secondaryKey(), toSign, sent));
    }

    private static boolean matches(String base64Key, String toSign, byte[] sent) {
        if (base64Key == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(base64Key), HMAC));
            return MessageDigest.isEqual(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)), sent);
        } catch (GeneralSecurityException | IllegalArgumentException unusableKey) {
            // A stored key that is not base64 signs nothing, so no token matches it.
            return false;
        }
    }
}
