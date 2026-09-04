package io.floci.az.services.keyvault;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class KeyVaultServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {"secrets", "secrets/"})
    void listSecretsWithOptionalTrailingSlash(String path) {
        String vault = "/kv" + UUID.randomUUID().toString().replace("-", "") + "-keyvault/";

        given().header("Authorization", "Bearer test-token")
                .queryParam("api-version", "7.4")
                .when().get(vault + path)
                .then().statusCode(200)
                .body("value", hasSize(0))
                .body("nextLink", nullValue());

        given().header("Authorization", "Bearer test-token")
                .contentType("application/json")
                .body(Map.of("value", "secret-value", "contentType", "text/plain"))
                .when().put(vault + "secrets/example?api-version=7.4")
                .then().statusCode(200);

        try {
            String expected = given().header("Authorization", "Bearer test-token")
                    .when().get(vault + "secrets?api-version=7.4")
                    .then().statusCode(200).extract().asString();

            given().header("Authorization", "Bearer test-token")
                    .queryParam("api-version", "7.4")
                    .when().get(vault + path)
                    .then().statusCode(200)
                    .body(equalTo(expected))
                    .body("value", hasSize(1))
                    .body("value[0].id", containsString("/secrets/example"))
                    .body("value[0].contentType", equalTo("text/plain"))
                    .body("value[0].value", nullValue());
        } finally {
            given().header("Authorization", "Bearer test-token")
                    .when().delete(vault + "secrets/example").then().statusCode(200);
            given().header("Authorization", "Bearer test-token")
                    .when().delete(vault + "deletedsecrets/example").then().statusCode(204);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"deletedsecrets", "deletedsecrets/"})
    void listDeletedSecretsWithOptionalTrailingSlash(String path) {
        String vault = "/kv" + UUID.randomUUID().toString().replace("-", "") + "-keyvault/";
        given().header("Authorization", "Bearer test-token")
                .contentType("application/json").body(Map.of("value", "secret-value"))
                .when().put(vault + "secrets/example").then().statusCode(200);
        given().header("Authorization", "Bearer test-token")
                .when().delete(vault + "secrets/example").then().statusCode(200);
        try {
            given().header("Authorization", "Bearer test-token")
                    .when().get(vault + path + "?api-version=7.4")
                    .then().statusCode(200)
                    .body("value", hasSize(1))
                    .body("value[0].id", containsString("/secrets/example"));
        } finally {
            given().header("Authorization", "Bearer test-token")
                    .when().delete(vault + "deletedsecrets/example").then().statusCode(204);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "deletedsecrets", "certificates/contacts"})
    void fixedRoutesAcceptOptionalTrailingSlash(String path) {
        String url = "/kv" + UUID.randomUUID().toString().replace("-", "") + "-keyvault"
                + (path.isEmpty() ? "" : "/" + path);
        String expected = given().header("Authorization", "Bearer test-token")
                .when().get(url + "?api-version=7.4")
                .then().statusCode(200).extract().asString();

        given().header("Authorization", "Bearer test-token")
                .when().get(url + "/?api-version=7.4")
                .then().statusCode(200).body(equalTo(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"secrets", "secrets/", "deletedsecrets", "deletedsecrets/",
            "certificates/contacts", "certificates/contacts/"})
    void fixedRoutesRequireAuthentication(String path) {
        given().when().get("/devstoreaccount1-keyvault/" + path + "?api-version=7.4")
                .then().statusCode(401)
                .header("WWW-Authenticate", containsString("Bearer"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void fixedRoutesRejectNonGetMethods(String method) {
        for (String path : new String[]{"secrets", "secrets/", "deletedsecrets", "deletedsecrets/",
                "certificates/contacts", "certificates/contacts/"}) {
            given().header("Authorization", "Bearer test-token")
                    .contentType("application/json").body(Map.of("value", "invalid"))
                    .when().request(method, "/devstoreaccount1-keyvault/" + path + "?api-version=7.4")
                    .then().statusCode(405);
        }
    }
}
