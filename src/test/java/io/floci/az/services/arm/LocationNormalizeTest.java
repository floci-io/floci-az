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

/**
 * {@code parity-overrides.location-normalize} alone: a display-name location is stored and echoed canonically,
 * and a global type is {@code global} whatever its casing.
 */
@QuarkusTest
@TestProfile(LocationNormalizeTest.NormalizeOnly.class)
@DisplayName("ARM locations: location-normalize override alone")
class LocationNormalizeTest {

    public static class NormalizeOnly implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.location-normalize", "true");
        }
    }

    private static final String SUB = "7a1d0000-0000-4000-8000-00000000c0d2";
    private static final String RG = "/subscriptions/" + SUB + "/resourceGroups/rg-loc";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void aStorageAccountSentWithADisplayNameIsStoredCanonically() {
        String account = RG + "/providers/Microsoft.Storage/storageAccounts/locnormacct01";
        given().contentType("application/json")
            .body("{\"location\":\"East US 2\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}}")
            .when().put(account + "?api-version=2023-01-01").then().statusCode(200)
            .body("location", equalTo("eastus2"));
        given().when().get(account + "?api-version=2023-01-01").then().statusCode(200)
            .body("location", equalTo("eastus2"));
    }

    @Test
    void aPrivateDnsZoneIsGlobalWhateverItsCasing() {
        given().contentType("application/json").body("{\"location\":\"Global\"}")
            .when().put(RG + "/providers/Microsoft.Network/privateDnsZones/loc.example?api-version=2020-06-01")
            .then().statusCode(201)
            .body("location", equalTo("global"));
    }
}
