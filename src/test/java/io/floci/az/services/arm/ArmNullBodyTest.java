package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * A request body that is the JSON literal {@code null} answers ARM's {@code 400 InvalidRequestContent} on the
 * leniently parsed operations, rather than reaching the handler as no map and failing with a 500.
 */
@QuarkusTest
@DisplayName("ARM: a JSON null body answers 400 InvalidRequestContent on leniently parsed operations")
class ArmNullBodyTest {

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "PUT /subscriptions/sub-null/resourceGroups/rg-null?api-version=2021-04-01",
        "PATCH /subscriptions/sub-null/resourceGroups/rg-null/providers/Microsoft.KeyVault/vaults/kv-null?api-version=2023-07-01",
        "POST /providers/Microsoft.Management/checkNameAvailability?api-version=2023-04-01",
        "POST /subscriptions/sub-null/providers/Microsoft.Communication/checkNameAvailability?api-version=2023-03-31"
    })
    void aNullBodyAnswersInvalidRequestContent(String request) {
        String[] parts = request.split(" ", 2);
        given().contentType(ContentType.JSON).body("null")
            .when().request(parts[0], parts[1])
            .then().statusCode(400)
            .body("error.code", equalTo("InvalidRequestContent"));
    }
}
