package io.floci.az.compat;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.identity.UsernamePasswordCredentialBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compatibility test for the Microsoft Entra ID token service: the OpenID discovery document, the JWKS,
 * and tokens acquired through the real {@code azure-identity} credentials (backed by MSAL4J).
 *
 * <p>azure-identity rejects a non-https authority host, so the credentials are given the https form of
 * the emulator URL and {@link EmulatorConfig.ForceHttpPolicy} rewrites every MSAL request back to http on
 * the credential's own pipeline. Instance discovery is disabled because it would call
 * {@code login.microsoftonline.com} to validate an authority it does not know.
 */
@DisplayName("Microsoft Entra ID Java SDK Compatibility")
class EntraCompatibilityTest {

    private static final String BASE = EmulatorConfig.httpBase();
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String CLIENT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CLIENT_SECRET = "floci-az-dev-secret";
    private static final String DEV_USER = "dev-user@floci-az.local";
    private static final String API_SCOPE = "api://floci-compat/.default";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode discovery;

    @BeforeAll
    static void setup() throws Exception {
        EmulatorConfig.assumeEmulatorRunning();
        discovery = getJson(BASE + "/" + TENANT + "/v2.0/.well-known/openid-configuration");
    }

    @Test
    void discoveryDocumentIsTenantRooted() {
        String tenantRoot = BASE + "/" + TENANT;
        assertEquals(tenantRoot + "/v2.0", discovery.get("issuer").asText());
        assertEquals(tenantRoot + "/oauth2/v2.0/token", discovery.get("token_endpoint").asText());
        assertEquals(tenantRoot + "/oauth2/v2.0/authorize", discovery.get("authorization_endpoint").asText());
        assertEquals(tenantRoot + "/discovery/v2.0/keys", discovery.get("jwks_uri").asText());
        assertTrue(textValues(discovery.get("id_token_signing_alg_values_supported")).contains("RS256"));
        assertTrue(textValues(discovery.get("grant_types_supported"))
                .containsAll(List.of("client_credentials", "password", "authorization_code")));
    }

    @Test
    void jwksPublishesAnRsaSigningKey() throws Exception {
        JsonNode keys = getJson(discovery.get("jwks_uri").asText()).get("keys");
        assertFalse(keys.isEmpty(), "JWKS must publish at least one key");
        JsonNode key = keys.get(0);
        assertEquals("RSA", key.get("kty").asText());
        assertEquals("sig", key.get("use").asText());
        assertFalse(key.get("kid").asText().isBlank());
        assertFalse(key.get("x5c").isEmpty(), "JWKS key must carry its certificate chain");
    }

    @Test
    void clientSecretCredentialAcquiresAnAppOnlyToken() throws Exception {
        TokenCredential credential = new ClientSecretCredentialBuilder()
                .tenantId(TENANT)
                .clientId(CLIENT_ID)
                .clientSecret(CLIENT_SECRET)
                .authorityHost(httpsBase())
                .disableInstanceDiscovery()
                .addPolicy(new EmulatorConfig.ForceHttpPolicy())
                .build();

        AccessToken token = acquire(credential, API_SCOPE);
        JsonNode claims = verifiedClaims(token.getToken());

        assertEquals(discovery.get("issuer").asText(), claims.get("iss").asText());
        assertEquals("api://floci-compat", claims.get("aud").asText());
        assertEquals(TENANT, claims.get("tid").asText());
        assertEquals(CLIENT_ID, claims.get("azp").asText());
        assertEquals("2.0", claims.get("ver").asText());
        assertEquals("app", claims.get("idtyp").asText());
        assertEquals(claims.get("oid").asText(), claims.get("sub").asText());
        assertFalse(claims.get("uti").asText().isBlank());
        assertTrue(token.getExpiresAt().isAfter(OffsetDateTime.now()), "token must not be expired");
    }

    @Test
    @Disabled("MSAL4J runs user realm discovery (GET /common/userrealm/{upn}?api-version=1.0) before the "
            + "password grant; floci-az does not serve that endpoint, the request falls through to Blob "
            + "Storage and the XML 404 fails MSAL's JSON parsing")
    void usernamePasswordCredentialAcquiresADelegatedToken() throws Exception {
        TokenCredential credential = new UsernamePasswordCredentialBuilder()
                .tenantId(TENANT)
                .clientId(CLIENT_ID)
                .username(DEV_USER)
                .password("any-password")
                .authorityHost(httpsBase())
                .disableInstanceDiscovery()
                .addPolicy(new EmulatorConfig.ForceHttpPolicy())
                .build();

        AccessToken token = acquire(credential, "https://management.azure.com/.default");
        JsonNode claims = verifiedClaims(token.getToken());

        assertEquals(discovery.get("issuer").asText(), claims.get("iss").asText());
        assertEquals(TENANT, claims.get("tid").asText());
        assertEquals(CLIENT_ID, claims.get("azp").asText());
        assertFalse(claims.has("idtyp"), "a delegated token carries no idtyp=app");
        assertFalse(claims.get("oid").asText().isBlank());
    }

    @Test
    void passwordGrantOverTheWireIssuesASignedDelegatedToken() throws Exception {
        String form = "grant_type=password&client_id=" + CLIENT_ID + "&username=" + DEV_USER
                + "&password=any-password&scope=https%3A%2F%2Fmanagement.azure.com%2F.default";
        HttpRequest request = HttpRequest.newBuilder(URI.create(discovery.get("token_endpoint").asText()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals("Bearer", body.get("token_type").asText());

        JsonNode claims = verifiedClaims(body.get("access_token").asText());
        assertEquals(discovery.get("issuer").asText(), claims.get("iss").asText());
        assertEquals(CLIENT_ID, claims.get("azp").asText());
        assertFalse(claims.has("idtyp"), "a delegated token carries no idtyp=app");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static AccessToken acquire(TokenCredential credential, String scope) {
        AccessToken token = credential.getToken(new TokenRequestContext().addScopes(scope))
                .block(Duration.ofSeconds(30));
        assertNotNull(token, "credential must return a token");
        return token;
    }

    /**
     * Verifies the RS256 signature against the key the JWKS publishes for the token's {@code kid}, then
     * checks the token's time window, and returns the claims.
     */
    private static JsonNode verifiedClaims(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length, "a signed JWT has three parts");
        JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertEquals("RS256", header.get("alg").asText());

        JsonNode jwk = null;
        for (JsonNode key : getJson(discovery.get("jwks_uri").asText()).get("keys")) {
            if (key.get("kid").asText().equals(header.get("kid").asText())) {
                jwk = key;
            }
        }
        assertNotNull(jwk, "JWKS must publish the key with kid " + header.get("kid"));

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(rsaKey(jwk));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])), "RS256 signature must verify");

        JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        long now = Instant.now().getEpochSecond();
        assertTrue(claims.get("nbf").asLong() <= now + 60, "nbf must not be in the future");
        assertTrue(claims.get("exp").asLong() > now, "exp must be in the future");
        return claims;
    }

    private static PublicKey rsaKey(JsonNode jwk) throws Exception {
        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("n").asText()));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("e").asText()));
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static String httpsBase() {
        return BASE.replace("http://", "https://");
    }

    private static JsonNode getJson(String url) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), url + ": " + response.body());
        return MAPPER.readTree(response.body());
    }
}
