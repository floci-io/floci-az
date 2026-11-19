package io.floci.az.services.iothub;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The {@code Microsoft.Devices} management plane. The request bodies are the ones the az CLI sent in its {@code iot}
 * test recordings (2023-06-30-preview) and the expected answers are what Azure gave it, so these pin property names,
 * defaults and the key semantics, not just status codes.
 */
@QuarkusTest
@DisplayName("Microsoft.Devices: IoT hubs and their shared access policies")
class IotHubArmServiceTest {

    private static final String SUB = "10700000-0000-4000-8000-000000000001";
    private static final String API = "?api-version=2023-06-30";
    private static final String KEY_PATTERN = "[A-Za-z0-9+/]{43}=";
    /** The body {@code az iot hub create --sku S1} sends. */
    private static final String CLI_BODY = """
            {"location": "westus2",
             "properties": {"minTlsVersion": "1.2",
                            "eventHubEndpoints": {"events": {"retentionTimeInDays": 3, "partitionCount": 4}},
                            "storageEndpoints": {"$default": {"sasTtlAsIso8601": "PT1H", "connectionString": "",
                                                              "containerName": ""}},
                            "messagingEndpoints": {"fileNotifications": {"lockDurationAsIso8601": "PT15S",
                                                   "ttlAsIso8601": "PT20H", "maxDeliveryCount": 79}},
                            "enableFileUploadNotifications": false,
                            "cloudToDevice": {"maxDeliveryCount": 89, "defaultTtlAsIso8601": "PT23H",
                                              "feedback": {"lockDurationAsIso8601": "PT35S", "ttlAsIso8601": "P1DT5H",
                                                           "maxDeliveryCount": 40}}},
             "sku": {"name": "S1", "capacity": 1}}""";
    private static final String MINIMAL_BODY = "{\"location\": \"westus2\", \"sku\": {\"name\": \"S1\", \"capacity\": 1}}";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String group(String rg) {
        return "/subscriptions/" + SUB + "/resourceGroups/" + rg + "/providers/Microsoft.Devices/IotHubs";
    }

    private static String hub(String rg, String name) {
        return group(rg) + "/" + name;
    }

    private static Response put(String rg, String name, String body) {
        return given().contentType("application/json").body(body).when().put(hub(rg, name) + API);
    }

    private static Response listKeys(String rg, String name) {
        return given().when().post(hub(rg, name) + "/listkeys" + API);
    }

    @Test
    void creatingAHubAnswersTheProvisionedResourceAzureDoes() {
        put("rg", "hub-one", CLI_BODY).then().statusCode(201)
            .header("Azure-AsyncOperation", nullValue())
            .body("id", equalTo(hub("rg", "hub-one")))
            .body("name", equalTo("hub-one"))
            .body("type", equalTo("Microsoft.Devices/IotHubs"))
            .body("location", equalTo("westus2"))
            .body("tags", anEmptyMap())
            .body("subscriptionid", equalTo(SUB))
            .body("resourcegroup", equalTo("rg"))
            .body("etag", matchesPattern("[A-Za-z0-9+/]{11}="))
            .body("properties.locations[0].location", equalTo("West US 2"))
            .body("properties.locations[0].role", equalTo("primary"))
            .body("properties.state", equalTo("Active"))
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.hostName", equalTo("hub-one.azure-devices.net"))
            .body("properties.ipFilterRules", empty())
            .body("properties.minTlsVersion", equalTo("1.2"))
            .body("properties.eventHubEndpoints.events.retentionTimeInDays", equalTo(3))
            .body("properties.eventHubEndpoints.events.partitionCount", equalTo(4))
            .body("properties.eventHubEndpoints.events.partitionIds", contains("0", "1", "2", "3"))
            .body("properties.eventHubEndpoints.events.path", matchesPattern("hub-one[a-z0-9]{6}"))
            .body("properties.eventHubEndpoints.events.endpoint",
                matchesPattern("sb://iothub-ns-hub-one-[0-9]{8}-[0-9a-f]{10}\\.servicebus\\.windows\\.net/"))
            .body("properties.routing.routes", empty())
            .body("properties.routing.endpoints.cosmosDBSqlContainers", empty())
            .body("properties.routing.fallbackRoute.name", equalTo("$fallback"))
            .body("properties.routing.fallbackRoute.source", equalTo("DeviceMessages"))
            .body("properties.routing.fallbackRoute.condition", equalTo("true"))
            .body("properties.routing.fallbackRoute.endpointNames", contains("events"))
            .body("properties.routing.fallbackRoute.isEnabled", equalTo(true))
            .body("properties.storageEndpoints.'$default'.sasTtlAsIso8601", equalTo("PT1H"))
            .body("properties.messagingEndpoints.fileNotifications.maxDeliveryCount", equalTo(79))
            .body("properties.cloudToDevice.feedback.ttlAsIso8601", equalTo("P1DT5H"))
            .body("properties.enableFileUploadNotifications", equalTo(false))
            .body("properties.features", equalTo("None"))
            .body("properties.allowedFqdnList", empty())
            .body("sku.name", equalTo("S1"))
            .body("sku.tier", equalTo("Standard"))
            .body("sku.capacity", equalTo(1))
            .body("identity.type", equalTo("None"))
            .body("systemData.createdAt", notNullValue());
    }

    @Test
    void aHubCreatedWithoutPropertiesGetsAzuresDefaults() {
        put("rg", "hub-defaults", MINIMAL_BODY).then().statusCode(201)
            .body("properties.eventHubEndpoints.events.retentionTimeInDays", equalTo(1))
            .body("properties.eventHubEndpoints.events.partitionCount", equalTo(4))
            .body("properties.cloudToDevice.maxDeliveryCount", equalTo(10))
            .body("properties.cloudToDevice.defaultTtlAsIso8601", equalTo("PT1H"))
            .body("properties.storageEndpoints.'$default'.connectionString", equalTo(""))
            .body("properties.routing.fallbackRoute.isEnabled", equalTo(true))
            .body("properties", not(hasKey("minTlsVersion")));
    }

    @Test
    void aSectionSentEmptyStaysEmptyAndOnlyOmittedSectionsGetDefaults() {
        // The body azurerm_iothub 3.x sends. It reads a $default storage endpoint back as a file_upload block, so an
        // empty storageEndpoints must stay empty or a hub without file upload would never plan clean.
        put("rg", "hub-terraform", """
                {"identity": {"type": "None"}, "location": "eastus",
                 "properties": {"allowedFqdnList": [], "cloudToDevice": {}, "disableLocalAuth": false,
                                "enableFileUploadNotifications": false,
                                "eventHubEndpoints": {"events": {"partitionCount": 4, "retentionTimeInDays": 1}},
                                "features": "None", "ipFilterRules": [], "messagingEndpoints": {},
                                "routing": {"fallbackRoute": {"source": "DeviceMessages", "condition": "true",
                                                              "endpointNames": ["events"], "isEnabled": true}},
                                "storageEndpoints": {}},
                 "sku": {"capacity": 1, "name": "S1"}, "tags": {"env": "b"}}""")
            .then().statusCode(201)
            .body("properties.storageEndpoints", anEmptyMap())
            .body("properties.messagingEndpoints", anEmptyMap())
            .body("properties.cloudToDevice", anEmptyMap())
            .body("properties.disableLocalAuth", equalTo(false));

        put("rg", "hub-minimal", MINIMAL_BODY).then().statusCode(201)
            .body("properties.storageEndpoints.'$default'.sasTtlAsIso8601", equalTo("PT1H"))
            .body("properties.cloudToDevice.maxDeliveryCount", equalTo(10));
    }

    @Test
    void aMalformedPolicyCollectionIsRefusedAndChangesNothing() {
        put("rg", "hub-bad-policies", MINIMAL_BODY).then().statusCode(201);
        String owner = listKeys("rg", "hub-bad-policies").then().extract()
                .path("value.find { it.keyName == 'iothubowner' }.primaryKey");

        for (String policies : List.of("{}", "\"iothubowner\"", "7", "[\"iothubowner\"]")) {
            put("rg", "hub-bad-policies", """
                    {"location": "westus2", "sku": {"name": "S1"}, "tags": {"changed": "yes"},
                     "properties": {"authorizationPolicies": %s}}""".formatted(policies))
                .then().statusCode(400)
                .body("error.code", equalTo("InvalidRequestContent"));
        }

        given().when().get(hub("rg", "hub-bad-policies") + API).then().statusCode(200)
            .body("tags", anEmptyMap());
        listKeys("rg", "hub-bad-policies").then()
            .body("value.find { it.keyName == 'iothubowner' }.primaryKey", equalTo(owner));
        // null still means "keep the policies", as an omitted collection does.
        put("rg", "hub-bad-policies", """
                {"location": "westus2", "sku": {"name": "S1"}, "properties": {"authorizationPolicies": null}}""")
            .then().statusCode(201);
        listKeys("rg", "hub-bad-policies").then().body("value", hasSize(5));
    }

    @Test
    void anETagFromADeletedHubDoesNotMatchTheHubCreatedInItsPlace() {
        String stale = put("rg", "hub-reborn", MINIMAL_BODY).then().statusCode(201).extract().path("etag");
        given().when().delete(hub("rg", "hub-reborn") + API).then().statusCode(200);
        String fresh = put("rg", "hub-reborn", MINIMAL_BODY).then().statusCode(201).extract().path("etag");

        assertNotEquals(stale, fresh);
        given().contentType("application/json").header("If-Match", stale).body(MINIMAL_BODY)
            .when().put(hub("rg", "hub-reborn") + API).then().statusCode(412);
        given().contentType("application/json").header("If-Match", fresh).body(MINIMAL_BODY)
            .when().put(hub("rg", "hub-reborn") + API).then().statusCode(201);
    }

    @Test
    void endpointSecretsAreMaskedOnReadAndKeptWhenSentBackMasked() {
        String storage = "DefaultEndpointsProtocol=https;EndpointSuffix=core.windows.net;AccountName=acct;"
                + "AccountKey=c2VjcmV0LXN0b3JhZ2Uta2V5";
        String bus = "Endpoint=sb://ns.servicebus.windows.net/;SharedAccessKeyName=send;"
                + "SharedAccessKey=c2VjcmV0LWJ1cy1rZXk=;EntityPath=queue";
        put("rg", "hub-secrets", """
                {"location": "westus2", "sku": {"name": "S1"},
                 "properties": {"storageEndpoints": {"$default": {"sasTtlAsIso8601": "PT1H",
                                                                  "connectionString": "%s", "containerName": "c"}},
                                "routing": {"endpoints": {"serviceBusQueues": [{"name": "q1", "connectionString": "%s"}]}}}}
                """.formatted(storage, bus))
            .then().statusCode(201)
            .body("properties.storageEndpoints.'$default'.connectionString",
                    equalTo(storage.replace("c2VjcmV0LXN0b3JhZ2Uta2V5", "****")))
            .body("properties.routing.endpoints.serviceBusQueues[0].connectionString",
                    equalTo(bus.replace("c2VjcmV0LWJ1cy1rZXk=", "****")));

        String shown = given().when().get(hub("rg", "hub-secrets") + API).then().statusCode(200).extract().asString();
        String listed = given().when().get(group("rg") + API).then().statusCode(200).extract().asString();
        String listedBySubscription = given().when()
                .get("/subscriptions/" + SUB + "/providers/Microsoft.Devices/IotHubs" + API)
                .then().statusCode(200).extract().asString();
        for (String body : List.of(shown, listed, listedBySubscription)) {
            assertFalse(body.contains("c2VjcmV0"), body);
        }

        // The az CLI writes back what it read; the masked secrets must not overwrite the stored ones (SecretsTest).
        given().contentType("application/json").body(shown).when().put(hub("rg", "hub-secrets") + API)
            .then().statusCode(201)
            .body("properties.storageEndpoints.'$default'.connectionString",
                    equalTo(storage.replace("c2VjcmV0LXN0b3JhZ2Uta2V5", "****")));
    }

    @Test
    void aMisspeltIdentityTypeIsRefusedAndTheIdentityKept() {
        String principal = put("rg", "hub-identity-typo", """
                {"location": "westus2", "sku": {"name": "S1"}, "identity": {"type": "SystemAssigned"}}""")
            .then().statusCode(201).extract().path("identity.principalId");

        for (String identity : List.of("{\"type\": \"SystemAsigned\"}", "{\"type\": 7}", "\"SystemAssigned\"")) {
            put("rg", "hub-identity-typo", """
                    {"location": "westus2", "sku": {"name": "S1"}, "identity": %s}""".formatted(identity))
                .then().statusCode(400)
                .body("error.code", equalTo("InvalidRequestContent"));
        }
        given().when().get(hub("rg", "hub-identity-typo") + API).then().statusCode(200)
            .body("identity.type", equalTo("SystemAssigned"))
            .body("identity.principalId", equalTo(principal));
    }

    @Test
    void thePartitionCountIsFixedAtCreation() {
        put("rg", "hub-partitions", """
                {"location": "westus2", "sku": {"name": "S1"},
                 "properties": {"eventHubEndpoints": {"events": {"partitionCount": 2, "retentionTimeInDays": 1}}}}""")
            .then().statusCode(201);

        // A PUT that leaves the endpoint out keeps the count rather than falling back to the default of 4.
        put("rg", "hub-partitions", MINIMAL_BODY).then().statusCode(201)
            .body("properties.eventHubEndpoints.events.partitionCount", equalTo(2))
            .body("properties.eventHubEndpoints.events.partitionIds", contains("0", "1"));
        put("rg", "hub-partitions", """
                {"location": "westus2", "sku": {"name": "S1"},
                 "properties": {"eventHubEndpoints": {"events": {"retentionTimeInDays": 3}}}}""")
            .then().statusCode(201)
            .body("properties.eventHubEndpoints.events.partitionCount", equalTo(2))
            .body("properties.eventHubEndpoints.events.retentionTimeInDays", equalTo(3));

        put("rg", "hub-partitions", """
                {"location": "westus2", "sku": {"name": "S1"},
                 "properties": {"eventHubEndpoints": {"events": {"partitionCount": 4}}}}""")
            .then().statusCode(400)
            .body("error.message", containsString("cannot be changed"));
        given().when().get(hub("rg", "hub-partitions") + API).then()
            .body("properties.eventHubEndpoints.events.partitionCount", equalTo(2));
    }

    @Test
    void unitsAndRetentionAreBoundedAsAzureBoundsThem() {
        put("rg", "hub-units", "{\"location\": \"westus2\", \"sku\": {\"name\": \"F1\", \"capacity\": 2}}")
            .then().statusCode(400).body("error.message", containsString("at most 1 IoT hub unit"));
        put("rg", "hub-units", "{\"location\": \"westus2\", \"sku\": {\"name\": \"S3\", \"capacity\": 11}}")
            .then().statusCode(400);
        put("rg", "hub-units", "{\"location\": \"westus2\", \"sku\": {\"name\": \"S1\", \"capacity\": 200}}")
            .then().statusCode(201);

        for (String retention : List.of("0", "8", "1.5", "\"3\"")) {
            put("rg", "hub-retention", """
                    {"location": "westus2", "sku": {"name": "S1"},
                     "properties": {"eventHubEndpoints": {"events": {"retentionTimeInDays": %s}}}}"""
                    .formatted(retention))
                .then().statusCode(400).body("error.message", containsString("retentionTimeInDays"));
        }
        put("rg", "hub-retention", """
                {"location": "westus2", "sku": {"name": "S1"},
                 "properties": {"eventHubEndpoints": {"events": {"retentionTimeInDays": 7}}}}""")
            .then().statusCode(201);
        put("rg", "hub-free-retention", """
                {"location": "westus2", "sku": {"name": "F1"},
                 "properties": {"eventHubEndpoints": {"events": {"partitionCount": 2, "retentionTimeInDays": 2}}}}""")
            .then().statusCode(400).body("error.message", containsString("from 1 to 1"));
    }

    @Test
    void aConditionalPutAfterADeleteDoesNotBringTheHubBack() {
        // Client A reads the hub; client B deletes it; A's delayed update still carries the ETag it read.
        String read = put("rg", "hub-deleted", MINIMAL_BODY).then().statusCode(201).extract().path("etag");
        given().when().delete(hub("rg", "hub-deleted") + API).then().statusCode(200);

        given().contentType("application/json").header("If-Match", read).body(MINIMAL_BODY)
            .when().put(hub("rg", "hub-deleted") + API).then().statusCode(412)
            .body("error.code", equalTo("PreconditionFailed"));
        given().when().get(hub("rg", "hub-deleted") + API).then().statusCode(404);
        given().when().post(hub("rg", "hub-deleted") + "/listkeys" + API).then().statusCode(404);

        // An unconditional create, as the az CLI and Terraform send it, and one with If-Match: * still create.
        put("rg", "hub-deleted", MINIMAL_BODY).then().statusCode(201);
        given().contentType("application/json").header("If-Match", "*").body(MINIMAL_BODY)
            .when().put(hub("rg", "hub-wildcard") + API).then().statusCode(201);
    }

    @Test
    void patchAndDeleteRefuseAStaleIfMatch() {
        String first = put("rg", "hub-concurrent", MINIMAL_BODY).then().statusCode(201).extract().path("etag");
        String second = put("rg", "hub-concurrent", MINIMAL_BODY).then().statusCode(201).extract().path("etag");

        given().contentType("application/json").header("If-Match", first).body("{\"tags\": {\"a\": \"b\"}}")
            .when().patch(hub("rg", "hub-concurrent") + API).then().statusCode(412)
            .body("error.code", equalTo("PreconditionFailed"));
        given().header("If-Match", first).when().delete(hub("rg", "hub-concurrent") + API).then().statusCode(412);
        given().when().get(hub("rg", "hub-concurrent") + API).then().statusCode(200)
            .body("tags", anEmptyMap())
            .body("etag", equalTo(second));

        String third = given().contentType("application/json").header("If-Match", second)
            .body("{\"tags\": {\"a\": \"b\"}}")
            .when().patch(hub("rg", "hub-concurrent") + API).then().statusCode(200).extract().path("etag");
        given().header("If-Match", third).when().delete(hub("rg", "hub-concurrent") + API).then().statusCode(200);
        // A hub that is already gone has no version to compare: DELETE stays idempotent.
        given().header("If-Match", third).when().delete(hub("rg", "hub-concurrent") + API).then().statusCode(204);
    }

    @Test
    void cosmosKeysAreMaskedAndAMaskWithNothingToKeepIsRefused() {
        put("rg", "hub-cosmos", """
                {"location": "westus2", "sku": {"name": "S1"},
                 "properties": {"routing": {"endpoints": {"cosmosDBSqlContainers": [
                     {"name": "c1", "endpointUri": "https://acct.documents.azure.com", "databaseName": "db",
                      "containerName": "msgs", "primaryKey": "cHJpbWFyeQ==", "secondaryKey": "c2Vjb25kYXJ5"}]}}}}""")
            .then().statusCode(201)
            .body("properties.routing.endpoints.cosmosDBSqlContainers[0].primaryKey", equalTo("****"))
            .body("properties.routing.endpoints.cosmosDBSqlContainers[0].secondaryKey", equalTo("****"));
        String shown = given().when().get(hub("rg", "hub-cosmos") + API).then().statusCode(200).extract().asString();
        assertFalse(shown.contains("cHJpbWFyeQ=="), shown);

        // Sent back as read, the masked keys keep the stored ones.
        given().contentType("application/json").body(shown).when().put(hub("rg", "hub-cosmos") + API)
            .then().statusCode(201);
        // Sent back under another endpoint name, there is nothing to keep: refused, and the hub is unchanged.
        given().contentType("application/json").body(shown.replace("\"name\":\"c1\"", "\"name\":\"c2\""))
            .when().put(hub("rg", "hub-cosmos") + API).then().statusCode(400)
            .body("error.message", containsString("masked secret"));
        given().when().get(hub("rg", "hub-cosmos") + API).then()
            .body("properties.routing.endpoints.cosmosDBSqlContainers[0].name", equalTo("c1"));
    }

    @Test
    void aFreeHubHasTwoPartitionsAndRefusesMoreWithAzuresError() {
        put("rg", "hub-free", "{\"location\": \"westus2\", \"sku\": {\"name\": \"F1\", \"capacity\": 1}}")
            .then().statusCode(201)
            .body("sku.tier", equalTo("Free"))
            .body("properties.eventHubEndpoints.events.partitionCount", equalTo(2))
            .body("properties.eventHubEndpoints.events.partitionIds", contains("0", "1"));

        put("rg", "hub-free4", """
                {"location": "westus2", "sku": {"name": "F1", "capacity": 1},
                 "properties": {"eventHubEndpoints": {"events": {"retentionTimeInDays": 1, "partitionCount": 4}}}}""")
            .then().statusCode(400)
            .body("code", equalTo(400016))
            .body("httpStatusCode", equalTo(400))
            .body("message", equalTo("Invalid PartitionCount 4 - value must be between 2 and 2."));
    }

    @Test
    void basicAndStandardHubsHaveAtMostThirtyTwoPartitions() {
        for (String sku : new String[] {"S1", "B1"}) {
            put("rg", "hub-p32-" + sku.toLowerCase(), "{\"location\": \"westus2\", \"sku\": {\"name\": \"" + sku
                    + "\", \"capacity\": 1}, \"properties\": {\"eventHubEndpoints\": {\"events\": "
                    + "{\"partitionCount\": 32}}}}")
                .then().statusCode(201)
                .body("properties.eventHubEndpoints.events.partitionIds", hasSize(32));
            put("rg", "hub-p33-" + sku.toLowerCase(), "{\"location\": \"westus2\", \"sku\": {\"name\": \"" + sku
                    + "\", \"capacity\": 1}, \"properties\": {\"eventHubEndpoints\": {\"events\": "
                    + "{\"partitionCount\": 33}}}}")
                .then().statusCode(400)
                .body("code", equalTo(400016))
                .body("message", equalTo("Invalid PartitionCount 33 - value must be between 2 and 32."));
        }
    }

    @Test
    void anUnknownTlsVersionIsRefusedWithAzuresError() {
        put("rg", "hub-tls", """
                {"location": "westus2", "sku": {"name": "S1"}, "properties": {"minTlsVersion": "2.5"}}""")
            .then().statusCode(400)
            .body("code", equalTo(400089))
            .body("message", equalTo("2.5 is not a valid MinTlsVersion value."));
    }

    @Test
    void aMissingOrUnknownSkuIsRefused() {
        put("rg", "hub-nosku", "{\"location\": \"westus2\"}").then().statusCode(400)
            .body("error.code", equalTo("InvalidRequestContent"));
        put("rg", "hub-badsku", "{\"location\": \"westus2\", \"sku\": {\"name\": \"S9\"}}").then().statusCode(400)
            .body("error.code", equalTo("InvalidRequestContent"));
        put("rg", "hub-badcap", "{\"location\": \"westus2\", \"sku\": {\"name\": \"S1\", \"capacity\": 0}}")
            .then().statusCode(400);
        // The SKU name is matched ignoring case and answered in the spec's.
        put("rg", "hub-lower", "{\"location\": \"westus2\", \"sku\": {\"name\": \"s2\"}}").then().statusCode(201)
            .body("sku.name", equalTo("S2"));
    }

    @Test
    void aNewHubHasAzuresFiveDefaultPolicies() {
        put("rg", "hub-keys", CLI_BODY).then().statusCode(201);

        listKeys("rg", "hub-keys").then().statusCode(200)
            .body("value.keyName", contains("iothubowner", "service", "device", "registryRead", "registryReadWrite"))
            .body("value.rights", contains("RegistryWrite, ServiceConnect, DeviceConnect", "ServiceConnect",
                "DeviceConnect", "RegistryRead", "RegistryWrite"))
            .body("value.primaryKey", hasItem(matchesPattern(KEY_PATTERN)))
            .body("value.secondaryKey", hasItem(matchesPattern(KEY_PATTERN)))
            .body("$", not(hasKey("nextLink")));

        given().when().post(hub("rg", "hub-keys") + "/IotHubKeys/iothubowner/listkeys" + API).then().statusCode(200)
            .body("keyName", equalTo("iothubowner"))
            .body("rights", equalTo("RegistryWrite, ServiceConnect, DeviceConnect"))
            .body("primaryKey", matchesPattern(KEY_PATTERN));
        given().when().post(hub("rg", "hub-keys") + "/IotHubKeys/nosuch/listkeys" + API).then().statusCode(404);
    }

    @Test
    void theKeysNeverAppearOnTheResource() {
        put("rg", "hub-hidden", CLI_BODY).then().statusCode(201)
            .body("properties", not(hasKey("authorizationPolicies")));
        given().when().get(hub("rg", "hub-hidden") + API).then().statusCode(200)
            .body("properties", not(hasKey("authorizationPolicies")));
        given().when().get(group("rg") + API).then().statusCode(200)
            .body("value[0].properties", not(hasKey("authorizationPolicies")));
    }

    @Test
    void anUpdateThatLeavesThePoliciesOutKeepsTheirKeys() {
        put("rg", "hub-update", CLI_BODY).then().statusCode(201);
        String owner = listKeys("rg", "hub-update").then().extract().path("value[0].primaryKey");
        String etag = given().when().get(hub("rg", "hub-update") + API).then().extract().path("etag");
        String created = given().when().get(hub("rg", "hub-update") + API).then().extract().path("systemData.createdAt");
        String path = given().when().get(hub("rg", "hub-update") + API).then()
            .extract().path("properties.eventHubEndpoints.events.path");

        // The az CLI's update: the hub it read back, with the change, under If-Match. Azure answers 201.
        given().contentType("application/json").header("If-Match", etag).body("""
                {"location": "westus2", "tags": {"e": "f"}, "etag": "%s",
                 "properties": {"eventHubEndpoints": {"events": {"retentionTimeInDays": 4, "partitionCount": 4,
                                                                 "partitionIds": ["0"], "path": "ignored"}},
                                "hostName": "ignored.example", "state": "Deleting"},
                 "sku": {"name": "S1", "capacity": 1}, "identity": {"type": "None"}}""".formatted(etag))
            .when().put(hub("rg", "hub-update") + API).then().statusCode(201)
            .body("tags.e", equalTo("f"))
            .body("etag", not(equalTo(etag)))
            .body("properties.state", equalTo("Active"))
            .body("properties.hostName", equalTo("hub-update.azure-devices.net"))
            .body("properties.eventHubEndpoints.events.retentionTimeInDays", equalTo(4))
            .body("properties.eventHubEndpoints.events.partitionIds", hasSize(4))
            .body("properties.eventHubEndpoints.events.path", equalTo(path))
            // A PUT replaces the hub: what this one left out is back at Azure's default.
            .body("properties", not(hasKey("minTlsVersion")))
            .body("properties.cloudToDevice.maxDeliveryCount", equalTo(10))
            .body("systemData.createdAt", equalTo(created));

        listKeys("rg", "hub-update").then().statusCode(200)
            .body("value", hasSize(5))
            .body("value[0].primaryKey", equalTo(owner));
    }

    @Test
    void aStaleIfMatchIsRefusedAndAWildcardPasses() {
        put("rg", "hub-etag", MINIMAL_BODY).then().statusCode(201);

        given().contentType("application/json").header("If-Match", "AAAAAP8AAAA=").body(MINIMAL_BODY)
            .when().put(hub("rg", "hub-etag") + API).then().statusCode(412)
            .body("error.code", equalTo("PreconditionFailed"));
        given().contentType("application/json").header("If-Match", "*").body(MINIMAL_BODY)
            .when().put(hub("rg", "hub-etag") + API).then().statusCode(201);
        // Terraform sends no If-Match on update.
        put("rg", "hub-etag", MINIMAL_BODY).then().statusCode(201);
    }

    @Test
    void aPolicySentWithoutKeysGetsNewOnesAndOneSentWithKeysKeepsThem() {
        put("rg", "hub-policy", MINIMAL_BODY).then().statusCode(201);
        List<String> before = listKeys("rg", "hub-policy").then().extract().path("value.primaryKey");

        // az iot hub policy create: every policy back with its keys, and the new one without.
        StringBuilder policies = new StringBuilder();
        List<String> names = List.of("iothubowner", "service", "device", "registryRead", "registryReadWrite");
        List<String> rights = List.of("RegistryWrite, ServiceConnect, DeviceConnect", "ServiceConnect",
            "DeviceConnect", "RegistryRead", "RegistryWrite");
        List<String> secondaries = listKeys("rg", "hub-policy").then().extract().path("value.secondaryKey");
        for (int i = 0; i < names.size(); i++) {
            policies.append("{\"keyName\": \"").append(names.get(i)).append("\", \"primaryKey\": \"")
                .append(before.get(i)).append("\", \"secondaryKey\": \"").append(secondaries.get(i))
                .append("\", \"rights\": \"").append(rights.get(i)).append("\"}, ");
        }
        policies.append("{\"keyName\": \"test_policy\", \"rights\": \"registrywrite,serviceconnect,deviceconnect\"}");
        put("rg", "hub-policy", "{\"location\": \"westus2\", \"sku\": {\"name\": \"S1\"},"
            + " \"properties\": {\"authorizationPolicies\": [" + policies + "]}}").then().statusCode(201)
            .body("properties", not(hasKey("authorizationPolicies")));

        listKeys("rg", "hub-policy").then().statusCode(200)
            .body("value", hasSize(6))
            .body("value[0].primaryKey", equalTo(before.get(0)))
            .body("value[5].keyName", equalTo("test_policy"))
            .body("value[5].rights", equalTo("RegistryWrite, ServiceConnect, DeviceConnect"))
            .body("value[5].primaryKey", matchesPattern(KEY_PATTERN))
            .body("value[5].secondaryKey", matchesPattern(KEY_PATTERN));
    }

    @Test
    void policiesWithBadRightsOrRepeatedNamesAreRefused() {
        put("rg", "hub-badpolicy", """
                {"location": "westus2", "sku": {"name": "S1"},
                 "properties": {"authorizationPolicies": [{"keyName": "p", "rights": "Everything"}]}}""")
            .then().statusCode(400).body("error.code", equalTo("InvalidRequestContent"));
        put("rg", "hub-badpolicy", """
                {"location": "westus2", "sku": {"name": "S1"},
                 "properties": {"authorizationPolicies": [{"keyName": "p", "rights": "RegistryRead"},
                                                          {"keyName": "P", "rights": "RegistryRead"}]}}""")
            .then().statusCode(400).body("error.message", containsString("listed twice"));
    }

    @Test
    void patchUpdatesTheTagsAndNothingElse() {
        put("rg", "hub-patch", CLI_BODY).then().statusCode(201);
        String etag = given().when().get(hub("rg", "hub-patch") + API).then().extract().path("etag");

        given().contentType("application/json").body("""
                {"tags": {"env": "dev"}, "properties": {"minTlsVersion": "1.0"}}""")
            .when().patch(hub("rg", "hub-patch") + API).then().statusCode(200)
            .body("tags.env", equalTo("dev"))
            .body("properties.minTlsVersion", equalTo("1.2"))
            .body("etag", not(equalTo(etag)));
    }

    @Test
    void theLocationOfAHubCannotChange() {
        put("rg", "hub-move", MINIMAL_BODY).then().statusCode(201);
        put("rg", "hub-move", "{\"location\": \"eastus\", \"sku\": {\"name\": \"S1\"}}").then().statusCode(400)
            .body("error.code", equalTo("InvalidResourceLocation"));
    }

    @Test
    void hubsAreListedByResourceGroupAndSubscription() {
        put("rg-a", "hub-a", MINIMAL_BODY).then().statusCode(201);
        put("rg-b", "hub-b", MINIMAL_BODY).then().statusCode(201);

        given().when().get(group("rg-a") + API).then().statusCode(200)
            .body("value.name", contains("hub-a"));
        given().when().get("/subscriptions/" + SUB + "/providers/Microsoft.Devices/IotHubs" + API).then()
            .statusCode(200)
            .body("value.name", hasItem("hub-a"))
            .body("value.name", hasItem("hub-b"));
    }

    @Test
    void deletingAHubForgetsItAndADeletedHubIsNotFound() {
        put("rg", "hub-gone", MINIMAL_BODY).then().statusCode(201);

        given().when().delete(hub("rg", "hub-gone") + API).then().statusCode(200);
        given().when().get(hub("rg", "hub-gone") + API).then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
        listKeys("rg", "hub-gone").then().statusCode(404);
        given().when().delete(hub("rg", "hub-gone") + API).then().statusCode(204);
    }

    @Test
    void checkNameAvailabilityAnswersAsAzureDid() {
        String check = "/subscriptions/" + SUB + "/providers/Microsoft.Devices/checkNameAvailability" + API;
        given().contentType("application/json").body("{\"name\": \"hub-free-name\"}").when().post(check).then()
            .statusCode(200)
            .body("nameAvailable", equalTo(true))
            .body("reason", equalTo("Invalid"))
            .body("message", nullValue());

        put("rg", "hub-taken", MINIMAL_BODY).then().statusCode(201);
        given().contentType("application/json").body("{\"name\": \"hub-taken\"}").when().post(check).then()
            .statusCode(200)
            .body("nameAvailable", equalTo(false))
            .body("reason", equalTo("AlreadyExists"))
            .body("message", equalTo("IotHub name 'hub-taken' is not available"));

        given().contentType("application/json").body("{\"name\": \"-bad\"}").when().post(check).then()
            .statusCode(200)
            .body("nameAvailable", equalTo(false))
            .body("reason", equalTo("Invalid"));
    }

    @Test
    void aSecondHubWithTheSameNameIsRefused() {
        put("rg-a", "hub-dup", MINIMAL_BODY).then().statusCode(201);
        put("rg-b", "hub-dup", MINIMAL_BODY).then().statusCode(409)
            .body("error.code", equalTo("Conflict"))
            .body("error.message", equalTo("IotHub name 'hub-dup' is not available"));
    }

    @Test
    void aSystemAssignedIdentityKeepsItsPrincipalAcrossUpdates() {
        String body = """
                {"location": "westus2", "sku": {"name": "S1"}, "identity": {"type": "SystemAssigned"}}""";
        String principal = put("rg", "hub-identity", body).then().statusCode(201)
            .body("identity.type", equalTo("SystemAssigned"))
            .extract().path("identity.principalId");
        put("rg", "hub-identity", body).then().statusCode(201)
            .body("identity.principalId", equalTo(principal));
        put("rg", "hub-identity", MINIMAL_BODY).then().statusCode(201)
            .body("identity.type", equalTo("None"));
    }

    @Test
    void theEtagChangesOnEveryWrite() {
        String first = put("rg", "hub-versions", MINIMAL_BODY).then().extract().path("etag");
        String second = put("rg", "hub-versions", MINIMAL_BODY).then().extract().path("etag");
        assertNotEquals(first, second);
        assertEquals(second, given().when().get(hub("rg", "hub-versions") + API).then().extract().path("etag"));
    }
}
