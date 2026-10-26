package io.floci.az.services.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.email.EmailModels.CommunicationResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("Email: Communication resources in the email backend, keyed by canonical id, with one owner per name")
class EmailResourceStoreTest {

    private static final String API = "?api-version=2023-03-31";
    private static final String BODY = "{\"location\":\"global\",\"properties\":{\"dataLocation\":\"United States\"}}";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String provider(String sub, String rg) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Communication";
    }

    @Test
    void aPutThroughOtherCasingUpdatesTheOneCommunicationService() {
        String first = provider("sub-case", "RG-Case") + "/communicationServices/case-comm";
        String immutableId = given().contentType("application/json").body(BODY).when().put(first + API)
            .then().statusCode(201).extract().path("properties.immutableResourceId");

        given().contentType("application/json").body("{\"location\":\"global\",\"tags\":{\"k\":\"v\"}}")
            .when().put(provider("SUB-CASE", "rg-case") + "/communicationServices/CASE-COMM" + API)
            .then().statusCode(201)
            .body("id", equalTo(first))
            .body("name", equalTo("case-comm"))
            .body("tags.k", equalTo("v"))
            .body("properties.immutableResourceId", equalTo(immutableId));

        given().when().get(provider("sub-case", "rg-case") + "/communicationServices" + API)
            .then().statusCode(200).body("value", hasSize(1));
    }

    @Test
    void aPutThroughOtherCasingUpdatesTheOneEmailServiceAndDomain() {
        String service = provider("sub-case", "RG-Mail") + "/emailServices/case-mail";
        given().contentType("application/json").body(BODY).when().put(service + API).then().statusCode(201);
        given().contentType("application/json").body(BODY)
            .when().put(provider("sub-case", "rg-mail") + "/emailServices/Case-Mail" + API)
            .then().statusCode(201).body("id", equalTo(service));
        given().contentType("application/json").body("{}")
            .when().put(service + "/domains/AzureManagedDomain" + API).then().statusCode(201);
        given().contentType("application/json").body("{}")
            .when().put(provider("sub-case", "rg-mail") + "/emailServices/case-mail/domains/azuremanageddomain" + API)
            .then().statusCode(201).body("id", equalTo(service + "/domains/AzureManagedDomain"));

        given().when().get(provider("sub-case", "rg-mail") + "/emailServices" + API)
            .then().statusCode(200).body("value", hasSize(1));
    }

    @Test
    void resourcesAreStoredUnderCanonicalKeysBesideTheCapturedEmails() {
        String service = provider("sub-store", "rg-store") + "/communicationServices/store-comm";
        given().contentType("application/json").body(BODY).when().put(service + API).then().statusCode(201);
        given().contentType("application/json")
            .body("{\"senderAddress\":\"a@b.c\",\"content\":{\"subject\":\"s\"},"
                + "\"recipients\":{\"to\":[{\"address\":\"d@e.f\"}]}}")
            .when().post("/emails:send" + API).then().statusCode(202);

        StorageBackend<String, StoredObject> backend = storageFactory.create("email");
        assertTrue(backend.keys().contains(service.toLowerCase()));

        given().when().get("/emailMessages").then().statusCode(200)
            .body("count", equalTo(1));
        given().when().delete("/emailMessages").then().statusCode(200);
        given().when().get("/emailMessages").then().statusCode(200)
            .body("count", equalTo(0));
        given().when().get(service + API).then().statusCode(200).body("name", equalTo("store-comm"));
    }

    @Test
    void anOperationIdShapedLikeAnArmIdNeverOverwritesAResource() {
        String service = provider("sub-op", "rg-op") + "/communicationServices/op-comm";
        given().contentType("application/json").body(BODY).when().put(service + API).then().statusCode(201);

        String operationId = given().contentType("application/json").header("Operation-Id", service.toLowerCase())
            .body("{\"senderAddress\":\"a@b.c\",\"content\":{\"subject\":\"s\"},"
                + "\"recipients\":{\"to\":[{\"address\":\"d@e.f\"}]}}")
            .when().post("/emails:send" + API).then().statusCode(202)
            .extract().path("id");

        assertFalse(operationId.startsWith("/"));
        given().when().get(service + API).then().statusCode(200).body("name", equalTo("op-comm"));
    }

    @Test
    void aGlobalNameIsTakenAcrossSubscriptionsAndFreedByItsDelete() {
        String owner = provider("sub-owner", "rg") + "/communicationServices/taken-comm";
        String other = provider("sub-other", "rg") + "/communicationServices/taken-comm";
        given().contentType("application/json").body(BODY).when().put(owner + API).then().statusCode(201);

        given().contentType("application/json").body(BODY).when().put(other + API).then().statusCode(409)
            .body("error.code", equalTo("Conflict"))
            .body("error.message", equalTo("The communication service name 'taken-comm' is already in use."));
        given().when().get(other + API).then().statusCode(404);

        given().when().delete(owner + API).then().statusCode(200);
        given().contentType("application/json").body(BODY).when().put(other + API).then().statusCode(201);
    }

    @Test
    void aStoredServiceKeepsItsNameAfterTheRegistryRebuilds() throws Exception {
        String owner = provider("sub-disk", "rg") + "/communicationServices/disk-comm";
        CommunicationResource stored = new CommunicationResource(owner, "disk-comm",
            "Microsoft.Communication/communicationServices", "global", null,
            Map.of("provisioningState", "Succeeded", "hostName", "disk-comm.communication.azure.com"));
        storageFactory.create("email").put(owner.toLowerCase(), new StoredObject(owner.toLowerCase(),
            MAPPER.writeValueAsBytes(stored), Map.of(), Instant.now(), "etag"));

        given().contentType("application/json").body(BODY)
            .when().put(provider("sub-other", "rg") + "/communicationServices/disk-comm" + API)
            .then().statusCode(409).body("error.code", equalTo("Conflict"));
        String id = given().when().get(owner + API).then().statusCode(200).extract().path("id");
        assertEquals(owner, id);
    }

    @Test
    void aPathWithoutAResourceGroupAddressesNoResource() {
        given().contentType("application/json").body(BODY)
            .when().put("/subscriptions/sub-norg/providers/Microsoft.Communication/communicationServices/norg" + API)
            .then().statusCode(404);
        given().contentType("application/json").body(BODY)
            .when().put("/subscriptions/sub-norg/providers/Microsoft.Communication/emailServices/norg" + API)
            .then().statusCode(404);
    }

    @Test
    void checkNameAvailabilityAnswersFromTheRegistryInEverySubscription() {
        String request = "{\"name\":\"check-comm\",\"type\":\"Microsoft.Communication/CommunicationServices\"}";
        String inOwner = "/subscriptions/sub-check-a/providers/Microsoft.Communication/checkNameAvailability" + API;
        String inOther = "/subscriptions/sub-check-b/providers/microsoft.communication/checknameavailability" + API;
        String owner = provider("sub-check-a", "rg") + "/communicationServices/check-comm";

        given().contentType("application/json").body(request).when().post(inOther).then().statusCode(200)
            .body("nameAvailable", equalTo(true)).body("reason", equalTo("NameAvailable"));
        given().contentType("application/json").body(BODY).when().put(owner + API).then().statusCode(201);
        for (String check : new String[] {inOwner, inOther}) {
            given().contentType("application/json").body(request).when().post(check).then().statusCode(200)
                .body("nameAvailable", equalTo(false)).body("reason", equalTo("AlreadyExists"))
                .body("message", equalTo("Requested name is unavailable for the requested type"));
        }
        given().when().delete(owner + API).then().statusCode(lessThan(300));
        given().contentType("application/json").body(request).when().post(inOther).then().statusCode(200)
            .body("nameAvailable", equalTo(true));
    }
}
