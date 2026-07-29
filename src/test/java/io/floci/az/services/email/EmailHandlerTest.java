package io.floci.az.services.email;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Data-plane tests for {@link EmailHandler}.
 *
 * <p>The response shapes asserted here were taken from a live ACS resource, identical on
 * {@code 2023-03-31}, {@code 2024-07-01-preview} and {@code 2025-09-01}:
 *
 * <pre>
 *   POST /emails:send            → 202  {"id":…,"status":"Running","error":null}   + Operation-Location, Retry-After
 *   GET  /emails/operations/{id} → 200  {"id":…,"status":"Succeeded","error":null}
 * </pre>
 *
 * <p>Shape matters as much as status: the SDK models are code-generated with required fields, so an extra
 * or wrongly-typed property breaks clients that a status-code assertion would call healthy.
 */
@QuarkusTest
@DisplayName("EmailHandler — ACS Email data plane")
class EmailHandlerTest {

    private static final String API = "?api-version=2023-03-31";

    private static final String SEND_BODY = """
            {
              "senderAddress": "DoNotReply@example.com",
              "content": {"subject": "Hello", "html": "<p>Hi from floci-az</p>"},
              "recipients": {"to": [{"address": "dev@example.com"}]}
            }""";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    @DisplayName("send returns 202 with Operation-Location, Retry-After, and error:null")
    void sendReturnsAcsAcceptedShape() {
        given().contentType("application/json")
                .body(SEND_BODY)
                .when()
                .post("/emails:send" + API)
                .then()
                .statusCode(202)
                .header("Operation-Location", notNullValue())
                .header("Retry-After", notNullValue())
                .contentType(startsWith("application/json"))
                .body("id", notNullValue())
                .body("status", equalTo("Running"))
                .body("$", hasKey("error"))
                .body("error", nullValue());
    }

    @Test
    @DisplayName("Operation-Location is followable and carries the caller's scheme")
    void operationLocationIsFollowable() {
        given().contentType("application/json")
                .body(SEND_BODY)
                .when()
                .post("/emails:send" + API)
                .then()
                .statusCode(202)
                .header("Operation-Location", startsWith("http://"))
                .header("Operation-Location", containsString("/emails/operations/"))
                .header("Operation-Location", containsString("api-version=2023-03-31"));
    }

    @Test
    @DisplayName("operation status is {id, status, error} with no resourceLocation")
    void operationStatusMatchesAcsContract() {
        String operationId = sendAndExtractId();

        given().when()
                .get("/emails/operations/" + operationId + API)
                .then()
                .statusCode(200)
                .body("id", equalTo(operationId))
                .body("status", equalTo("Succeeded"))
                .body("$", hasKey("error"))
                .body("error", nullValue())
                .body("$", not(hasKey("resourceLocation")));
    }

    @Test
    @DisplayName("a caller-supplied Operation-Id becomes the operation id")
    void clientSuppliedOperationIdIsAdopted() {
        String supplied = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

        given().contentType("application/json")
                .header("Operation-Id", supplied)
                .body(SEND_BODY)
                .when()
                .post("/emails:send" + API)
                .then()
                .statusCode(202)
                .body("id", equalTo(supplied))
                .header("Operation-Location", containsString(supplied));

        given().when()
                .get("/emails/operations/" + supplied + API)
                .then()
                .statusCode(200)
                .body("id", equalTo(supplied))
                .body("status", equalTo("Succeeded"));
    }

    @Test
    @DisplayName("without Operation-Id the service generates one")
    void operationIdIsGeneratedWhenAbsent() {
        String first = sendAndExtractId();
        String second = sendAndExtractId();

        given().when().get("/emails/operations/" + first + API).then().statusCode(200);
        given().when().get("/emails/operations/" + second + API).then().statusCode(200);
        Assertions.assertNotEquals(first, second);
    }

    @Test
    @DisplayName("an unknown operation id is a 404")
    void unknownOperationIsNotFound() {
        given().when()
                .get("/emails/operations/00000000-0000-0000-0000-000000000000" + API)
                .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("the captured message keeps the original request payload")
    void capturedMessageRetainsRequest() {
        String operationId = sendAndExtractId();

        given().when()
                .get("/emailMessages/" + operationId)
                .then()
                .statusCode(200)
                .body("operationId", equalTo(operationId))
                .body("request.senderAddress", equalTo("DoNotReply@example.com"))
                .body("request.content.subject", equalTo("Hello"))
                .body("request.recipients.to[0].address", equalTo("dev@example.com"));
    }

    private String sendAndExtractId() {
        return given().contentType("application/json")
                .body(SEND_BODY)
                .when()
                .post("/emails:send" + API)
                .then()
                .statusCode(202)
                .extract()
                .path("id");
    }
}
