package io.floci.az.services.iothub;

import io.floci.az.services.arm.ArmLocationsPerCloudTest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.floci.az.services.iothub.IotHubTestClient.SERVICE_API;
import static io.floci.az.services.iothub.IotHubTestClient.token;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * A hub in the US Government cloud answers its registry on the {@code hostName} that cloud gives it,
 * {@code {hub}.azure-devices.us}. The profile is the one the per-cloud locations test runs under, so this class costs
 * no restart of its own.
 */
@QuarkusTest
@TestProfile(ArmLocationsPerCloudTest.GovSubscription.class)
@DisplayName("IoT Hub service API: a hub in the US Government cloud")
class IotHubGovernmentCloudTest {

    /** The subscription the profile places in a US Government tenant. */
    private static final String GOV_SUB = "eeeeeeee-1111-0000-0000-0000000000c5";
    private static final String HUB = "devhub-gov";
    private static final String GOV_HOST = HUB + ".azure-devices.us";
    private static final String PATH = "/subscriptions/" + GOV_SUB + "/resourceGroups/rg/providers/Microsoft.Devices"
            + "/IotHubs/" + HUB + IotHubTestClient.API;

    @Test
    void theRegistryAnswersOnTheGovernmentHostName() {
        given().contentType("application/json")
            .body("{\"location\": \"usgovvirginia\", \"sku\": {\"name\": \"S1\", \"capacity\": 1}}")
            .when().put(PATH).then().statusCode(201)
            .body("properties.hostName", equalTo(GOV_HOST));
        List<Map<String, Object>> policies = given().when()
                .post(PATH.replace("?", "/listkeys?")).then().statusCode(200).extract().path("value");
        String key = (String) policies.stream().filter(policy -> "iothubowner".equals(policy.get("keyName")))
                .findFirst().orElseThrow().get("primaryKey");

        given().header("Host", GOV_HOST).urlEncodingEnabled(false).contentType("application/json")
            .header("Authorization", token(GOV_HOST, "iothubowner", key))
            .body("{\"deviceId\": \"d1\", \"authentication\": {\"type\": \"sas\"}}")
            .when().put("/devices/d1" + SERVICE_API).then().statusCode(200)
            .body("deviceId", equalTo("d1"));

        // The public cloud's host for the name finds no hub: the hub lives in the Government cloud.
        given().header("Host", HUB + ".azure-devices.net").urlEncodingEnabled(false)
            .header("Authorization", token(HUB + ".azure-devices.net", "iothubowner", key))
            .when().get("/devices/d1" + SERVICE_API).then().statusCode(404)
            .header("iothub-errorcode", equalTo("IotHubNotFound"));

        given().when().delete(PATH).then().statusCode(200);
    }
}
