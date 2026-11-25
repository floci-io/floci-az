package io.floci.az.services.iothub;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static io.floci.az.services.iothub.IotHubTestClient.API;
import static io.floci.az.services.iothub.IotHubTestClient.SERVICE_API;
import static io.floci.az.services.iothub.IotHubTestClient.createHub;
import static io.floci.az.services.iothub.IotHubTestClient.deleteHub;
import static io.floci.az.services.iothub.IotHubTestClient.hubPath;
import static io.floci.az.services.iothub.IotHubTestClient.owner;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The device identity registry on {@code {hub}.azure-devices.net}, driven as the az CLI's {@code azure-iot} extension
 * drives it: {@code device-identity create}, {@code show}, {@code update}, {@code renew-key}, {@code list} and
 * {@code delete}.
 */
@QuarkusTest
@DisplayName("IoT Hub service API: the device identity registry")
class IotHubDevicesTest {

    /** What {@code device-identity create -d {id}} sends: a {@code sas} identity whose keys the hub generates. */
    private static final String CREATE_SAS = "{\"deviceId\": \"%s\", \"status\": \"enabled\", "
            + "\"authentication\": {\"symmetricKey\": {\"primaryKey\": null, \"secondaryKey\": null}, "
            + "\"type\": \"sas\"}, \"capabilities\": {\"iotEdge\": %s}}";
    private static final String BASE64_32 = "[A-Za-z0-9+/]{43}=";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static Response create(String hub, String deviceId) {
        return create(hub, deviceId, false);
    }

    private static Response create(String hub, String deviceId, boolean edge) {
        return owner(hub).body(String.format(CREATE_SAS, deviceId, edge))
                .when().put("/devices/" + deviceId + SERVICE_API);
    }

    @Test
    void createGeneratesTheKeysAndAnswersTheDevice() {
        createHub("devhub-create");

        create("devhub-create", "esp32-1").then().statusCode(200)
            .body("deviceId", equalTo("esp32-1"))
            .body("generationId", matchesPattern("\\d{18}"))
            .body("etag", notNullValue())
            .body("status", equalTo("enabled"))
            .body("statusReason", nullValue())
            .body("connectionState", equalTo("Disconnected"))
            .body("connectionStateUpdatedTime", equalTo("0001-01-01T00:00:00Z"))
            .body("lastActivityTime", equalTo("0001-01-01T00:00:00Z"))
            .body("cloudToDeviceMessageCount", equalTo(0))
            .body("authentication.type", equalTo("sas"))
            .body("authentication.symmetricKey.primaryKey", matchesPattern(BASE64_32))
            .body("authentication.symmetricKey.secondaryKey", matchesPattern(BASE64_32))
            .body("authentication.x509Thumbprint.primaryThumbprint", nullValue())
            .body("capabilities.iotEdge", equalTo(false))
            .body("parentScopes", hasSize(0));

        owner("devhub-create").when().get("/devices/esp32-1" + SERVICE_API).then().statusCode(200)
            .body("deviceId", equalTo("esp32-1"))
            .body("authentication.symmetricKey.primaryKey", matchesPattern(BASE64_32));
    }

    @Test
    void createKeepsTheKeysItIsGiven() {
        createHub("devhub-keys");
        String primary = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
        String secondary = "HxwdHhsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=";

        owner("devhub-keys").body("{\"deviceId\": \"d1\", \"authentication\": {\"type\": \"sas\", \"symmetricKey\": "
                + "{\"primaryKey\": \"" + primary + "\", \"secondaryKey\": \"" + secondary + "\"}}}")
            .when().put("/devices/d1" + SERVICE_API).then().statusCode(200)
            .body("authentication.symmetricKey.primaryKey", equalTo(primary))
            .body("authentication.symmetricKey.secondaryKey", equalTo(secondary));
        owner("devhub-keys").body("{\"authentication\": {\"type\": \"sas\", \"symmetricKey\": "
                + "{\"primaryKey\": \"c2hvcnQ=\", \"secondaryKey\": \"" + secondary + "\"}}}")
            .when().put("/devices/d2" + SERVICE_API).then().statusCode(400)
            .header("iothub-errorcode", equalTo("ArgumentInvalid"));
    }

    @Test
    void aSecondCreateOfTheSameDeviceIsAConflict() {
        createHub("devhub-dup");
        create("devhub-dup", "d1").then().statusCode(200);

        create("devhub-dup", "d1").then().statusCode(409)
            .header("iothub-errorcode", equalTo("DeviceAlreadyExists"))
            .body("Message", startsWith("ErrorCode:DeviceAlreadyExists;"))
            .body("ExceptionMessage", startsWith("Tracking ID:"));
    }

    @Test
    void anUpdateNeedsTheCurrentEtagOrAStar() {
        createHub("devhub-update");
        Response created = create("devhub-update", "d1");
        String etag = created.path("etag");
        String generationId = created.path("generationId");
        String key = created.path("authentication.symmetricKey.primaryKey");
        String body = "{\"deviceId\": \"d1\", \"status\": \"Disabled\", \"statusReason\": \"stolen\", "
                + "\"authentication\": {\"type\": \"sas\", \"symmetricKey\": {\"primaryKey\": \"" + key
                + "\", \"secondaryKey\": \"" + key + "\"}}}";

        Response updated = owner("devhub-update").header("If-Match", "\"" + etag + "\"").body(body)
            .when().put("/devices/d1" + SERVICE_API);
        updated.then().statusCode(200)
            .body("status", equalTo("disabled"))
            .body("statusReason", equalTo("stolen"))
            .body("generationId", equalTo(generationId))
            .body("authentication.symmetricKey.primaryKey", equalTo(key));
        assertNotEquals(etag, updated.path("etag"));

        owner("devhub-update").header("If-Match", "\"" + etag + "\"").body(body)
            .when().put("/devices/d1" + SERVICE_API).then().statusCode(412)
            .header("iothub-errorcode", equalTo("PreconditionFailed"));
        owner("devhub-update").header("If-Match", "\"*\"").body(body.replace("Disabled", "enabled"))
            .when().put("/devices/d1" + SERVICE_API).then().statusCode(200)
            .body("status", equalTo("enabled"));
        owner("devhub-update").header("If-Match", "\"*\"").body(body)
            .when().put("/devices/missing" + SERVICE_API).then().statusCode(400);
        owner("devhub-update").header("If-Match", "\"*\"").body(body.replace("\"d1\"", "\"missing\""))
            .when().put("/devices/missing" + SERVICE_API).then().statusCode(404)
            .header("iothub-errorcode", equalTo("DeviceNotFound"));
    }

    @Test
    void renewingAKeySendsTheIdentityWithoutIt() {
        createHub("devhub-renew");
        Response created = create("devhub-renew", "d1");
        String primary = created.path("authentication.symmetricKey.primaryKey");
        String secondary = created.path("authentication.symmetricKey.secondaryKey");

        // device-identity renew-key --key-type primary: the key to renew is sent as null.
        owner("devhub-renew").header("If-Match", "\"*\"").body("{\"deviceId\": \"d1\", \"authentication\": "
                + "{\"type\": \"sas\", \"symmetricKey\": {\"primaryKey\": null, \"secondaryKey\": \"" + secondary
                + "\"}}}")
            .when().put("/devices/d1" + SERVICE_API).then().statusCode(200)
            .body("authentication.symmetricKey.primaryKey", not(equalTo(primary)))
            .body("authentication.symmetricKey.secondaryKey", equalTo(secondary));
    }

    @Test
    void renewKeyRegeneratesTheKeysItNames() {
        createHub("devhub-regen");
        Response created = create("devhub-regen", "d1");
        String primary = created.path("authentication.symmetricKey.primaryKey");
        String secondary = created.path("authentication.symmetricKey.secondaryKey");
        owner("devhub-regen").body("{\"deviceId\": \"x509\", \"authentication\": {\"type\": \"selfSigned\", "
                + "\"x509Thumbprint\": {\"primaryThumbprint\": \"" + "A".repeat(40) + "\"}}}")
            .when().put("/devices/x509" + SERVICE_API).then().statusCode(200);

        // device-identity renew-key --key-type primary, as the CLI sends it.
        Response renewed = owner("devhub-regen")
            .body("{\"policyKey\": \"primaryKey\", \"devices\": [{\"id\": \"d1\"}, {\"id\": \"x509\"}, "
                + "{\"id\": \"missing\"}]}")
            .when().post("/devices/keys/regenerate" + SERVICE_API);
        renewed.then().statusCode(200)
            .body("policyKey", equalTo("primaryKey"))
            .body("rotatedKeys.id", contains("d1"))
            .body("rotatedKeys[0].primaryKey", not(equalTo(primary)))
            .body("rotatedKeys[0].secondaryKey", equalTo(secondary))
            .body("errors.id", contains("x509", "missing"))
            .body("errors.errorCode", contains("ArgumentInvalid", "DeviceNotFound"));
        owner("devhub-regen").when().get("/devices/d1" + SERVICE_API).then().statusCode(200)
            .body("authentication.symmetricKey.primaryKey", equalTo(renewed.path("rotatedKeys[0].primaryKey")))
            .body("etag", not(equalTo(created.path("etag"))));

        owner("devhub-regen").body("{\"policyKey\": \"both\", \"devices\": [{\"id\": \"d1\"}]}")
            .when().post("/devices/keys/regenerate" + SERVICE_API).then().statusCode(200)
            .body("rotatedKeys[0].secondaryKey", not(equalTo(secondary)));
        owner("devhub-regen").body("{\"policyKey\": \"swap\", \"devices\": [{\"id\": \"d1\"}]}")
            .when().post("/devices/keys/regenerate" + SERVICE_API).then().statusCode(400);
        IotHubTestClient.as("devhub-regen", "registryRead")
            .body("{\"policyKey\": \"both\", \"devices\": [{\"id\": \"d1\"}]}")
            .when().post("/devices/keys/regenerate" + SERVICE_API).then().statusCode(401);
    }

    @Test
    void deleteRemovesTheDevice() {
        createHub("devhub-delete");
        String etag = create("devhub-delete", "d1").path("etag");

        owner("devhub-delete").header("If-Match", "\"stale\"").when().delete("/devices/d1" + SERVICE_API)
            .then().statusCode(412);
        owner("devhub-delete").header("If-Match", "\"" + etag + "\"").when().delete("/devices/d1" + SERVICE_API)
            .then().statusCode(204);
        owner("devhub-delete").when().get("/devices/d1" + SERVICE_API).then().statusCode(404)
            .header("iothub-errorcode", equalTo("DeviceNotFound"));
        owner("devhub-delete").header("If-Match", "\"*\"").when().delete("/devices/d1" + SERVICE_API)
            .then().statusCode(404);
    }

    @Test
    void aHubThatDoesNotExistAnswersNotFound() {
        createHub("devhub-known");
        String token = IotHubTestClient.token("devhub-unknown.azure-devices.net", "iothubowner",
                (String) IotHubTestClient.policy("devhub-known", "iothubowner").get("primaryKey"));

        IotHubTestClient.unsigned("devhub-unknown").header("Authorization", token)
            .when().get("/devices/d1" + SERVICE_API).then().statusCode(404)
            .header("iothub-errorcode", equalTo("IotHubNotFound"));
    }

    @Test
    void deviceIdsAreCaseSensitiveAndCheckedAgainstLearnsRule() {
        createHub("devhub-ids");
        create("devhub-ids", "Sensor").then().statusCode(200);
        create("devhub-ids", "sensor").then().statusCode(200);
        // ? and % are allowed: the path carries them escaped, and the ID keeps them.
        owner("devhub-ids").body("{\"deviceId\": \"a?b%c\"}").when().put("/devices/a%3Fb%25c" + SERVICE_API)
            .then().statusCode(200).body("deviceId", equalTo("a?b%c"));

        owner("devhub-ids").when().get("/devices/a%3Fb%25c" + SERVICE_API).then().statusCode(200)
            .body("deviceId", equalTo("a?b%c"));
        owner("devhub-ids").body("{}").when().put("/devices/has%23hash" + SERVICE_API).then().statusCode(400)
            .header("iothub-errorcode", equalTo("ArgumentInvalid"));
        owner("devhub-ids").body("{}").when().put("/devices/" + "x".repeat(129) + SERVICE_API).then().statusCode(400);
        owner("devhub-ids").when().get("/devices" + SERVICE_API).then().statusCode(200)
            .body("deviceId", contains("Sensor", "a?b%c", "sensor"));
    }

    @Test
    void anEdgeDeviceGetsItsOwnScope() {
        createHub("devhub-edge");

        Response edge = create("devhub-edge", "gateway", true);
        edge.then().statusCode(200)
            .body("capabilities.iotEdge", equalTo(true))
            .body("deviceScope", startsWith("ms-azure-iot-edge://gateway-"));
        String scope = edge.path("deviceScope");
        owner("devhub-edge").header("If-Match", "\"*\"")
            .body("{\"deviceId\": \"gateway\", \"status\": \"disabled\", \"capabilities\": {\"iotEdge\": true}}")
            .when().put("/devices/gateway" + SERVICE_API).then().statusCode(200)
            .body("deviceScope", equalTo(scope));
    }

    @Test
    void aLeafThatBecomesAnEdgeDeviceGetsAScopeOfItsOwn() {
        createHub("devhub-promote");
        String parentScope = create("devhub-promote", "gateway", true).path("deviceScope");
        owner("devhub-promote").body("{\"deviceId\": \"leaf\", \"deviceScope\": \"" + parentScope + "\"}")
            .when().put("/devices/leaf" + SERVICE_API).then().statusCode(200)
            .body("deviceScope", equalTo(parentScope));

        owner("devhub-promote").header("If-Match", "\"*\"")
            .body("{\"deviceId\": \"leaf\", \"capabilities\": {\"iotEdge\": true}}")
            .when().put("/devices/leaf" + SERVICE_API).then().statusCode(200)
            .body("deviceScope", startsWith("ms-azure-iot-edge://leaf-"));
    }

    @Test
    void aPathWithAMalformedEscapeIsRefusedNotAFailure() throws IOException {
        createHub("devhub-escape");

        String token = IotHubTestClient.token(IotHubTestClient.host("devhub-escape"), "iothubowner",
                (String) IotHubTestClient.policy("devhub-escape", "iothubowner").get("primaryKey"));
        // HTTP clients refuse to send such a path, so it goes over a socket, as a careless client would send it. The
        // HTTP server refuses it before the registry sees it; the registry's own check is the fallback behind that.
        String answer = rawGet("/devices/50%zz" + SERVICE_API, IotHubTestClient.host("devhub-escape"), token);

        assertTrue(answer.startsWith("HTTP/1.1 400"), answer);
    }

    private static String rawGet(String path, String host, String authorization) throws IOException {
        try (Socket socket = new Socket("localhost", RestAssured.port)) {
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nAuthorization: "
                    + authorization + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aContinuationOutsideTheResultsAnswersAnEmptyLastPage() {
        createHub("devhub-pages");
        for (int i = 0; i < 3; i++) {
            create("devhub-pages", "d" + i).then().statusCode(200);
        }
        String query = "{\"query\": \"select * from devices\"}";

        // An offset at the top of the int range would overflow once the page size is added to it.
        for (String offset : List.of(Integer.toString(Integer.MAX_VALUE), "3", "10")) {
            owner("devhub-pages").header("x-ms-max-item-count", "1000").header("x-ms-continuation", token(offset))
                .body(query).when().post("/devices/query" + SERVICE_API).then().statusCode(200)
                .header("x-ms-continuation", nullValue())
                .body("$", hasSize(0));
        }
        for (String invalid : List.of(token("-1"), token("x"), "not base64!")) {
            owner("devhub-pages").header("x-ms-continuation", invalid)
                .body(query).when().post("/devices/query" + SERVICE_API).then().statusCode(400)
                .header("iothub-errorcode", equalTo("ArgumentInvalid"));
        }

        // The result set shrinks between pages: the next page is what is left after the offset, here nothing.
        String next = owner("devhub-pages").header("x-ms-max-item-count", "2").body(query)
            .when().post("/devices/query" + SERVICE_API).then().statusCode(200).body("$", hasSize(2))
            .extract().header("x-ms-continuation");
        for (String id : List.of("d0", "d1")) {
            owner("devhub-pages").header("If-Match", "\"*\"").when().delete("/devices/" + id + SERVICE_API)
                .then().statusCode(204);
        }
        owner("devhub-pages").header("x-ms-max-item-count", "2").header("x-ms-continuation", next).body(query)
            .when().post("/devices/query" + SERVICE_API).then().statusCode(200)
            .header("x-ms-continuation", nullValue())
            .body("$", hasSize(0));
    }

    private static String token(String offset) {
        return Base64.getEncoder().encodeToString(offset.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void theListQueryAnswersTwinsInPages() {
        createHub("devhub-query");
        for (int i = 0; i < 5; i++) {
            create("devhub-query", "d" + i).then().statusCode(200);
        }
        create("devhub-query", "edge", true).then().statusCode(200);

        List<String> ids = new ArrayList<>();
        String continuation = null;
        int pages = 0;
        do {
            RequestSpecification request = owner("devhub-query")
                    .header("x-ms-max-item-count", "4");
            if (continuation != null) {
                request = request.header("x-ms-continuation", continuation);
            }
            Response page = request.body("{\"query\": \"select * from devices\"}")
                .when().post("/devices/query" + SERVICE_API);
            page.then().statusCode(200).header("x-ms-item-type", equalTo("Twin"));
            ids.addAll(page.path("deviceId"));
            continuation = page.header("x-ms-continuation");
            pages++;
        } while (continuation != null);

        assertEquals(List.of("d0", "d1", "d2", "d3", "d4", "edge"), ids);
        assertEquals(2, pages);

        owner("devhub-query").body("{\"query\": \"SELECT * FROM devices WHERE capabilities.iotEdge=true\"}")
            .when().post("/devices/query" + SERVICE_API).then().statusCode(200)
            .body("deviceId", contains("edge"))
            .body("[0].authenticationType", equalTo("sas"))
            .body("[0].statusUpdateTime", notNullValue())
            .body("[0].properties.desired.'$version'", equalTo(1))
            .body("[0].properties.reported.'$version'", equalTo(1));
        owner("devhub-query").body("{\"query\": \"select deviceId from devices\"}")
            .when().post("/devices/query" + SERVICE_API).then().statusCode(400)
            .header("iothub-errorcode", equalTo("SqlQueryInvalid"));
    }

    @Test
    void theHubsViewsCountTheRegistry() {
        createHub("devhub-stats");
        create("devhub-stats", "on").then().statusCode(200);
        create("devhub-stats", "off").then().statusCode(200);
        owner("devhub-stats").header("If-Match", "\"*\"").body("{\"deviceId\": \"off\", \"status\": \"disabled\"}")
            .when().put("/devices/off" + SERVICE_API).then().statusCode(200);

        given().when().get(hubPath("devhub-stats") + "/IotHubStats" + API).then().statusCode(200)
            .body("totalDeviceCount", equalTo(2))
            .body("enabledDeviceCount", equalTo(1))
            .body("disabledDeviceCount", equalTo(1));
        given().when().get(hubPath("devhub-stats") + "/quotaMetrics" + API).then().statusCode(200)
            .body("value.find { it.name == 'TotalDeviceCount' }.currentValue", equalTo(2));
    }

    @Test
    void deletingAHubDeletesItsDevices() {
        createHub("devhub-cascade");
        create("devhub-cascade", "d1").then().statusCode(200);

        deleteHub("devhub-cascade");
        createHub("devhub-cascade");

        owner("devhub-cascade").when().get("/devices/d1" + SERVICE_API).then().statusCode(404);
        given().when().get(hubPath("devhub-cascade") + "/IotHubStats" + API).then().statusCode(200)
            .body("totalDeviceCount", equalTo(0));
    }

    @Test
    void twoHubsHoldTheirOwnRecordsForTheSameId() {
        createHub("devhub-one");
        createHub("devhub-two");
        String one = create("devhub-one", "same").path("authentication.symmetricKey.primaryKey");
        String two = create("devhub-two", "same").path("authentication.symmetricKey.primaryKey");
        assertNotEquals(one, two);

        owner("devhub-one").header("If-Match", "\"*\"").when().delete("/devices/same" + SERVICE_API)
            .then().statusCode(204);
        owner("devhub-two").when().get("/devices/same" + SERVICE_API).then().statusCode(200)
            .body("authentication.symmetricKey.primaryKey", equalTo(two));
        assertNull(owner("devhub-one").when().get("/devices/same" + SERVICE_API).path("deviceId"));
    }

    @Test
    void otherServiceOperationsAreNotServedYet() {
        createHub("devhub-twins");

        owner("devhub-twins").when().get("/twins/d1" + SERVICE_API).then().statusCode(404);
        owner("devhub-twins").when().get("/devices/d1/modules" + SERVICE_API).then().statusCode(404);
    }
}
