package io.floci.az.core;

import io.floci.az.services.entra.EntraModels.User;
import io.floci.az.services.entra.EntraStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
@DisplayName("/_admin/reset — every state-holding service self-registers via Resettable")
class AdminResetTest {

    private static final String APIM_SERVICE =
            "/subscriptions/reset-sub/resourceGroups/reset-rg"
                    + "/providers/Microsoft.ApiManagement/service/reset-apim?api-version=2024-05-01";

    @Inject EntraStore entraStore;

    @Test
    void resetClearsGraphMembershipWrittenThroughEntraStore() {
        String userId = "reset-user-" + java.util.UUID.randomUUID();
        entraStore.putUser(new User(userId, userId + "@floci-az.local", "reset test user", null, "reset-tenant"));

        given()
          .contentType("application/json")
          .body("{\"@odata.id\": \"https://graph.microsoft.com/v1.0/directoryObjects/" + userId + "\"}")
          .when().post("/v1.0/groups/{id}/members/$ref", EntraStore.DEV_GROUP_OBJECT_ID)
          .then().statusCode(204);

        given()
          .contentType("application/json")
          .body("{}")
          .when().post("/v1.0/users/{id}/getMemberGroups", userId)
          .then().statusCode(200)
          .body("value", hasItem(EntraStore.DEV_GROUP_OBJECT_ID));

        given().when().post("/_admin/reset")
          .then().statusCode(204);

        // EntraStore didn't self-register as Resettable before — this Graph-written
        // membership (and the user backing it) used to survive a reset.
        given()
          .contentType("application/json")
          .body("{}")
          .when().post("/v1.0/users/{id}/getMemberGroups", userId)
          .then().statusCode(404);

        // the zero-setup dev fixtures must still work after a reset
        given()
          .contentType("application/json")
          .body("{}")
          .when().post("/v1.0/users/{id}/getMemberGroups", EntraStore.DEV_USER_OBJECT_ID)
          .then().statusCode(200)
          .body("value", hasItem(EntraStore.DEV_GROUP_OBJECT_ID));
    }

    @Test
    void resetClearsApimEmailAndAppConfigState() {
        given().contentType("application/json").body("""
                {
                  "location": "eastus",
                  "sku": {"name": "Developer", "capacity": 1},
                  "properties": {"publisherEmail": "admin@example.com", "publisherName": "floci"}
                }
                """)
                .when().put(APIM_SERVICE)
                .then().statusCode(200)
                .body("name", equalTo("reset-apim"));

        given().contentType("application/json")
                .body("{\"value\": \"reset-me\"}")
                .when().put("/devstoreaccount1-appconfig/kv/reset-key?api-version=1.0")
                .then().statusCode(200);

        given().contentType("application/json").body("""
                {
                  "senderAddress": "noreply@reset.test",
                  "content": {"subject": "reset-me", "plainText": "bye"},
                  "recipients": {"to": [{"address": "dev@reset.test"}]}
                }
                """)
                .when().post("/emails:send?api-version=2023-03-31")
                .then().statusCode(202);

        given().when().get("/emailMessages")
                .then().statusCode(200)
                .body("count", greaterThan(0));

        given().when().post("/_admin/reset")
                .then().statusCode(204);

        // apim and email were both silently missing from reset before
        // Resettable self-registration.
        given().when().get(APIM_SERVICE)
                .then().statusCode(404);

        given().when().get("/emailMessages")
                .then().statusCode(200)
                .body("count", equalTo(0));

        given().when().get("/devstoreaccount1-appconfig/kv/reset-key?api-version=1.0")
                .then().statusCode(404);
    }
}
