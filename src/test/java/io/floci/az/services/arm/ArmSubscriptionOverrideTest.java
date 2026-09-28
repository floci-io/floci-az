package io.floci.az.services.arm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.common.mapper.TypeRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Overriding the default subscription and tenant must move every surface that reports them: the ARM
 * tenant and subscription listings, and the IMDS system-assigned identity, whose scope follows the
 * subscription through the {@code system-assigned-scope} property expression.
 */
@QuarkusTest
@TestProfile(ArmSubscriptionOverrideTest.OverrideProfile.class)
@DisplayName("ARM subscription and tenant follow their config keys")
class ArmSubscriptionOverrideTest {

    private static final String SUB = "12345678-aaaa-bbbb-cccc-1234567890ab";
    private static final String TENANT = "87654321-dddd-eeee-ffff-ba0987654321";

    public static class OverrideProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-az.services.arm.default-subscription-id", SUB,
                    "floci-az.services.entra.default-tenant-id", TENANT);
        }
    }

    @Test
    void tenantAndSubscriptionListingsReportTheOverrides() {
        given().when().get("/tenants?api-version=2022-12-01")
                .then().statusCode(200)
                .body("value[0].tenantId", equalTo(TENANT));

        given().when().get("/subscriptions?api-version=2022-12-01")
                .then().statusCode(200)
                .body("value[0].subscriptionId", equalTo(SUB))
                .body("value[0].tenantId", equalTo(TENANT));
    }

    @Test
    void systemAssignedIdentityScopeFollowsTheSubscription() throws Exception {
        Map<String, String> armProps = given()
                .when().get("/subscriptions/" + SUB
                        + "/providers/Microsoft.ManagedIdentity/identities/default?api-version=2023-01-31")
                .then().statusCode(200)
                .extract().path("properties");

        Map<String, Object> token = given().header("Metadata", "true")
                .when().get("/metadata/identity/oauth2/token?resource=https://management.azure.com/&api-version=2018-02-01")
                .then().statusCode(200)
                .extract().as(new TypeRef<Map<String, Object>>() {});

        Map<?, ?> claims = new ObjectMapper().readValue(
                Base64.getUrlDecoder().decode(((String) token.get("access_token")).split("\\.")[1]), Map.class);
        assertEquals(armProps.get("principalId"), claims.get("oid"));
        assertEquals(TENANT, claims.get("tid"));
        assertEquals(TENANT, armProps.get("tenantId"));
    }
}
