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
    void idtypFollowsTheTokenWhenItCarriesNone() {
        // A delegated token carries the user's scopes: scp alone reads as a user, with no upn at all.
        Map<String, Object> delegated = PolicyEvaluationRule.identity(BearerClaims.parse(bearer(
            "{\"oid\": \"u1\", \"appid\": \"cli\", \"scp\": \"user_impersonation\"}")));
        assertEquals("user", delegated.get("idtyp"));
        // An app-only token carries roles instead, and names no user.
        Map<String, Object> appOnly = PolicyEvaluationRule.identity(BearerClaims.parse(bearer(
            "{\"oid\": \"s1\", \"appid\": \"daemon\", \"roles\": [\"Reader\"]}")));
        assertEquals("app", appOnly.get("idtyp"));
        // An explicit idtyp always wins over what the other claims imply.
        Map<String, Object> explicit = PolicyEvaluationRule.identity(BearerClaims.parse(bearer(
            "{\"oid\": \"s1\", \"scp\": \"x\", \"idtyp\": \"app\"}")));
        assertEquals("app", explicit.get("idtyp"));
        // A token that names no principal (none of oid, appid, azp or a user name) implies no caller type.
        Map<String, Object> anonymous = PolicyEvaluationRule.identity(BearerClaims.parse(bearer(
            "{\"tid\": \"t1\", \"scp\": \"x\"}")));
        assertFalse(anonymous.containsKey("idtyp"));
        assertEquals("t1", anonymous.get("tid"));
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
