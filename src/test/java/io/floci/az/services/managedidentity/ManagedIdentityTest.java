package io.floci.az.services.managedidentity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("ManagedIdentityHandler - ARM userAssignedIdentities + IMDS token endpoint")
class ManagedIdentityTest {

    private static final String SUB = "test-sub-msi";
    private static final String RG = "test-rg-msi";
    private static final String API = "?api-version=2024-11-30";
    private static final String BASE =
            "/subscriptions/" + SUB + "/resourceGroups/" + RG
                    + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities";
    private static final String IMDS = "/metadata/identity/oauth2/token";
    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
    }

    // ── ARM CRUD ────────────────────────────────────────────────────────────────

    @Test
    void identityLifecycle() {
        String body = """
                {"location": "westus", "tags": {"env": "test"}}
                """;

        String clientId = given().contentType("application/json").body(body)
                .when().put(BASE + "/id1" + API)
                .then().statusCode(201)
                .body("name", equalTo("id1"))
                .body("type", equalTo("Microsoft.ManagedIdentity/userAssignedIdentities"))
                .body("location", equalTo("westus"))
                .body("tags.env", equalTo("test"))
                .body("id", equalTo(BASE + "/id1"))
                .body("properties.principalId", matchesPattern(UUID_PATTERN))
                .body("properties.clientId", matchesPattern(UUID_PATTERN))
                .body("properties.tenantId", matchesPattern(UUID_PATTERN))
                .extract().path("properties.clientId");

        // Re-PUT keeps the generated ids stable and returns 200.
        given().contentType("application/json").body(body)
                .when().put(BASE + "/id1" + API)
                .then().statusCode(200)
                .body("properties.clientId", equalTo(clientId));

        given().when().get(BASE + "/id1" + API)
                .then().statusCode(200)
                .body("name", equalTo("id1"))
                .body("properties.clientId", equalTo(clientId));

        given().contentType("application/json").body("""
                {"tags": {"env": "prod"}}
                """)
                .when().patch(BASE + "/id1" + API)
                .then().statusCode(200)
                .body("tags.env", equalTo("prod"))
                .body("properties.clientId", equalTo(clientId));

        given().when().get(BASE + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("id1"));

        given().when().get("/subscriptions/" + SUB
                        + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities" + API)
                .then().statusCode(200)
                .body("value", hasSize(1));

        given().when().delete(BASE + "/id1" + API)
                .then().statusCode(200);

        given().when().get(BASE + "/id1" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void putReplacesTagsWhenBodyOmitsThem() {
        given().contentType("application/json")
                .body("{\"location\": \"westus\", \"tags\": {\"env\": \"test\"}}")
                .when().put(BASE + "/tagged" + API)
                .then().statusCode(201)
                .body("tags.env", equalTo("test"));

        // ARM PUT is full-replace: omitting tags clears the existing ones.
        given().contentType("application/json").body("{\"location\": \"westus\"}")
                .when().put(BASE + "/tagged" + API)
                .then().statusCode(200)
                .body("tags", anEmptyMap());

        given().when().get(BASE + "/tagged" + API)
                .then().statusCode(200)
                .body("tags", anEmptyMap());
    }

    @Test
    void malformedJsonBodyIsRejected() {
        given().contentType("application/json").body("{not json")
                .when().put(BASE + "/broken" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("InvalidRequestContent"));

        given().when().get(BASE + "/broken" + API)
                .then().statusCode(404);
    }

    @Test
    void nestedChildProviderPathDoesNotTouchTheIdentity() {
        given().contentType("application/json").body("{\"location\": \"eastus\"}")
                .when().put(BASE + "/scoped" + API)
                .then().statusCode(201);

        // A role assignment scoped to the identity is not a ManagedIdentity resource:
        // deleting it must 404 harmlessly instead of deleting the identity itself.
        given().when().delete(BASE + "/scoped/providers/Microsoft.Authorization/roleAssignments/"
                        + "22222222-2222-2222-2222-222222222222?api-version=2022-04-01")
                .then().statusCode(404);

        given().when().get(BASE + "/scoped" + API)
                .then().statusCode(200)
                .body("name", equalTo("scoped"));
    }

    @Test
    void identityIsListedAmongResourceGroupResources() {
        given().contentType("application/json").body("{\"location\": \"eastus\"}")
                .when().put(BASE + "/rg-visible" + API)
                .then().statusCode(201);

        // azurerm checks this listing before deleting a resource group.
        given().when().get("/subscriptions/" + SUB + "/resourceGroups/" + RG
                        + "/resources?api-version=2021-04-01")
                .then().statusCode(200)
                .body("value.find { it.name == 'rg-visible' }.type",
                        equalTo("Microsoft.ManagedIdentity/userAssignedIdentities"));
    }

    @Test
    void federatedIdentityCredentialLifecycle() {
        given().contentType("application/json").body("{\"location\": \"eastus\"}")
                .when().put(BASE + "/id2" + API)
                .then().statusCode(201);

        String ficBody = """
                {"properties": {"issuer": "https://token.actions.githubusercontent.com",
                                "subject": "repo:org/repo:ref:refs/heads/main",
                                "audiences": ["api://AzureADTokenExchange"]}}
                """;

        given().contentType("application/json").body(ficBody)
                .when().put(BASE + "/id2/federatedIdentityCredentials/fic1" + API)
                .then().statusCode(201)
                .body("name", equalTo("fic1"))
                .body("type", equalTo("Microsoft.ManagedIdentity/userAssignedIdentities/federatedIdentityCredentials"))
                .body("properties.issuer", equalTo("https://token.actions.githubusercontent.com"))
                .body("properties.audiences", hasSize(1));

        given().when().get(BASE + "/id2/federatedIdentityCredentials" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("fic1"));

        // Missing required properties → 400 CloudError.
        given().contentType("application/json").body("{\"properties\": {\"issuer\": \"x\"}}")
                .when().put(BASE + "/id2/federatedIdentityCredentials/fic2" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadRequest"));

        // Parent identity must exist.
        given().contentType("application/json").body(ficBody)
                .when().put(BASE + "/missing/federatedIdentityCredentials/fic1" + API)
                .then().statusCode(404);

        given().when().delete(BASE + "/id2/federatedIdentityCredentials/fic1" + API)
                .then().statusCode(200);

        given().when().get(BASE + "/id2/federatedIdentityCredentials/fic1" + API)
                .then().statusCode(404);
    }

    @Test
    void systemAssignedIdentityDefaultRead() {
        String scope = "/subscriptions/" + SUB + "/resourceGroups/" + RG
                + "/providers/Microsoft.Compute/virtualMachines/vm1";

        String principalId = given()
                .when().get(scope + "/providers/Microsoft.ManagedIdentity/identities/default" + API)
                .then().statusCode(200)
                .body("name", equalTo("default"))
                .body("type", equalTo("Microsoft.ManagedIdentity/identities"))
                .body("properties.principalId", matchesPattern(UUID_PATTERN))
                .body("properties.clientSecretUrl", notNullValue())
                .extract().path("properties.principalId");

        // Deterministic: same scope yields the same principal.
        given().when().get(scope + "/providers/Microsoft.ManagedIdentity/identities/default" + API)
                .then().statusCode(200)
                .body("properties.principalId", equalTo(principalId));
    }

    // ── IMDS ────────────────────────────────────────────────────────────────────

    @Test
    void imdsRejectsMissingMetadataHeader() {
        given().when().get(IMDS + "?resource=https://management.azure.com/&api-version=2018-02-01")
                .then().statusCode(400)
                .header("Www-Authenticate", startsWith("Basic realm="))
                .body("error", equalTo("invalid_request"))
                .body("error_description", equalTo("Required metadata header not specified"));
    }

    @Test
    void imdsRejectsNonGetMethods() {
        // Per imds spec 2023-07-01 the token endpoint is GET-only.
        given().header("Metadata", "true")
                .when().post(IMDS + "?resource=https://management.azure.com/&api-version=2018-02-01")
                .then().statusCode(405)
                .body("error", equalTo("method_not_allowed"));
    }

    @Test
    void imdsSystemAssignedTokenMatchesIdentitiesDefault() throws Exception {
        // The configured system-assigned scope (default subscription) must yield the same
        // principal via ARM identities/default and via the IMDS token's oid claim.
        String scope = "/subscriptions/00000000-0000-0000-0000-000000000001";
        Map<String, String> armProps = given()
                .when().get(scope + "/providers/Microsoft.ManagedIdentity/identities/default" + API)
                .then().statusCode(200)
                .extract().path("properties");

        Map<String, Object> tokenBody = given().header("Metadata", "true")
                .when().get(IMDS + "?resource=https://management.azure.com/&api-version=2018-02-01")
                .then().statusCode(200)
                .extract().as(new io.restassured.common.mapper.TypeRef<Map<String, Object>>() {});

        assertEquals(armProps.get("clientId"), tokenBody.get("client_id"));
        Map<?, ?> claims = mapper.readValue(
                URL_DECODER.decode(((String) tokenBody.get("access_token")).split("\\.")[1]), Map.class);
        assertEquals(armProps.get("principalId"), claims.get("oid"));
    }

    @Test
    void imdsRejectsMissingResource() {
        given().header("Metadata", "true")
                .when().get(IMDS + "?api-version=2018-02-01")
                .then().statusCode(400)
                .body("error", equalTo("invalid_request"));
    }

    @Test
    void imdsRejectsUnknownClientId() {
        given().header("Metadata", "true")
                .when().get(IMDS + "?resource=https://management.azure.com/"
                        + "&client_id=11111111-1111-1111-1111-111111111111&api-version=2018-02-01")
                .then().statusCode(400)
                .body("error", equalTo("invalid_request"))
                .body("error_description", equalTo("Identity not found"));
    }

    @Test
    void imdsRejectsMultipleSelectors() {
        given().header("Metadata", "true")
                .when().get(IMDS + "?resource=https://management.azure.com/"
                        + "&client_id=a&object_id=b&api-version=2018-02-01")
                .then().statusCode(400)
                .body("error", equalTo("invalid_request"));
    }

    @Test
    void imdsSystemAssignedTokenHasAllStringFields() {
        Map<String, Object> body = given().header("Metadata", "true")
                .when().get(IMDS + "?resource=https://management.azure.com/&api-version=2018-02-01")
                .then().statusCode(200)
                .body("token_type", equalTo("Bearer"))
                .body("resource", equalTo("https://management.azure.com/"))
                .extract().as(new io.restassured.common.mapper.TypeRef<Map<String, Object>>() {});

        // Per imds spec 2023-07-01 every value in the token response is a string.
        for (String field : List.of("access_token", "client_id", "expires_in", "expires_on",
                "ext_expires_in", "not_before", "resource", "token_type")) {
            assertTrue(body.get(field) instanceof String, field + " must be a string");
        }
        long notBefore = Long.parseLong((String) body.get("not_before"));
        long expiresOn = Long.parseLong((String) body.get("expires_on"));
        assertEquals(Long.parseLong((String) body.get("expires_in")), expiresOn - notBefore);
    }

    @Test
    void imdsUserAssignedTokenVerifiesAgainstJwks() throws Exception {
        given().contentType("application/json").body("{\"location\": \"eastus\"}")
                .when().put(BASE + "/token-id" + API)
                .then().statusCode(201);

        Map<String, String> identity = given().when().get(BASE + "/token-id" + API)
                .then().statusCode(200).extract().path("properties");
        String clientId = identity.get("clientId");
        String principalId = identity.get("principalId");

        String accessToken = given().header("Metadata", "true")
                .when().get(IMDS + "?resource=https://vault.azure.net"
                        + "&client_id=" + clientId + "&api-version=2018-02-01")
                .then().statusCode(200)
                .body("client_id", equalTo(clientId))
                .extract().path("access_token");

        String[] parts = accessToken.split("\\.");
        assertEquals(3, parts.length);

        Map<?, ?> claims = mapper.readValue(URL_DECODER.decode(parts[1]), Map.class);
        assertEquals("https://vault.azure.net", claims.get("aud"));
        assertEquals(clientId, claims.get("appid"), "v1.0 tokens carry appid");
        assertEquals(principalId, claims.get("oid"));
        assertEquals("1.0", claims.get("ver"));
        assertEquals("app", claims.get("idtyp"));
        assertNull(claims.get("scp"), "IMDS tokens are app-only, no scp");
        assertTrue(String.valueOf(claims.get("iss")).contains(String.valueOf(claims.get("tid"))),
                "v1 issuer embeds the tenant id");

        // Signature verifies against the Entra JWKS the emulator already serves.
        Map<String, Object> jwks = given().when().get("/common/discovery/v2.0/keys")
                .then().statusCode(200)
                .extract().as(new io.restassured.common.mapper.TypeRef<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> key = ((List<Map<String, Object>>) jwks.get("keys")).get(0);
        BigInteger n = new BigInteger(1, URL_DECODER.decode((String) key.get("n")));
        BigInteger e = new BigInteger(1, URL_DECODER.decode((String) key.get("e")));
        PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pub);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(URL_DECODER.decode(parts[2])),
                "IMDS token must verify against the emulator JWKS");
    }
}
