package io.floci.az.core;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

@QuarkusTest
@DisplayName("/_admin/reset — every state-holding service self-registers via Resettable")
class AdminResetTest {

    private static final String APIM_SERVICE =
            "/subscriptions/reset-sub/resourceGroups/reset-rg"
                    + "/providers/Microsoft.ApiManagement/service/reset-apim?api-version=2024-05-01";

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
