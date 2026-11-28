package io.floci.az.services.apim;

import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.apim.ApiManagementModels.ApimDeletedService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code DeletedServices_ListBySubscription}, {@code _GetByName} and {@code _Purge}, and the {@code restore} flag of
 * {@code ApiManagementService_CreateOrUpdate}: a deleted service is soft-deleted for 48 hours, as azd and azurerm
 * expect when they purge it.
 */
@QuarkusTest
class ApiManagementDeletedServicesTest {

    private static final String API = "?api-version=2022-08-01";
    private static final String SUB = "test-sub-apim-deleted";
    private static final String SERVICE = "soft-apim";
    private static final String SERVICE_ID = "/subscriptions/" + SUB + "/resourceGroups/test-rg-apim-deleted"
            + "/providers/Microsoft.ApiManagement/service/" + SERVICE;
    private static final String DELETED = "/subscriptions/" + SUB
            + "/providers/Microsoft.ApiManagement/locations/westus/deletedservices/" + SERVICE;
    private static final String SERVICE_BODY = "{\"location\":\"westus\",\"sku\":{\"name\":\"Developer\",\"capacity\":1},"
            + "\"properties\":{\"publisherEmail\":\"admin@contoso.com\",\"publisherName\":\"Contoso\"}}";

    private static final String OTHER_SUBSCRIPTION = "/subscriptions/another-sub/resourceGroups/test-rg-apim-deleted"
            + "/providers/Microsoft.ApiManagement/service/" + SERVICE;

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void aDeletedServiceIsReadByNameInTheContractShape() {
        createService();
        given().when().delete(SERVICE_ID + API).then().statusCode(200);

        String deletionDate = given().when().get(DELETED + API)
                .then().statusCode(200)
                .body("id", equalTo(DELETED))
                .body("name", equalTo(SERVICE))
                .body("type", equalTo("Microsoft.ApiManagement/deletedservices"))
                .body("location", equalTo("West US"))
                .body("properties.serviceId", equalTo(SERVICE_ID))
                .extract().path("properties.deletionDate");
        String purgeDate = given().when().get(DELETED + API).then().extract().path("properties.scheduledPurgeDate");
        assertEquals(Duration.ofHours(48), Duration.between(Instant.parse(deletionDate), Instant.parse(purgeDate)));
        given().when().get(SERVICE_ID + API).then().statusCode(404);
    }

    @Test
    void theLocationOfTheGetMatchesInAnyForm() {
        createService();
        given().when().delete(SERVICE_ID + API).then().statusCode(200);

        given().when().get("/subscriptions/" + SUB + "/providers/Microsoft.ApiManagement/locations/West%20US"
                + "/deletedservices/" + SERVICE + API)
                .then().statusCode(200)
                .body("id", equalTo(DELETED));
    }

    @Test
    void theSubscriptionListsItsDeletedServices() {
        createService();
        given().when().delete(SERVICE_ID + API).then().statusCode(200);

        given().when().get("/subscriptions/" + SUB + "/providers/Microsoft.ApiManagement/deletedservices" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].properties.serviceId", equalTo(SERVICE_ID));
        given().when().get("/subscriptions/another-sub/providers/Microsoft.ApiManagement/deletedservices" + API)
                .then().statusCode(200)
                .body("value", empty());
    }

    @Test
    void aPurgeRemovesTheDeletedServiceAndASecondPurgeFindsNothing() {
        createService();
        given().when().delete(SERVICE_ID + API).then().statusCode(200);

        given().when().delete(DELETED + API).then().statusCode(200);

        given().when().get(DELETED + API).then().statusCode(404).body("error.code", equalTo("ResourceNotFound"));
        given().when().delete(DELETED + API).then().statusCode(204);
    }

    @Test
    void aRestoreBringsTheServiceBackWithItsApis() {
        createService();
        createApi();
        given().when().delete(SERVICE_ID + API).then().statusCode(200);
        given().when().get(SERVICE_ID + "/apis" + API).then().statusCode(404);

        given().contentType("application/json")
                .body("{\"location\":\"westus\",\"sku\":{\"name\":\"Developer\",\"capacity\":1},"
                        + "\"properties\":{\"restore\":true}}")
                .when().put(SERVICE_ID + API)
                .then().statusCode(200)
                .body("properties.publisherName", equalTo("Contoso"));

        given().when().get(SERVICE_ID + "/apis" + API)
                .then().statusCode(200)
                .body("value.name", hasItem("orders"));
        given().when().get(DELETED + API).then().statusCode(404);
    }

    @Test
    void aRestoreIgnoresTheOtherPropertiesItCarries() {
        createService();
        given().when().delete(SERVICE_ID + API).then().statusCode(200);

        // "If this flag is specified and set to True all other properties will be ignored", identity included.
        given().contentType("application/json")
                .body("{\"location\":\"westus\",\"identity\":{\"type\":\"NotAType\"},"
                        + "\"properties\":{\"restore\":true}}")
                .when().put(SERVICE_ID + API)
                .then().statusCode(200)
                .body("properties.publisherName", equalTo("Contoso"))
                .body("identity", nullValue());
    }

    @Test
    void aPlainCreateOverADeletedNameIsRefusedUntilItIsPurged() {
        createService();
        createApi();
        given().when().delete(SERVICE_ID + API).then().statusCode(200);

        // "You can't reuse the name of the API Management instance while the instance is soft-deleted."
        given().contentType("application/json").body(SERVICE_BODY).when().put(SERVICE_ID + API)
                .then().statusCode(409).body("error.code", equalTo("ServiceAlreadyExists"));
        given().when().get(DELETED + API).then().statusCode(200);

        given().when().delete(DELETED + API).then().statusCode(200);
        createService();
        given().when().get(SERVICE_ID + "/apis" + API).then().statusCode(200).body("value", empty());
    }

    @Test
    void aPurgeAlsoDropsTheChildren() {
        createService();
        createApi();
        given().when().delete(SERVICE_ID + API).then().statusCode(200);
        given().when().delete(DELETED + API).then().statusCode(200);

        given().contentType("application/json").body("{\"location\":\"westus\",\"properties\":{\"restore\":true}}")
                .when().put(SERVICE_ID + API)
                .then().statusCode(200);

        given().when().get(SERVICE_ID + "/apis" + API).then().statusCode(200).body("value", empty());
    }

    @Test
    void aServiceOfADeletedNameInAnotherSubscriptionIsRefusedUntilItIsPurged() {
        createService();
        createApi();
        given().when().delete(SERVICE_ID + API).then().statusCode(200);

        // The name is global, so the deleted service holds it in every subscription.
        given().contentType("application/json").body(SERVICE_BODY).when().put(OTHER_SUBSCRIPTION + API)
                .then().statusCode(409).body("error.code", equalTo("ServiceAlreadyExists"));
        given().when().get(DELETED + API).then().statusCode(200);

        given().when().delete(DELETED + API).then().statusCode(200);
        given().contentType("application/json").body(SERVICE_BODY).when().put(OTHER_SUBSCRIPTION + API)
                .then().statusCode(200);
        given().when().get(OTHER_SUBSCRIPTION + "/apis" + API).then().statusCode(200).body("value", empty());
        given().when().get("/devstoreaccount1-apim/" + SERVICE + "/orders")
                .then().statusCode(404)
                .body("error.message", containsString("No API route matched"));
    }

    @Test
    void aDeletedServicePastItsPurgeDateFreesItsName() {
        createService();
        createApi();
        given().when().delete(SERVICE_ID + API).then().statusCode(200);

        // Moves the purge date into the past, as 48 hours passing would.
        ArmResourceStore<ApimDeletedService> deleted = ArmResourceStore.create(storageFactory, "apim",
                ApiManagementModels.DELETED_SERVICE_ID_TYPE, ApimDeletedService.class, ApimDeletedService::id,
                key -> false);
        ApimDeletedService record = deleted.all().getFirst().value();
        deleted.put(new ApimDeletedService(record.id(), record.name(), record.location(), record.deletionDate(),
                Instant.now().minusSeconds(1), record.service()));

        given().contentType("application/json").body(SERVICE_BODY).when().put(OTHER_SUBSCRIPTION + API)
                .then().statusCode(200);
        given().when().get(DELETED + API).then().statusCode(404);
        given().when().get(OTHER_SUBSCRIPTION + "/apis" + API).then().statusCode(200).body("value", empty());
    }

    @Test
    void aServiceOfADeletedNameInAnotherResourceGroupIsRefusedUntilTheDeletedOneIsPurged() {
        createService();
        createApi();
        given().when().delete(SERVICE_ID + API).then().statusCode(200);

        String otherGroup = "/subscriptions/" + SUB + "/resourceGroups/test-rg-other"
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE;
        // The subscription keeps one deleted service of a name, so a second one there could not be soft-deleted
        // without overwriting the first: a create, or a restore aimed at the other group, is refused.
        for (String body : new String[] {SERVICE_BODY, "{\"location\":\"westus\",\"properties\":{\"restore\":true}}"}) {
            given().contentType("application/json").body(body).when().put(otherGroup + API)
                    .then().statusCode(409).body("error.code", equalTo("ServiceAlreadyExists"));
        }
        given().when().get(DELETED + API).then().statusCode(200).body("properties.serviceId", equalTo(SERVICE_ID));

        given().when().delete(DELETED + API).then().statusCode(200);
        given().contentType("application/json").body(SERVICE_BODY).when().put(otherGroup + API).then().statusCode(200);
        given().when().get(otherGroup + "/apis" + API).then().statusCode(200).body("value", empty());
    }

    private static void createService() {
        given().contentType("application/json").body(SERVICE_BODY)
                .when().put(SERVICE_ID + API)
                .then().statusCode(200);
    }

    private static void createApi() {
        given().contentType("application/json").body("{\"properties\":{\"path\":\"orders\",\"displayName\":\"Orders\"}}")
                .when().put(SERVICE_ID + "/apis/orders" + API)
                .then().statusCode(200);
    }
}
