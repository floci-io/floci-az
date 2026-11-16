package io.floci.az.services.storageaccounts;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@DisplayName("Storage accounts: the management policy and the blob service properties are kept per account")
class StorageAccountChildPropertiesTest {

    private static final String API = "?api-version=2025-08-01";
    private static final String ACCOUNT_BODY =
        "{\"location\":\"eastus\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}}";
    /** What azurerm_storage_management_policy sends for one lifecycle rule. */
    private static final String POLICY_BODY = "{\"name\":\"default\",\"properties\":{\"policy\":{\"rules\":[{"
        + "\"name\":\"expire-logs\",\"enabled\":true,\"type\":\"Lifecycle\",\"definition\":{"
        + "\"filters\":{\"blobTypes\":[\"blockBlob\"],\"prefixMatch\":[\"logs/\"]},"
        + "\"actions\":{\"baseBlob\":{\"delete\":{\"daysAfterModificationGreaterThan\":30}}}}}]}}}";
    /** What azurerm_storage_account sends for a blob_properties block. */
    private static final String BLOB_SERVICE_BODY = "{\"properties\":{"
        + "\"cors\":{\"corsRules\":[{\"allowedHeaders\":[\"*\"],\"allowedOrigins\":[\"https://example.com\"],"
        + "\"allowedMethods\":[\"GET\",\"PUT\"],\"exposedHeaders\":[\"*\"],\"maxAgeInSeconds\":3600}]},"
        + "\"deleteRetentionPolicy\":{\"enabled\":true,\"allowPermanentDelete\":false,\"days\":7},"
        + "\"containerDeleteRetentionPolicy\":{\"enabled\":true,\"days\":7},"
        + "\"lastAccessTimeTrackingPolicy\":{\"enable\":false},"
        + "\"changeFeed\":{\"enabled\":false},"
        + "\"isVersioningEnabled\":true,"
        + "\"restorePolicy\":{\"enabled\":false}}}";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String account(String name) {
        return "/subscriptions/sub-props/resourceGroups/rg-props/providers/Microsoft.Storage/storageAccounts/" + name;
    }

    private static void createAccount(String name) {
        given().contentType("application/json").body(ACCOUNT_BODY).when().put(account(name) + API)
            .then().statusCode(200);
    }

    @Test
    void aManagementPolicyIsKeptAsSentUntilItIsDeleted() {
        createAccount("policysa");
        String policy = account("policysa") + "/managementPolicies/default" + API;

        given().when().get(policy).then().statusCode(404).body("error.code", equalTo("ResourceNotFound"));

        given().contentType("application/json").body(POLICY_BODY).when().put(policy)
            .then().statusCode(200)
            .body("name", equalTo("default"))
            .body("type", equalTo("Microsoft.Storage/storageAccounts/managementPolicies"))
            .body("properties.lastModifiedTime", notNullValue())
            .body("properties.policy.rules[0].name", equalTo("expire-logs"));

        given().when().get(policy)
            .then().statusCode(200)
            .body("properties.policy.rules", hasSize(1))
            .body("properties.policy.rules[0].enabled", equalTo(true))
            .body("properties.policy.rules[0].type", equalTo("Lifecycle"))
            .body("properties.policy.rules[0].definition.filters.blobTypes[0]", equalTo("blockBlob"))
            .body("properties.policy.rules[0].definition.filters.prefixMatch[0]", equalTo("logs/"))
            .body("properties.policy.rules[0].definition.actions.baseBlob.delete.daysAfterModificationGreaterThan",
                equalTo(30));

        given().when().delete(policy).then().statusCode(200);
        given().when().get(policy).then().statusCode(404);
        given().when().delete(policy).then().statusCode(204);
    }

    @Test
    void aManagementPolicyForAMissingAccountIsRefusedAndCreatesNoAccount() {
        given().contentType("application/json").body(POLICY_BODY)
            .when().put(account("nosuchsa") + "/managementPolicies/default" + API)
            .then().statusCode(404);

        given().when().get(account("nosuchsa") + API).then().statusCode(404);
    }

    @Test
    void blobServicePropertiesAreKeptAndAPutReplacesThem() {
        createAccount("blobpropsa");
        String service = account("blobpropsa") + "/blobServices/default" + API;

        given().when().get(service)
            .then().statusCode(200)
            .body("type", equalTo("Microsoft.Storage/storageAccounts/blobServices"))
            .body("properties", equalTo(Map.of()));

        given().contentType("application/json").body(BLOB_SERVICE_BODY).when().put(service)
            .then().statusCode(200)
            .body("name", equalTo("default"))
            .body("properties.isVersioningEnabled", equalTo(true));

        given().when().get(service)
            .then().statusCode(200)
            .body("properties.isVersioningEnabled", equalTo(true))
            .body("properties.deleteRetentionPolicy.enabled", equalTo(true))
            .body("properties.deleteRetentionPolicy.days", equalTo(7))
            .body("properties.containerDeleteRetentionPolicy.days", equalTo(7))
            .body("properties.cors.corsRules[0].allowedMethods", equalTo(List.of("GET", "PUT")))
            .body("properties.cors.corsRules[0].maxAgeInSeconds", equalTo(3600));

        given().contentType("application/json").body("{\"properties\":{\"isVersioningEnabled\":false}}")
            .when().put(service).then().statusCode(200);
        given().when().get(service)
            .then().statusCode(200)
            .body("properties.isVersioningEnabled", equalTo(false))
            .body("properties", not(hasKey("deleteRetentionPolicy")));
    }

    @Test
    void aMalformedOrMisshapenWriteIsRefusedAndKeepsWhatWasSet() {
        createAccount("keepsa");
        String policy = account("keepsa") + "/managementPolicies/default" + API;
        String service = account("keepsa") + "/blobServices/default" + API;
        given().contentType("application/json").body(POLICY_BODY).when().put(policy).then().statusCode(200);
        given().contentType("application/json").body(BLOB_SERVICE_BODY).when().put(service).then().statusCode(200);

        for (String body : List.of("{ not json", "{\"properties\":{\"policy\":\"rules\"}}", "{\"properties\":{}}")) {
            given().contentType("application/json").body(body).when().put(policy)
                .then().statusCode(400).body("error.code", equalTo("InvalidRequestContent"));
        }
        for (String body : List.of("{ not json", "{\"properties\":[]}")) {
            given().contentType("application/json").body(body).when().put(service)
                .then().statusCode(400).body("error.code", equalTo("InvalidRequestContent"));
        }

        given().when().get(policy).then().statusCode(200).body("properties.policy.rules[0].name", equalTo("expire-logs"));
        given().when().get(service).then().statusCode(200).body("properties.isVersioningEnabled", equalTo(true));
    }

    @Test
    void deletingTheAccountTakesItsPolicyAndBlobServiceWithIt() {
        createAccount("gonesa");
        given().contentType("application/json").body(POLICY_BODY)
            .when().put(account("gonesa") + "/managementPolicies/default" + API).then().statusCode(200);
        given().contentType("application/json").body(BLOB_SERVICE_BODY)
            .when().put(account("gonesa") + "/blobServices/default" + API).then().statusCode(200);

        given().when().delete(account("gonesa") + API).then().statusCode(200);
        createAccount("gonesa");

        given().when().get(account("gonesa") + "/managementPolicies/default" + API).then().statusCode(404);
        given().when().get(account("gonesa") + "/blobServices/default" + API)
            .then().statusCode(200).body("properties", not(hasKey("isVersioningEnabled")));
    }

    @Test
    void theAccountListingsHoldOnlyAccounts() {
        createAccount("listsa");
        given().contentType("application/json").body(POLICY_BODY)
            .when().put(account("listsa") + "/managementPolicies/default" + API).then().statusCode(200);
        given().contentType("application/json").body(BLOB_SERVICE_BODY)
            .when().put(account("listsa") + "/blobServices/default" + API).then().statusCode(200);

        given().when().get("/subscriptions/sub-props/resourceGroups/rg-props/providers/Microsoft.Storage/storageAccounts"
                + API)
            .then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].type", equalTo("Microsoft.Storage/storageAccounts"));
        given().when().get("/subscriptions/sub-props/providers/Microsoft.Storage/storageAccounts" + API)
            .then().statusCode(200).body("value", hasSize(1));
    }
}
