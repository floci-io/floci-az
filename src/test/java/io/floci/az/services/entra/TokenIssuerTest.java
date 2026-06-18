package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TokenIssuerTest {

    private static final Base64.Decoder URL = Base64.getUrlDecoder();
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject TokenIssuer tokenIssuer;
    @Inject SigningKeyProvider keys;

    @Test
    void mintsValidRs256TokenVerifiableWithPublicKey() throws Exception {
        var spec = new TokenIssuer.TokenSpec(
            "00000000-0000-0000-0000-000000000002",
            "http://localhost:4577/00000000-0000-0000-0000-000000000002/v2.0",
            "api://resource", "sub-oid", "sub-oid", "client-app", "Files.Read",
            "2.0", "app", 3599);

        String jwt = tokenIssuer.issue(spec);
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length, "JWT must have header.payload.signature");

        // Signature verifies against the provider's public key.
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keys.publicKey());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(URL.decode(parts[2])), "signature must verify");

        Map<?, ?> header = mapper.readValue(URL.decode(parts[0]), Map.class);
        assertEquals("RS256", header.get("alg"));
        assertEquals(keys.kid(), header.get("kid"));

        Map<?, ?> claims = mapper.readValue(URL.decode(parts[1]), Map.class);
        assertEquals(spec.issuer(), claims.get("iss"));
        assertEquals("api://resource", claims.get("aud"));
        assertEquals("00000000-0000-0000-0000-000000000002", claims.get("tid"));
        assertEquals("2.0", claims.get("ver"));
        assertEquals("client-app", claims.get("azp"));
        assertEquals("Files.Read", claims.get("scp"));
        assertEquals("app", claims.get("idtyp"), "app-only tokens carry idtyp=app");
        assertNotNull(claims.get("uti"), "every token carries a unique token id");
        long exp = ((Number) claims.get("exp")).longValue();
        long iat = ((Number) claims.get("iat")).longValue();
        assertEquals(3599, exp - iat);
    }

    @Test
    void signatureVerifiesAgainstPublicKeyRebuiltFromJwks() throws Exception {
        String jwt = tokenIssuer.issue(new TokenIssuer.TokenSpec(
            "tid", "iss", "aud", "sub", "oid", "app", null, "1.0", null, 60));
        String[] parts = jwt.split("\\.");

        @SuppressWarnings("unchecked")
        Map<String, Object> key = ((List<Map<String, Object>>) keys.jwks().get("keys")).get(0);
        BigInteger n = new BigInteger(1, URL.decode((String) key.get("n")));
        BigInteger e = new BigInteger(1, URL.decode((String) key.get("e")));
        PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pub);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(URL.decode(parts[2])),
            "token must verify against key reconstructed from JWKS n/e");

        Map<?, ?> claims = mapper.readValue(URL.decode(parts[1]), Map.class);
        assertEquals("app", claims.get("appid"), "v1.0 tokens use appid, not azp");
        assertNull(claims.get("scp"), "null scope omits scp claim");
    }
}
