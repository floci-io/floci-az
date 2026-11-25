package io.floci.az.services.iothub;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static io.floci.az.services.iothub.IotHubTestClient.SERVICE_API;
import static io.floci.az.services.iothub.IotHubTestClient.createHub;
import static io.floci.az.services.iothub.IotHubTestClient.host;
import static io.floci.az.services.iothub.IotHubTestClient.policy;
import static io.floci.az.services.iothub.IotHubTestClient.token;
import static io.floci.az.services.iothub.IotHubTestClient.unsigned;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared access signatures the registry accepts: signed with a key of one of the hub's own policies, for the hub,
 * unexpired, and by a policy whose rights cover the operation.
 */
@QuarkusTest
@DisplayName("IoT Hub service API: shared access signatures")
class IotHubSasTest {

    private static final String HUB = "sashub";
    private static final String DEVICE = "{\"deviceId\": \"d1\"}";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        createHub(HUB);
    }

    private static String key(String keyName, String which) {
        Map<String, Object> policy = policy(HUB, keyName);
        return (String) policy.get(which);
    }

    private static int get(String authorization) {
        return unsigned(HUB).header("Authorization", authorization).when().get("/devices" + SERVICE_API)
                .statusCode();
    }

    private static int put(String authorization) {
        return unsigned(HUB).header("Authorization", authorization).body(DEVICE).when()
                .put("/devices/d1" + SERVICE_API).statusCode();
    }

    @Test
    void eitherKeyOfAPolicySignsATokenForTheHub() {
        assertTrue(get(token(host(HUB), "iothubowner", key("iothubowner", "primaryKey"))) == 200);
        assertTrue(get(token(host(HUB), "iothubowner", key("iothubowner", "secondaryKey"))) == 200);
        // A token for something under the hub, and one for the host as the hub's own name spells it, pass too.
        assertTrue(get(token(host(HUB) + "/devices", "iothubowner", key("iothubowner", "primaryKey"))) == 200);
        assertTrue(get(token("SasHub.azure-devices.net", "iothubowner", key("iothubowner", "primaryKey"))) == 200);
    }

    @Test
    void aTokenThatIsWrongInAnyWayIsRefused() {
        String owner = key("iothubowner", "primaryKey");
        String other = key("service", "primaryKey");

        unsigned(HUB).when().get("/devices" + SERVICE_API).then().statusCode(401)
            .header("iothub-errorcode", equalTo("IotHubUnauthorizedAccess"));
        assertTrue(get(token(host(HUB), "iothubowner", other)) == 401, "signed with another policy's key");
        assertTrue(get(token(host(HUB), "nosuchpolicy", owner)) == 401, "an unknown policy");
        assertTrue(get(token(host(HUB), "iothubowner", owner, Instant.now().getEpochSecond() - 1)) == 401,
                "expired");
        assertTrue(get(token("otherhub.azure-devices.net", "iothubowner", owner)) == 401, "for another hub");
        assertTrue(get(token(host(HUB), null, owner)) == 401, "a device token, without skn");
        assertTrue(get("Bearer abc") == 401, "not a shared access signature");
        assertTrue(get("SharedAccessSignature sr=x") == 401, "malformed");
    }

    @Test
    void aPolicyNameMatchesIgnoringCase() {
        assertTrue(get(token(host(HUB), "IoTHubOwner", key("iothubowner", "primaryKey"))) == 200);
    }

    @Test
    void aSignatureWithAnUnescapedPlusIsRead() {
        String owner = key("iothubowner", "primaryKey");
        String resource = URLEncoder.encode(host(HUB), StandardCharsets.UTF_8);
        // Find an expiry whose signature holds a '+', and send that signature unescaped, as a lenient client does.
        long expiry = Instant.now().getEpochSecond() + 600;
        String signature = IotHubTestClient.sign(owner, resource + "\n" + expiry);
        while (!signature.contains("+")) {
            expiry++;
            signature = IotHubTestClient.sign(owner, resource + "\n" + expiry);
        }

        assertTrue(get("SharedAccessSignature sr=" + resource + "&sig=" + signature + "&se=" + expiry
                + "&skn=iothubowner") == 200);
    }

    @Test
    void aTokenWithAMalformedEscapeIsRefusedNotAFailure() {
        String token = token(host(HUB), "iothubowner", key("iothubowner", "primaryKey"));

        unsigned(HUB).header("Authorization", token.replace("sr=", "sr=%zz")).when().get("/devices" + SERVICE_API)
            .then().statusCode(401)
            .header("iothub-errorcode", equalTo("IotHubUnauthorizedAccess"));
    }

    @Test
    void thePolicyMustHaveTheRightsTheOperationNeeds() {
        String read = token(host(HUB), "registryRead", key("registryRead", "primaryKey"));
        String readWrite = token(host(HUB), "registryReadWrite", key("registryReadWrite", "primaryKey"));
        String device = token(host(HUB), "device", key("device", "primaryKey"));
        String service = token(host(HUB), "service", key("service", "primaryKey"));

        assertTrue(get(read) == 200);
        assertTrue(put(read) == 401, "RegistryRead cannot write");
        assertTrue(put(readWrite) == 200, "RegistryWrite writes");
        assertTrue(get(readWrite) == 200, "RegistryWrite reads too");
        assertTrue(get(device) == 401, "DeviceConnect cannot read the registry");
        assertTrue(get(service) == 401, "ServiceConnect cannot read the registry");
    }

    @Test
    void rightsAreReadInTheSpecsSpelling() {
        assertTrue(IotHubSas.permits("RegistryWrite, ServiceConnect, DeviceConnect", IotHubSas.Access.WRITE));
        assertTrue(IotHubSas.permits("RegistryWrite, ServiceConnect, DeviceConnect", IotHubSas.Access.READ));
        assertTrue(IotHubSas.permits("RegistryRead", IotHubSas.Access.READ));
        assertFalse(IotHubSas.permits("RegistryRead", IotHubSas.Access.WRITE));
        assertFalse(IotHubSas.permits("ServiceConnect, DeviceConnect", IotHubSas.Access.READ));
        assertFalse(IotHubSas.permits(null, IotHubSas.Access.READ));
    }
}
