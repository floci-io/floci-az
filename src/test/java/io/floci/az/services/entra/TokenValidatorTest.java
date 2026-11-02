package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TokenValidator: signature, lifetime, issuer and audience")
class TokenValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KID = "test-kid";
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String AUDIENCE = "https://management.azure.com";
    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    private static KeyPair keys;
    private static KeyPair otherKeys;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        otherKeys = generator.generateKeyPair();
    }

    private static TokenValidator validator(String configuredIssuer) {
        return new TokenValidator(() -> (RSAPublicKey) keys.getPublic(), () -> KID, configuredIssuer,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Map<String, Object> claims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "https://floci-az:4577/" + TENANT + "/v2.0");
        claims.put("aud", AUDIENCE);
        claims.put("tid", TENANT);
        claims.put("nbf", NOW.minusSeconds(60).getEpochSecond());
        claims.put("exp", NOW.plusSeconds(3600).getEpochSecond());
        return claims;
    }

    private static String segment(Object value) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MAPPER.writeValueAsBytes(value));
    }

    private static String token(Map<String, Object> claims, String alg, String kid, PrivateKey key) throws Exception {
        String signed = segment(Map.of("alg", alg, "kid", kid, "typ", "JWT")) + "." + segment(claims);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(signed.getBytes(StandardCharsets.US_ASCII));
        return signed + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    private static String bearer(Map<String, Object> claims) throws Exception {
        return "Bearer " + token(claims, "RS256", KID, keys.getPrivate());
    }

    private static Optional<TokenValidator.Rejection> check(String authorization) {
        return validator(null).validate(authorization, AUDIENCE::equals);
    }

    private static void assertInvalid(String authorization, String messagePart) {
        TokenValidator.Rejection rejection = check(authorization).orElseThrow();
        assertEquals(TokenValidator.Reason.INVALID, rejection.reason());
        assertTrue(rejection.message().contains(messagePart), rejection.message());
    }

    @Test
    void aTokenTheEmulatorSignedIsAccepted() throws Exception {
        assertEquals(Optional.empty(), check(bearer(claims())));
    }

    @Test
    void theBearerSchemeIsMatchedInAnyCase() throws Exception {
        assertEquals(Optional.empty(), check(bearer(claims()).replace("Bearer ", "bearer ")));
    }

    @Test
    void anIssuerOnAnyHostIsAcceptedInEitherVersionsFormWhenItsTenantMatches() throws Exception {
        for (String issuer : new String[] {"http://localhost:4577/" + TENANT + "/v2.0",
            "https://localhost:4577/" + TENANT + "/", "https://sts.windows.net/" + TENANT + "/"}) {
            Map<String, Object> claims = claims();
            claims.put("iss", issuer);
            assertEquals(Optional.empty(), check(bearer(claims)), issuer);
        }
    }

    @Test
    void aMissingHeaderIsItsOwnReason() {
        for (String authorization : new String[] {null, "", "  "}) {
            assertEquals(TokenValidator.Reason.MISSING, check(authorization).orElseThrow().reason());
        }
    }

    @Test
    void anythingButAWellFormedBearerJwtIsInvalid() {
        assertInvalid("SharedKey account:signature", "Bearer");
        assertInvalid("Bearer opaque-token", "JSON Web Token");
        assertInvalid("Bearer not.base64!.token", "JSON Web Token");
        assertInvalid("Bearer e30.e30.e30", "signing key");
    }

    @Test
    void aSignatureFromAnotherKeyOrOverOtherClaimsIsInvalid() throws Exception {
        assertInvalid("Bearer " + token(claims(), "RS256", KID, otherKeys.getPrivate()), "signature");

        String[] parts = bearer(claims()).split("\\.");
        Map<String, Object> tampered = claims();
        tampered.put("aud", "https://other.example");
        assertInvalid(parts[0] + "." + segment(tampered) + "." + parts[2], "signature");
        assertInvalid(parts[0] + "." + parts[1] + ".AAAA", "signature");
    }

    @Test
    void anotherAlgorithmOrKeyIdIsInvalidWithoutTryingTheSignature() throws Exception {
        assertInvalid("Bearer " + token(claims(), "none", KID, keys.getPrivate()), "signing key");
        assertInvalid("Bearer " + token(claims(), "RS256", "other-kid", keys.getPrivate()), "signing key");
    }

    @Test
    void aTokenOutsideItsLifetimeIsInvalidOnceTheClockSkewIsUsedUp() throws Exception {
        Map<String, Object> expired = claims();
        expired.put("exp", NOW.minusSeconds(301).getEpochSecond());
        assertInvalid(bearer(expired), "expired");

        Map<String, Object> withinSkew = claims();
        withinSkew.put("exp", NOW.minusSeconds(299).getEpochSecond());
        assertEquals(Optional.empty(), check(bearer(withinSkew)));

        Map<String, Object> early = claims();
        early.put("nbf", NOW.plusSeconds(301).getEpochSecond());
        assertInvalid(bearer(early), "not valid before");

        Map<String, Object> noExpiry = claims();
        noExpiry.remove("exp");
        assertInvalid(bearer(noExpiry), "no expiry");
    }

    @Test
    void anIssuerOrTenantThatDoesNotLineUpIsInvalid() throws Exception {
        for (String issuer : new String[] {"https://floci-az:4577/other-tenant/v2.0", "https://floci-az:4577/",
            "ftp://floci-az/" + TENANT + "/", "/" + TENANT + "/v2.0", "not a uri", ""}) {
            Map<String, Object> claims = claims();
            claims.put("iss", issuer);
            assertInvalid(bearer(claims), "issuer");
        }
        Map<String, Object> noTenant = claims();
        noTenant.remove("tid");
        assertInvalid(bearer(noTenant), "tenant");
    }

    @Test
    void aConfiguredIssuerIsComparedExactly() throws Exception {
        TokenValidator validator = validator("https://issuer.example/fixed");
        Map<String, Object> claims = claims();
        assertEquals(TokenValidator.Reason.INVALID,
            validator.validate(bearer(claims), AUDIENCE::equals).orElseThrow().reason());

        claims.put("iss", "https://issuer.example/fixed");
        assertEquals(Optional.empty(), validator.validate(bearer(claims), AUDIENCE::equals));
    }

    @Test
    void anAudienceTheServiceDoesNotAcceptIsItsOwnReason() throws Exception {
        Map<String, Object> claims = claims();
        claims.put("aud", "https://vault.azure.net");

        TokenValidator.Rejection rejection = check(bearer(claims)).orElseThrow();

        assertEquals(TokenValidator.Reason.AUDIENCE, rejection.reason());
        assertTrue(rejection.message().contains("https://vault.azure.net"), rejection.message());
    }
}
