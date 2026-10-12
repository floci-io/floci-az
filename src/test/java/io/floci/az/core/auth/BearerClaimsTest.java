package io.floci.az.core.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BearerClaims: who a request's token says is calling")
class BearerClaimsTest {

    private static String token(String payloadJson) {
        String encode = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return "Bearer eyJhbGciOiJSUzI1NiJ9." + encode + ".signature";
    }

    @Test
    void readsAnApplicationToken() {
        BearerClaims claims = BearerClaims.parse(token("{\"oid\":\"11111111-2222-3333-4444-555555555555\",\"azp\":\"app-1\",\"idtyp\":\"app\"}"));
        assertTrue(claims.present());
        assertEquals("11111111-2222-3333-4444-555555555555", claims.principal());
        assertEquals("app-1", claims.appId());
        assertEquals("Application", claims.principalType());
    }

    @Test
    void readsAUserTokenAndInfersTheTypeWhenAbsent() {
        BearerClaims claims = BearerClaims.parse(token("{\"oid\":\"u-1\",\"upn\":\"me@contoso.com\",\"appid\":\"cli\"}"));
        assertEquals("u-1", claims.principal());
        assertEquals("User", claims.principalType());
        assertEquals("user", claims.idtyp());
        BearerClaims delegated = BearerClaims.parse(token("{\"oid\":\"u-2\",\"appid\":\"cli\",\"scp\":\"user_impersonation\"}"));
        assertEquals("User", delegated.principalType());
        BearerClaims app = BearerClaims.parse(token("{\"appid\":\"only-app\"}"));
        assertEquals("only-app", app.principal());
        assertEquals("Application", app.principalType());
    }

    @Test
    void answersNoneForMissingOrUnreadableTokens() {
        assertFalse(BearerClaims.parse(null).present());
        assertFalse(BearerClaims.parse("Basic abc").present());
        assertFalse(BearerClaims.parse("Bearer not-a-jwt").present());
        assertFalse(BearerClaims.parse("Bearer a.!!!.c").present());
        assertNull(BearerClaims.NONE.principal());
    }
}
