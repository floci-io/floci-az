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
import static org.hamcrest.Matchers.startsWith;

/**
 * {@code parity-overrides.strict-locations} alone: a resource PUT naming a location the subscription's cloud does
 * not offer is refused with Azure's {@code 400 NoRegisteredProviderFound}, before its handler runs.
 */
@QuarkusTest
@TestProfile(StrictLocationsTest.StrictLocationsOnly.class)
@DisplayName("ARM locations: strict-locations override alone")
class StrictLocationsTest {

    public static class StrictLocationsOnly implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-locations", "true");
        }
    }

    private static final String SUB = "7a1d0000-0000-4000-8000-00000000c0d3";
    private static final String ACCOUNT = "/subscriptions/" + SUB
        + "/resourceGroups/rg-strict-loc/providers/Microsoft.Storage/storageAccounts/";
    private static final String API = "?api-version=2023-01-01";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String body(String location) {
        return "{\"location\":\"" + location + "\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}}";
    }

    @Test
    void aLocationOfAnotherCloudIsNoRegisteredProviderFound() {
        given().contentType("application/json").body(body("usgovvirginia"))
            .when().put(ACCOUNT + "strictloc0001" + API).then().statusCode(400)
            .body("error.code", equalTo("NoRegisteredProviderFound"))
            .body("error.message", startsWith("No registered resource provider found for location 'usgovvirginia' "
                + "and API version '2023-01-01' for type 'storageAccounts'."));
        given().when().get(ACCOUNT + "strictloc0001" + API).then().statusCode(404);
    }

    @Test
    void aLocationOfTheCloudPassesAsSentWhenNormalizingIsOff() {
        given().contentType("application/json").body(body("East US"))
            .when().put(ACCOUNT + "strictloc0002" + API).then().statusCode(200)
            .body("location", equalTo("East US"));
    }
}
