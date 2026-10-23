package io.floci.az.services.managedidentity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.managedidentity.ManagedIdentityModels.UserAssignedIdentity;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("Managed Identity: identities and credentials in one store keyed by canonical id")
class ManagedIdentityResourceStoreTest {

    private static final String API = "?api-version=2024-11-30";
    private static final String IMDS = "/metadata/identity/oauth2/token";

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String identity(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg
            + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities/" + name;
    }

    private static String imdsByClientId(String clientId) {
        return IMDS + "?resource=https://management.azure.com/&client_id=" + clientId + "&api-version=2018-02-01";
    }

    @Test
    void aPutThroughOtherCasingUpdatesTheOneIdentity() {
        String clientId = given().contentType("application/json").body("{\"location\":\"westus\"}")
            .when().put(identity("sub-case", "RG-Case", "Identity1") + API)
            .then().statusCode(201).extract().path("properties.clientId");

        given().contentType("application/json").body("{\"location\":\"westus\",\"tags\":{\"env\":\"t\"}}")
            .when().put(identity("sub-case", "rg-case", "identity1") + API)
            .then().statusCode(200)
            .body("id", equalTo(identity("sub-case", "RG-Case", "Identity1")))
            .body("name", equalTo("Identity1"))
            .body("tags.env", equalTo("t"))
            .body("properties.clientId", equalTo(clientId));

        given().when().get("/subscriptions/sub-case/resourceGroups/rg-case/providers/Microsoft.ManagedIdentity"
                + "/userAssignedIdentities" + API)
            .then().statusCode(200).body("value", hasSize(1));
        given().when().get("/subscriptions/sub-case/resourceGroups/rg-case/resources?api-version=2021-04-01")
            .then().statusCode(200).body("value.findAll { it.name == 'Identity1' }", hasSize(1));
    }

    @Test
    void anIdentityLoadedFromTheStoreIsServedListedAndSelectableForTokens() throws Exception {
        String id = identity("Sub-Stored", "RG-Stored", "stored-identity");
        UserAssignedIdentity stored = new UserAssignedIdentity(id, "stored-identity", "eastus", Map.of(),
            "00000000-0000-0000-0000-000000000001", "aaaaaaaa-0000-0000-0000-000000000002",
            "bbbbbbbb-0000-0000-0000-000000000003");
        StorageBackend<String, StoredObject> backend = storageFactory.create("managedidentity");
        backend.put(id.toLowerCase(), new StoredObject(id.toLowerCase(),
            new ObjectMapper().writeValueAsBytes(stored), Map.of(), Instant.now(), "etag"));

        given().when().get(identity("sub-stored", "rg-stored", "STORED-IDENTITY") + API).then().statusCode(200)
            .body("id", equalTo(id))
            .body("properties.clientId", equalTo("bbbbbbbb-0000-0000-0000-000000000003"));
        given().when().get("/subscriptions/sub-stored/providers/Microsoft.ManagedIdentity/userAssignedIdentities" + API)
            .then().statusCode(200).body("value", hasSize(1));

        given().header("Metadata", "true").when().get(imdsByClientId("BBBBBBBB-0000-0000-0000-000000000003"))
            .then().statusCode(200).body("client_id", equalTo("bbbbbbbb-0000-0000-0000-000000000003"));
        given().header("Metadata", "true")
            .when().get(IMDS + "?resource=https://management.azure.com/&object_id=aaaaaaaa-0000-0000-0000-000000000002"
                + "&api-version=2018-02-01")
            .then().statusCode(200).body("client_id", equalTo("bbbbbbbb-0000-0000-0000-000000000003"));
        given().header("Metadata", "true")
            .when().get(IMDS + "?resource=https://management.azure.com/&msi_res_id=" + id.toUpperCase()
                + "&api-version=2018-02-01")
            .then().statusCode(200).body("client_id", equalTo("bbbbbbbb-0000-0000-0000-000000000003"));
    }

    @Test
    void aDeletedIdentityIsNoLongerSelectableAndTakesItsCredentialsWithIt() {
        String clientId = given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(identity("sub-del", "rg-del", "gone") + API)
            .then().statusCode(201).extract().path("properties.clientId");
        given().header("Metadata", "true").when().get(imdsByClientId(clientId)).then().statusCode(200);
        given().contentType("application/json")
            .body("{\"properties\":{\"issuer\":\"https://issuer\",\"subject\":\"s\",\"audiences\":[\"a\"]}}")
            .when().put(identity("sub-del", "rg-del", "gone") + "/federatedIdentityCredentials/Fic1" + API)
            .then().statusCode(201);
        given().contentType("application/json")
            .body("{\"properties\":{\"issuer\":\"https://issuer\",\"subject\":\"s2\",\"audiences\":[\"a\"]}}")
            .when().put(identity("SUB-DEL", "RG-DEL", "GONE") + "/federatedIdentityCredentials/fic1" + API)
            .then().statusCode(200)
            .body("name", equalTo("Fic1"))
            .body("properties.subject", equalTo("s2"));

        given().when().delete(identity("sub-del", "rg-del", "GONE") + API).then().statusCode(200);
        given().header("Metadata", "true").when().get(imdsByClientId(clientId)).then().statusCode(400)
            .body("error_description", equalTo("Identity not found"));

        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(identity("sub-del", "rg-del", "gone") + API).then().statusCode(201);
        given().when().get(identity("sub-del", "rg-del", "gone") + "/federatedIdentityCredentials" + API)
            .then().statusCode(200).body("value", hasSize(0));
        assertTrue(storageFactory.create("managedidentity").keys().stream()
            .noneMatch(key -> key.contains("/federatedidentitycredentials/")), "the credential went with its identity");
    }
}
