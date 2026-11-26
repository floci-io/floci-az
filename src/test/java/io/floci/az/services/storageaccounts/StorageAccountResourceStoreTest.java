package io.floci.az.services.storageaccounts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.core.StoredObject;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.cloud.CloudSuffix;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.blob.BlobServiceHandler;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("Storage accounts: one store keyed by canonical id, and the global name registry")
class StorageAccountResourceStoreTest {

    private static final String API = "?api-version=2023-01-01";
    private static final String BODY = "{\"location\":\"eastus\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}}";

    @Inject
    StorageFactory storageFactory;

    @Inject
    StorageAccountArmService storageAccountArmService;

    @Inject
    BlobServiceHandler blobHandler;

    @Inject
    ObjectMapper mapper;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String account(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Storage/storageAccounts/"
            + name;
    }

    @Test
    void restoresAccountAccessTierToBlobDataPlane() {
        String path = account("sub-tier", "rg", "restoredsa");
        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{\"accessTier\":\"Cool\"}}")
            .put(path + API).then().statusCode(200);
        given().put("/restoredsa/container?restype=container").then().statusCode(201);
        given().header("x-ms-blob-type", "BlockBlob").body("data")
            .put("/restoredsa/container/blob").then().statusCode(201);

        blobHandler.removeAccountAccessTier("restoredsa");
        given().head("/restoredsa/container/blob").then().header("x-ms-access-tier", "Hot");
        storageAccountArmService.restoreHierarchicalNamespaces(null);
        given().head("/restoredsa/container/blob").then().header("x-ms-access-tier", "Cool")
            .header("x-ms-access-tier-inferred", "true");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"invalid\"", "\"\"", "42", "{}"})
    void putRejectsInvalidAccountAccessTiersWithoutChangingTheAccount(String invalidTier) {
        String path = account("sub-tier", "rg", "validsa") + API;
        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{\"accessTier\":\"Cool\"}}")
            .put(path).then().statusCode(200);
        given().put("/validsa/container?restype=container").then().statusCode(201);
        given().header("x-ms-blob-type", "BlockBlob").body("data")
            .put("/validsa/container/blob").then().statusCode(201);

        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{\"accessTier\":" + invalidTier + "}}")
            .put(path).then().statusCode(400).body("error.code", equalTo("InvalidValuesForRequestParameters"));
        given().get(path).then().body("properties.accessTier", equalTo("Cool"));
        given().head("/validsa/container/blob").then().header("x-ms-access-tier", "Cool");

        String newPath = account("sub-tier", "rg", "invalidsa") + API;
        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{\"accessTier\":" + invalidTier + "}}")
            .put(newPath).then().statusCode(400).body("error.code", equalTo("InvalidValuesForRequestParameters"));
        given().get(newPath).then().statusCode(404);
    }

    @Test
    void invalidPersistedAccountTierIsNotExposedToBlobReads() throws Exception {
        String accountPath = account("sub-tier", "rg", "legacysa");
        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{\"accessTier\":\"Cool\"}}")
            .put(accountPath + API).then().statusCode(200);
        given().put("/legacysa/container?restype=container").then().statusCode(201);
        given().header("x-ms-blob-type", "BlockBlob").body("data")
            .put("/legacysa/container/blob").then().statusCode(201);

        String key = ArmResourceId.parse(accountPath).orElseThrow().canonicalKey();
        StorageBackend<String, StoredObject> backend = storageFactory.create(StorageAccountArmService.STORE);
        StoredObject stored = backend.get(key).orElseThrow();
        ObjectNode document = (ObjectNode) mapper.readTree(stored.data());
        ((ObjectNode) document.get("properties")).put("accessTier", "invalid");
        backend.put(key, new StoredObject(stored.key(), mapper.writeValueAsBytes(document),
            stored.metadata(), stored.lastModified(), stored.etag()));

        storageAccountArmService.restoreHierarchicalNamespaces(null);
        given().head("/legacysa/container/blob").then().header("x-ms-access-tier", "Hot")
            .header("x-ms-access-tier-inferred", "true");
        given().contentType("application/json").body("{\"properties\":{\"accessTier\":\"Cold\"}}")
            .patch(accountPath + API).then().statusCode(200);
        given().head("/legacysa/container/blob").then().header("x-ms-access-tier", "Cold");
    }

    @Test
    void aGetReturnsWhatThePutSet() {
        String body = "{\"location\":\"westeurope\",\"kind\":\"BlockBlobStorage\",\"sku\":{\"name\":\"Premium_ZRS\"},"
            + "\"properties\":{\"minimumTlsVersion\":\"TLS1_2\",\"allowSharedKeyAccess\":false,"
            + "\"encryption\":{\"services\":{\"queue\":{\"keyType\":\"Service\"}}}}}";
        given().contentType("application/json").body(body).when().put(account("sub-echo", "rg", "echosa") + API)
            .then().statusCode(200);

        given().when().get(account("sub-echo", "rg", "echosa") + API)
            .then().statusCode(200)
            .body("sku.name", equalTo("Premium_ZRS"))
            .body("sku.tier", equalTo("Premium"))
            .body("kind", equalTo("BlockBlobStorage"))
            .body("properties.accessTier", nullValue())
            .body("properties.minimumTlsVersion", equalTo("TLS1_2"))
            .body("properties.allowSharedKeyAccess", equalTo(false))
            .body("properties.encryption.services.queue.keyType", equalTo("Service"))
            .body("properties.encryption.services.blob.keyType", equalTo("Account"));
    }

    @Test
    void aPutCannotSetReadOnlyPropertiesOrNulls() {
        String body = "{\"location\":\"westeurope\",\"properties\":{\"creationTime\":\"2020-01-01T00:00:00Z\","
            + "\"secondaryEndpoints\":{\"blob\":\"http://x/\"},"
            + "\"storageAccountSkuConversionStatus\":{\"skuConversionStatus\":\"InProgress\"},"
            + "\"minimumTlsVersion\":null,\"accessTier\":null,"
            + "\"encryption\":{\"services\":{\"blob\":{\"lastEnabledTime\":\"2020-01-01T00:00:00Z\",\"keyType\":null}}}}}";
        given().contentType("application/json").body(body).when().put(account("sub-ro", "rg", "readonlysa") + API)
            .then().statusCode(200);

        given().when().get(account("sub-ro", "rg", "readonlysa") + API)
            .then().statusCode(200)
            .body("properties", not(hasKey("creationTime")))
            .body("properties", not(hasKey("secondaryEndpoints")))
            .body("properties", not(hasKey("storageAccountSkuConversionStatus")))
            .body("properties", not(hasKey("minimumTlsVersion")))
            .body("properties.accessTier", equalTo("Hot"))
            .body("properties.encryption.services.blob", not(hasKey("lastEnabledTime")))
            .body("properties.encryption.services.blob.keyType", equalTo("Account"));
    }

    @Test
    void holdsAnswersFromTheStoredAccountAlone() {
        given().contentType("application/json").body(BODY).when().put(account("sub-holds", "rg", "heldsa") + API)
            .then().statusCode(200);
        ArmResourceId owner = ArmResourceId.parse(account("sub-holds", "rg", "heldsa")).orElseThrow();

        assertTrue(storageAccountArmService.holds(CloudSuffix.BLOB, "HELDSA", owner));
        assertFalse(storageAccountArmService.holds(CloudSuffix.WEB, "heldsa", owner));
        assertFalse(storageAccountArmService.holds(CloudSuffix.BLOB, "othersa", owner));
        assertFalse(storageAccountArmService.holds(CloudSuffix.BLOB, "heldsa",
            ArmResourceId.parse(account("sub-elsewhere", "rg", "heldsa")).orElseThrow()));

        given().when().delete(account("sub-holds", "rg", "heldsa") + API).then().statusCode(200);
        assertFalse(storageAccountArmService.holds(CloudSuffix.BLOB, "heldsa", owner));
    }

    @Test
    void aPutThroughOtherCasingUpdatesTheOneAccount() {
        given().contentType("application/json").body(BODY).when().put(account("sub-case", "RG-Case", "casesa") + API)
            .then().statusCode(200);
        given().contentType("application/json").body("{\"location\":\"eastus\",\"tags\":{\"env\":\"t\"}}")
            .when().put(account("SUB-CASE", "rg-case", "casesa") + API)
            .then().statusCode(200)
            .body("id", equalTo(account("sub-case", "RG-Case", "casesa")))
            .body("tags.env", equalTo("t"));

        given().when().get("/subscriptions/sub-case/resourceGroups/rg-case/providers/Microsoft.Storage/storageAccounts"
                + API)
            .then().statusCode(200).body("value", hasSize(1));
        given().when().get("/subscriptions/sub-case/providers/Microsoft.Storage/storageAccounts" + API)
            .then().statusCode(200).body("value", hasSize(1));
        given().when().get("/subscriptions/sub-case/resourceGroups/rg-case/resources" + API)
            .then().statusCode(200).body("value.id", hasItem(account("sub-case", "RG-Case", "casesa")));
    }

    @Test
    void anAccountIsWrittenUnderItsCanonicalKey() {
        given().contentType("application/json").body(BODY).when().put(account("Sub-Key", "RG-Key", "keysa") + API)
            .then().statusCode(200);
        StorageBackend<String, StoredObject> backend = storageFactory.create(StorageAccountArmService.STORE);
        assertTrue(backend.keys().contains(account("sub-key", "rg-key", "keysa").toLowerCase()), "keys "
            + backend.keys());
    }

    @Test
    void checkNameAvailabilityAnswersFromTheRegistry() {
        String check = "/subscriptions/sub-check/providers/Microsoft.Storage/checkNameAvailability" + API;
        String request = "{\"name\":\"checksa\",\"type\":\"Microsoft.Storage/storageAccounts\"}";

        given().contentType("application/json").body(request).when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(true));
        given().contentType("application/json").body(BODY).when().put(account("sub-owner", "rg", "checksa") + API)
            .then().statusCode(200);
        given().contentType("application/json").body(request).when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(false))
            .body("reason", equalTo("AlreadyExists"))
            .body("message", equalTo("The storage account named checksa is already taken."));
        given().when().delete(account("sub-owner", "rg", "checksa") + API).then().statusCode(200);
        given().contentType("application/json").body(request).when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(true));
    }

    @Test
    void aNameTakenInAnotherSubscriptionIsRefusedUntilItsOwnerDeletesIt() {
        given().contentType("application/json").body(BODY).when().put(account("sub-a", "rg", "takensa") + API)
            .then().statusCode(200);
        given().contentType("application/json").body(BODY).when().put(account("sub-b", "rg", "takensa") + API)
            .then().statusCode(409)
            .body("error.code", equalTo("StorageAccountAlreadyTaken"))
            .body("error.message", equalTo("The storage account named takensa is already taken."));
        given().contentType("application/json").body(BODY).when().put(account("sub-a", "rg-two", "takensa") + API)
            .then().statusCode(409)
            .body("error.code", equalTo("StorageAccountInAnotherResourceGroup"))
            .body("error.message",
                equalTo("The account takensa is already in another resource group in this subscription."));

        given().when().delete(account("sub-a", "rg", "takensa") + API).then().statusCode(200);
        given().contentType("application/json").body(BODY).when().put(account("sub-b", "rg", "takensa") + API)
            .then().statusCode(200);
    }

    @Test
    void anInvalidNameIsRefusedBeforeAConflictWithoutStrictNames() {
        given().contentType("application/json").body(BODY).when().put(account("sub-inv", "rg", "abc") + API)
            .then().statusCode(200);
        given().contentType("application/json").body(BODY).when().put(account("sub-other", "rg", "ABC") + API)
            .then().statusCode(400)
            .body("error.code", equalTo("AccountNameInvalid"));
    }

    @Test
    void aChildPathNeverCreatesAnAccountOrTakesItsName() {
        String check = "/subscriptions/sub-child/providers/Microsoft.Storage/checkNameAvailability" + API;
        String request = "{\"name\":\"filesa\",\"type\":\"Microsoft.Storage/storageAccounts\"}";

        given().contentType("application/json").body(BODY)
            .when().put(account("sub-child", "rg", "filesa") + "/fileServices/default" + API)
            .then().statusCode(200)
            .body("type", equalTo("Microsoft.Storage/storageAccounts/fileServices"));
        given().contentType("application/json").body(BODY)
            .when().put(account("sub-child", "rg", "filesa") + "/tableServices/default" + API)
            .then().statusCode(404);
        given().contentType("application/json").body(BODY)
            .when().put(account("sub-child", "rg", "filesa") + "/fileServices/default/shares/share1" + API)
            .then().statusCode(404);

        given().when().get(account("sub-child", "rg", "filesa") + API).then().statusCode(404);
        given().when().get("/subscriptions/sub-child/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts" + API)
            .then().statusCode(200).body("value", hasSize(0));
        given().contentType("application/json").body(request).when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(true));
        given().contentType("application/json").body(BODY).when().put(account("sub-elsewhere", "rg", "filesa") + API)
            .then().statusCode(200);
    }

    @Test
    void anInvalidNameThroughAChildPathCreatesNothing() {
        given().contentType("application/json").body(BODY)
            .when().put(account("sub-badchild", "rg", "Bad-Name") + "/tableServices/default" + API)
            .then().statusCode(404);
        given().contentType("application/json").body(BODY)
            .when().put(account("sub-badchild", "rg", "Bad-Name") + "/fileServices/default" + API)
            .then().statusCode(200);
        given().when().get("/subscriptions/sub-badchild/providers/Microsoft.Storage/storageAccounts" + API)
            .then().statusCode(200).body("value", hasSize(0));
        given().contentType("application/json").body(BODY).when().put(account("sub-badchild", "rg", "Bad-Name") + API)
            .then().statusCode(400)
            .body("error.code", equalTo("AccountNameInvalid"));
    }
}
