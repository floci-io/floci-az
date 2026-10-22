package io.floci.az.services.entra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins the OpenID Connect discovery document for every tenant form, and checks that it advertises
 * only what the token service serves: every listed grant is accepted by the token endpoint and
 * every listed endpoint URL is routed.
 */
@QuarkusTest
class DiscoveryGoldenTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestHTTPResource("/")
    URL root;

    private String baseUrl() {
        String url = root.toString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private Map<String, Object> expectedDocument(String segment) {
        String tenantBase = baseUrl() + "/" + segment;
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("issuer", tenantBase + "/v2.0");
        doc.put("authorization_endpoint", tenantBase + "/oauth2/v2.0/authorize");
        doc.put("token_endpoint", tenantBase + "/oauth2/v2.0/token");
        doc.put("userinfo_endpoint", baseUrl() + "/oidc/userinfo");
        doc.put("end_session_endpoint", tenantBase + "/oauth2/v2.0/logout");
        doc.put("jwks_uri", tenantBase + "/discovery/v2.0/keys");
        doc.put("response_types_supported", List.of("code"));
        doc.put("response_modes_supported", List.of("query", "fragment"));
        doc.put("subject_types_supported", List.of("pairwise"));
        doc.put("id_token_signing_alg_values_supported", List.of("RS256"));
        doc.put("scopes_supported", List.of("openid", "profile", "email"));
        doc.put("token_endpoint_auth_methods_supported",
                List.of("client_secret_post", "client_secret_basic", "private_key_jwt"));
        doc.put("grant_types_supported", List.of("authorization_code", "client_credentials", "password"));
        doc.put("http_logout_supported", true);
        doc.put("frontchannel_logout_supported", true);
        doc.put("request_uri_parameter_supported", false);
        doc.put("tenant_region_scope", "NA");
        doc.put("cloud_instance_name", "microsoftonline.com");
        doc.put("claims_supported", List.of(
                "sub", "iss", "aud", "exp", "iat", "nbf", "oid", "tid", "ver",
                "appid", "azp", "scp", "idtyp", "uti"));
        return doc;
    }

    private static Map<String, Object> fetch(String path) throws Exception {
        String body = given().when().get(path).then().statusCode(200)
                .contentType("application/json").extract().asString();
        return MAPPER.readValue(body, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    @ParameterizedTest
    @ValueSource(strings = {TENANT, "common", "organizations"})
    void v2DiscoveryDocumentMatchesTheGolden(String segment) throws Exception {
        Map<String, Object> expected = expectedDocument(segment);
        Map<String, Object> actual = fetch("/" + segment + "/v2.0/.well-known/openid-configuration");
        assertEquals(expected, actual);
        assertEquals(List.copyOf(expected.keySet()), List.copyOf(actual.keySet()), "field order");
    }

    @ParameterizedTest
    @ValueSource(strings = {TENANT, "common", "organizations"})
    void v1DiscoveryPathServesTheSameDocument(String segment) throws Exception {
        assertEquals(expectedDocument(segment), fetch("/" + segment + "/.well-known/openid-configuration"));
    }

    @Test
    void everyAdvertisedGrantIsAcceptedByTheTokenEndpoint() throws Exception {
        Map<String, Object> doc = fetch("/" + TENANT + "/v2.0/.well-known/openid-configuration");
        @SuppressWarnings("unchecked")
        List<String> grants = (List<String>) doc.get("grant_types_supported");
        for (String grant : grants) {
            given()
              .contentType("application/x-www-form-urlencoded")
              .formParam("grant_type", grant)
              .formParam("client_id", EntraStore.DEV_CLIENT_ID)
              .when().post((String) doc.get("token_endpoint"))
              .then()
                .body("error", not(is("unsupported_grant_type")));
        }
    }

    @Test
    void everyAdvertisedEndpointIsRouted() throws Exception {
        Map<String, Object> doc = fetch("/" + TENANT + "/v2.0/.well-known/openid-configuration");
        for (Map.Entry<String, Object> field : doc.entrySet()) {
            boolean endpointField = field.getKey().endsWith("_endpoint") || field.getKey().equals("jwks_uri");
            // end_session_endpoint is kept for msal-js, which refuses to log out without it; the
            // logout route lands separately.
            if (!endpointField || field.getKey().equals("end_session_endpoint")) {
                continue;
            }
            String url = (String) field.getValue();
            Response response = field.getKey().equals("token_endpoint")
                    ? given().contentType("application/x-www-form-urlencoded")
                            .formParam("grant_type", "client_credentials").when().post(url)
                    : given().redirects().follow(false).when().get(url);
            assertNotEquals(404, response.statusCode(), field.getKey() + " " + url);
        }
    }

    @Test
    void offlineAccessIsNotAdvertisedWithoutTheRefreshTokenGrant() {
        given()
          .when().get("/{tenant}/v2.0/.well-known/openid-configuration", TENANT)
          .then()
            .statusCode(200)
            .body("grant_types_supported", not(hasItem("refresh_token")))
            .body("scopes_supported", not(hasItem("offline_access")))
            .body("$", not(hasKey("device_authorization_endpoint")))
            .body("userinfo_endpoint", is(baseUrl() + "/oidc/userinfo"));
    }
}
