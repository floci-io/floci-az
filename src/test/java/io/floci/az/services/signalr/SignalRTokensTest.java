package io.floci.az.services.signalr;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SignalRTokensTest {
    private static final String KEY = "local-test-access-key";
    private static final String AUDIENCE = "http://localhost/client/?hub=test";

    @Test
    void validatesSignatureAudienceAndExpiryAndPreservesRepeatedClaims() throws Exception {
        String token = token(Instant.now().getEpochSecond() + 60);
        var claims = SignalRTokens.verify(token, KEY, AUDIENCE);
        assertEquals("alice", claims.userId());
        assertEquals(2, claims.values().stream().filter(claim -> claim.getKey().equals("role")).count());
        assertThrows(IllegalArgumentException.class, () -> SignalRTokens.verify(token, "wrong", AUDIENCE));
        assertThrows(IllegalArgumentException.class, () -> SignalRTokens.verify(token, KEY, AUDIENCE + "other"));
        assertThrows(IllegalArgumentException.class, () -> SignalRTokens.verify(token(0), KEY, AUDIENCE));
    }

    private static String token(long expiry) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String body = encoder.encodeToString(new JsonObject().put("aud", AUDIENCE).put("exp", expiry)
                .put("asrs.s.uid", "alice").put("role", List.of("reader", "writer")).encode().getBytes(StandardCharsets.UTF_8));
        String unsigned = header + "." + body;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return unsigned + "." + encoder.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
    }
}
