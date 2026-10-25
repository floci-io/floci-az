package io.floci.az.services.policy.evaluation;

import io.floci.az.core.auth.BearerClaims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Policy requestContext().identity from the bearer claims")
class PolicyEvaluationRuleIdentityTest {

    private static String bearer(String payload) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
            + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    @Test
    void keepsArrayClaimsAsArraysAndFillsTheDerivedOnes() {
        Map<String, Object> identity = PolicyEvaluationRule.identity(BearerClaims.parse(bearer(
            "{\"oid\": \"o1\", \"azp\": \"a1\", \"tid\": \"t1\", \"acrs\": [\"c1\", \"c2\"], \"roles\": [\"r\"]}")));
        assertEquals(List.of("c1", "c2"), identity.get("acrs"));
        assertEquals("a1", identity.get("appid"));
        assertEquals("app", identity.get("idtyp"));
        assertEquals("o1", identity.get("http://schemas.microsoft.com/identity/claims/objectidentifier"));
        assertFalse(identity.containsKey("roles"), "only the claims the policy context carries");
    }

    @Test
    void aUserTokenNamesTheUserAndNoTokenNamesNothing() {
        Map<String, Object> user = PolicyEvaluationRule.identity(BearerClaims.parse(bearer(
            "{\"oid\": \"u1\", \"upn\": \"me@contoso.example\"}")));
        assertEquals("user", user.get("idtyp"));
        assertEquals("me@contoso.example", user.get("upn"));
        assertTrue(PolicyEvaluationRule.identity(BearerClaims.NONE).isEmpty());
    }
}
