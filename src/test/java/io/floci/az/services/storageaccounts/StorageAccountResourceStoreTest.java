package io.floci.az.services.storageaccounts;

import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("Storage accounts: one store keyed by canonical id, and the global name registry")
class StorageAccountResourceStoreTest {

    private static final String API = "?api-version=2023-01-01";
    private static final String BODY = "{\"location\":\"eastus\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}}";

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String account(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Storage/storageAccounts/"
            + name;
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
