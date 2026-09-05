package io.floci.az.services.keyvault;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class KeyVaultTimestampTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unsetDatesAreOmittedThroughoutSecretLifecycle(boolean explicitNulls) {
        String vault = vault();
        Map<String, Object> body = new HashMap<>(Map.of("value", "secret-value"));
        if (explicitNulls) {
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("nbf", null);
            attributes.put("exp", null);
            body.put("attributes", attributes);
        }

        var created = request().body(body).put(vault + "secrets/example");
        try {
            assertUnsetDates(created.then(), "attributes");
            String id = created.jsonPath().getString("id");
            String version = id.substring(id.lastIndexOf('/') + 1);

            assertUnsetDates(request().get(vault + "secrets/example").then(), "attributes");
            assertUnsetDates(request().get(vault + "secrets/example/" + version).then(), "attributes");
            assertUnsetDates(request().get(vault + "secrets").then(), "value[0].attributes");
            assertUnsetDates(request().get(vault + "secrets/example/versions").then(), "value[0].attributes");
            assertUnsetDates(request().body(Map.of("contentType", "text/plain"))
                    .patch(vault + "secrets/example/" + version).then(), "attributes");
            assertUnsetDates(request().delete(vault + "secrets/example").then(), "attributes");
            assertUnsetDates(request().get(vault + "deletedsecrets/example").then(), "attributes");
            assertUnsetDates(request().get(vault + "deletedsecrets").then(), "value[0].attributes");
            assertUnsetDates(request().post(vault + "deletedsecrets/example/recover").then(), "attributes");
        } finally {
            purge(vault);
        }
    }

    @ParameterizedTest
    @CsvSource({"nbf, 0", "exp, 0", "nbf, 1700000000", "exp, 1900000000"})
    void setDatesStayNumericAndClearedDatesAreOmitted(String attribute, int timestamp) {
        String vault = vault();
        String otherAttribute = "nbf".equals(attribute) ? "exp" : "nbf";
        var created = request().body(Map.of("value", "secret-value",
                        "attributes", Map.of(attribute, timestamp)))
                .put(vault + "secrets/example");
        try {
            created.then().statusCode(200)
                    .body("attributes." + attribute, equalTo(timestamp))
                    .body("attributes", not(hasKey(otherAttribute)));
            String id = created.jsonPath().getString("id");
            String version = id.substring(id.lastIndexOf('/') + 1);
            String versionPath = vault + "secrets/example/" + version;

            request().body(Map.of("contentType", "text/plain"))
                    .patch(versionPath).then().statusCode(200)
                    .body("attributes." + attribute, equalTo(timestamp));
            request().get(vault + "secrets").then().statusCode(200)
                    .body("value[0].attributes." + attribute, equalTo(timestamp));

            Map<String, Object> cleared = new HashMap<>();
            cleared.put(attribute, null);
            assertUnsetDates(request().body(Map.of("attributes", cleared))
                    .patch(versionPath).then(), "attributes");
            assertUnsetDates(request().get(versionPath).then(), "attributes");
            assertUnsetDates(request().get(vault + "secrets/example").then(), "attributes");
        } finally {
            purge(vault);
        }
    }

    private static void assertUnsetDates(ValidatableResponse response, String path) {
        response.statusCode(200)
                .body(path, not(hasKey("nbf")))
                .body(path, not(hasKey("exp")))
                .body(path + ".enabled", equalTo(true))
                .body(path + ".created", instanceOf(Number.class))
                .body(path + ".updated", instanceOf(Number.class));
    }

    private static RequestSpecification request() {
        return given().header("Authorization", "Bearer test-token")
                .contentType("application/json").queryParam("api-version", "7.4");
    }

    private static String vault() {
        return "/kv" + UUID.randomUUID().toString().replace("-", "") + "-keyvault/";
    }

    private static void purge(String vault) {
        request().delete(vault + "secrets/example").then().statusCode(anyOf(is(200), is(404)));
        request().delete(vault + "deletedsecrets/example").then().statusCode(204);
    }
}
