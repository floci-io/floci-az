package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;

/**
 * {@code parity-overrides.strict-providers} alone: namespaces start {@code NotRegistered}, and a request for a
 * resource type of one is refused with {@code 409 MissingSubscriptionRegistration} until it is registered, the
 * way the SDKs' auto-register policies expect.
 */
@QuarkusTest
@TestProfile(ProviderStrictTest.StrictProvidersOnly.class)
@DisplayName("ARM providers: strict-providers override alone")
class ProviderStrictTest {

    public static class StrictProvidersOnly implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-providers", "true");
        }
    }

    private static final String SUB = "7a1d0000-0000-4000-8000-00000000c0b3";
    private static final String API = "?api-version=2024-11-01";
    private static final String STORAGE_API = "?api-version=2023-01-01";
    private static final String PROVIDERS = "/subscriptions/" + SUB + "/providers";
    private static final String ACCOUNT = "/subscriptions/" + SUB
        + "/resourceGroups/rg-providers/providers/Microsoft.Storage/storageAccounts/strictrp0001";
    private static final String STORAGE_BODY =
        "{\"location\":\"eastus\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}}";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void namespacesStartNotRegisteredExceptMicrosoftResources() {
        given().when().get(PROVIDERS + API).then().statusCode(200)
            .body("value.findAll { it.namespace != 'Microsoft.Resources' }.registrationState",
                everyItem(equalTo("NotRegistered")))
            .body("value.find { it.namespace == 'Microsoft.Resources' }.registrationState", equalTo("Registered"));
    }

    @Test
    void aResourcePutIsRefusedUntilItsNamespaceIsRegistered() {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put("/subscriptions/" + SUB + "/resourceGroups/rg-providers" + API).then().statusCode(201);

        given().contentType("application/json").body(STORAGE_BODY).when().put(ACCOUNT + STORAGE_API)
            .then().statusCode(409)
            .body("error.code", equalTo("MissingSubscriptionRegistration"))
            .body("error.details[0].target", equalTo("Microsoft.Storage"));

        given().when().post(PROVIDERS + "/Microsoft.Storage/register" + API).then().statusCode(200)
            .body("registrationState", equalTo("Registered"));

        given().contentType("application/json").body(STORAGE_BODY).when().put(ACCOUNT + STORAGE_API)
            .then().statusCode(not(equalTo(409)));
    }
}
