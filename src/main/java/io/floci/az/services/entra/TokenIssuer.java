package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;
import java.util.UUID;

/**
 * Mints RS256-signed Entra ID JWTs without a JWT library — JCA {@code Signature("SHA256withRSA")}
 * over the {@code base64url(header).base64url(payload)} signing input, consistent with the
 * project's "no extra deps" stance.
 */
@ApplicationScoped
public class TokenIssuer {

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SigningKeyProvider keys;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public TokenIssuer(SigningKeyProvider keys) {
        this.keys = keys;
    }

    /**
     * @param tenantId        directory tenant id ({@code tid})
     * @param issuer          fully-qualified issuer ({@code iss})
     * @param audience        token audience ({@code aud})
     * @param subject         subject ({@code sub}); for app-only tokens this is the SP object id
     * @param oid             directory object id ({@code oid})
     * @param appId           client application id ({@code appid}/{@code azp})
     * @param scope           space-delimited scopes ({@code scp}); null for app-only tokens
     * @param version         "1.0" or "2.0" — controls {@code ver} and appid vs azp
     * @param idtyp           identity type ({@code idtyp}); "app" for app-only tokens, null otherwise
     * @param lifetimeSeconds access token lifetime
     */
    public record TokenSpec(
        String tenantId,
        String issuer,
        String audience,
        String subject,
        String oid,
        String appId,
        String scope,
        String version,
        String idtyp,
        long lifetimeSeconds
    ) {}

    /** Stable GUID derived from an arbitrary seed, for synthetic directory object ids. */
    public static String deterministicGuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Returns the compact serialised, signed JWT. */
    public String issue(TokenSpec spec) {
        Instant now = Instant.now();
        long iat = now.getEpochSecond();
        long exp = iat + spec.lifetimeSeconds();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("typ", "JWT");
        header.put("alg", "RS256");
        header.put("kid", keys.kid());

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", spec.issuer());
        claims.put("sub", spec.subject());
        claims.put("aud", spec.audience());
        claims.put("exp", exp);
        claims.put("iat", iat);
        claims.put("nbf", iat);
        claims.put("oid", spec.oid());
        claims.put("tid", spec.tenantId());
        claims.put("ver", spec.version());
        if ("2.0".equals(spec.version())) {
            claims.put("azp", spec.appId());
        } else {
            claims.put("appid", spec.appId());
        }
        if (spec.scope() != null && !spec.scope().isBlank()) {
            claims.put("scp", spec.scope());
        }
        if (spec.idtyp() != null && !spec.idtyp().isBlank()) {
            claims.put("idtyp", spec.idtyp());
        }
        claims.put("uti", newUti());

        try {
            String signingInput =
                URL.encodeToString(mapper.writeValueAsBytes(header)) + "." +
                URL.encodeToString(mapper.writeValueAsBytes(claims));

            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(keys.privateKey());
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            String signature = URL.encodeToString(signer.sign());

            return signingInput + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign token", e);
        }
    }

    /** Unique per-token identifier ({@code uti}) — 128 bits of randomness, base64url, like Azure. */
    private static String newUti() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return URL.encodeToString(bytes);
    }
}
