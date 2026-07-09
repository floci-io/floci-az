package io.floci.az.compat;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.ManagedIdentityCredential;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compatibility test for Managed Identity (Microsoft.ManagedIdentity/userAssignedIdentities)
 * and the IMDS token endpoint exposed by floci-az.
 *
 * <p>Mirrors {@link VmCompatibilityTest}: ARM management-plane CRUD is driven with a raw
 * {@link HttpClient} against the real REST wire protocol. The IMDS data plane is exercised
 * twice: raw HTTP against {@code /metadata/identity/oauth2/token} (shape per imds spec
 * 2023-07-01), and — when {@code AZURE_POD_IDENTITY_AUTHORITY_HOST} points at the emulator —
 * through the real azure-identity {@code ManagedIdentityCredential}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Managed Identity Compatibility")
class ManagedIdentityCompatibilityTest {

    private static final String BASE =
            System.getenv().getOrDefault("FLOCI_AZ_ENDPOINT", "http://localhost:4577");
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String RG = "msi-rg-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String IDENTITY = "msi-" + UUID.randomUUID().toString().substring(0, 8);

    private static final String MSI_API = "2024-11-30";
    private static final String IMDS_API = "2018-02-01";
    private static final String RG_API = "2021-04-01";

    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    private static String clientId;
    private static String principalId;

    @BeforeAll
    static void setup() {
        EmulatorConfig.assumeEmulatorRunning();
    }

    @Test
    @Order(1)
    void createIdentity_generatesServerSideGuids() throws Exception {
        assertOk(put(resourceGroupUrl(), "{\"location\":\"eastus\"}"), "create resource group");

        HttpResponse<String> resp = put(identityUrl(),
                "{\"location\":\"eastus\",\"tags\":{\"env\":\"compat\"}}");
        assertEquals(201, resp.statusCode(), "create identity: " + resp.body());

        JsonNode json = mapper.readTree(resp.body());
        assertEquals(IDENTITY, json.get("name").asText());
        assertEquals("Microsoft.ManagedIdentity/userAssignedIdentities", json.get("type").asText());
        assertEquals("eastus", json.get("location").asText());
        JsonNode props = json.get("properties");
        clientId = props.get("clientId").asText();
        principalId = props.get("principalId").asText();
        assertGuid(clientId, "clientId");
        assertGuid(principalId, "principalId");
        assertGuid(props.get("tenantId").asText(), "tenantId");

        // Idempotent re-PUT keeps the generated ids and returns 200.
        HttpResponse<String> resp2 = put(identityUrl(), "{\"location\":\"eastus\"}");
        assertEquals(200, resp2.statusCode(), resp2.body());
        assertEquals(clientId, mapper.readTree(resp2.body()).get("properties").get("clientId").asText());
    }

    @Test
    @Order(2)
    void getAndListIdentity() throws Exception {
        HttpResponse<String> getResp = get(identityUrl());
        assertEquals(200, getResp.statusCode(), getResp.body());
        assertEquals(IDENTITY, mapper.readTree(getResp.body()).get("name").asText());

        HttpResponse<String> rgList = get(identityCollectionInRgUrl());
        assertEquals(200, rgList.statusCode(), rgList.body());
        assertContainsName(mapper.readTree(rgList.body()).get("value"), IDENTITY);

        HttpResponse<String> subList = get(BASE + "/subscriptions/" + SUBSCRIPTION
                + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities?api-version=" + MSI_API);
        assertEquals(200, subList.statusCode(), subList.body());
        assertContainsName(mapper.readTree(subList.body()).get("value"), IDENTITY);
    }

    @Test
    @Order(3)
    void federatedIdentityCredentialLifecycle() throws Exception {
        String body = """
                {"properties": {"issuer": "https://token.actions.githubusercontent.com",
                                "subject": "repo:floci-io/floci-az:ref:refs/heads/main",
                                "audiences": ["api://AzureADTokenExchange"]}}
                """;
        HttpResponse<String> putResp = put(ficUrl("gh-actions"), body);
        assertEquals(201, putResp.statusCode(), putResp.body());
        JsonNode fic = mapper.readTree(putResp.body());
        assertEquals("gh-actions", fic.get("name").asText());
        assertEquals("Microsoft.ManagedIdentity/userAssignedIdentities/federatedIdentityCredentials",
                fic.get("type").asText());

        HttpResponse<String> listResp = get(identityUrl().replace("?api-version",
                "/federatedIdentityCredentials?api-version"));
        assertEquals(200, listResp.statusCode(), listResp.body());
        assertContainsName(mapper.readTree(listResp.body()).get("value"), "gh-actions");

        assertOk(delete(ficUrl("gh-actions")), "delete federated credential");
        assertEquals(404, get(ficUrl("gh-actions")).statusCode());
    }

    @Test
    @Order(4)
    void imdsRawHttp_requiresMetadataHeaderAndReturnsStringFields() throws Exception {
        // Probe without the Metadata header → IMDS-shaped 400.
        HttpResponse<String> noHeader = get(imdsUrl(null));
        assertEquals(400, noHeader.statusCode(), noHeader.body());
        assertEquals("invalid_request", mapper.readTree(noHeader.body()).get("error").asText());

        // User-assigned token by client_id.
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(imdsUrl(clientId))).header("Metadata", "true").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), resp.body());
        JsonNode json = mapper.readTree(resp.body());
        for (String field : new String[]{"access_token", "client_id", "expires_in", "expires_on",
                "ext_expires_in", "not_before", "resource", "token_type"}) {
            assertTrue(json.get(field).isTextual(), field + " must be a string: " + json.get(field));
        }
        assertEquals("Bearer", json.get("token_type").asText());
        assertEquals(clientId, json.get("client_id").asText());

        JsonNode claims = decodeJwtPayload(json.get("access_token").asText());
        assertEquals("https://management.azure.com/", claims.get("aud").asText());
        assertEquals(clientId, claims.get("appid").asText());
        assertEquals(principalId, claims.get("oid").asText());
        assertEquals("1.0", claims.get("ver").asText());
    }

    @Test
    @Order(5)
    void managedIdentityCredential_sdkAcquiresToken() {
        // The azure-identity IMDS path only reaches the emulator when
        // AZURE_POD_IDENTITY_AUTHORITY_HOST overrides http://169.254.169.254.
        Assumptions.assumeTrue(System.getenv("AZURE_POD_IDENTITY_AUTHORITY_HOST") != null,
                "AZURE_POD_IDENTITY_AUTHORITY_HOST not set; skipping SDK-level IMDS test");

        ManagedIdentityCredential credential = new ManagedIdentityCredentialBuilder()
                .clientId(clientId)
                .build();
        AccessToken token = credential
                .getToken(new TokenRequestContext().addScopes("https://management.azure.com/.default"))
                .block(Duration.ofSeconds(30));

        assertNotNull(token, "SDK must acquire a token from the emulator IMDS endpoint");
        assertTrue(token.getExpiresAt().isAfter(java.time.OffsetDateTime.now()),
                "token must not be expired");
        JsonNode claims = decodeJwtPayload(token.getToken());
        assertEquals(clientId, claims.get("appid").asText());
    }

    @Test
    @Order(6)
    void deleteIdentity_thenImdsLookupFails() throws Exception {
        assertOk(delete(identityUrl()), "delete identity");
        assertEquals(404, get(identityUrl()).statusCode());

        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(imdsUrl(clientId))).header("Metadata", "true").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, resp.statusCode(), resp.body());
        assertEquals("invalid_request", mapper.readTree(resp.body()).get("error").asText());
    }

    // ── URL builders ────────────────────────────────────────────────────────────

    private static String resourceGroupUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "?api-version=" + RG_API;
    }

    private static String msiProviderUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ManagedIdentity";
    }

    private static String identityUrl() {
        return msiProviderUrl() + "/userAssignedIdentities/" + IDENTITY + "?api-version=" + MSI_API;
    }

    private static String identityCollectionInRgUrl() {
        return msiProviderUrl() + "/userAssignedIdentities?api-version=" + MSI_API;
    }

    private static String ficUrl(String name) {
        return msiProviderUrl() + "/userAssignedIdentities/" + IDENTITY
                + "/federatedIdentityCredentials/" + name + "?api-version=" + MSI_API;
    }

    private static String imdsUrl(String clientIdParam) {
        String url = BASE + "/metadata/identity/oauth2/token"
                + "?resource=https://management.azure.com/&api-version=" + IMDS_API;
        return clientIdParam == null ? url : url + "&client_id=" + clientIdParam;
    }

    // ── HTTP helpers ────────────────────────────────────────────────────────────

    private static HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> put(String url, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .PUT(HttpRequest.BodyPublishers.ofString(json))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── Assertions ──────────────────────────────────────────────────────────────

    private static JsonNode decodeJwtPayload(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            assertEquals(3, parts.length, "JWT must have header.payload.signature");
            return mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (Exception e) {
            throw new AssertionError("failed to decode JWT payload", e);
        }
    }

    private static void assertGuid(String value, String field) {
        assertTrue(value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                field + " must be a GUID: " + value);
    }

    private static void assertContainsName(JsonNode array, String name) {
        assertNotNull(array);
        assertTrue(array.isArray(), "Expected array but got " + array);
        for (JsonNode item : array) {
            if (name.equals(item.get("name").asText())) {
                return;
            }
        }
        throw new AssertionError("Expected list to contain name " + name + ": " + array);
    }

    private static void assertOk(HttpResponse<String> resp, String operation) {
        assertTrue(resp.statusCode() >= 200 && resp.statusCode() < 300,
                operation + " failed: " + resp.statusCode() + " " + resp.body());
    }
}
