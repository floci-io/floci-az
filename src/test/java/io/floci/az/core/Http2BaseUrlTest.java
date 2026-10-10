package io.floci.az.core;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * URLs generated from the request must carry the authority the client addressed when it speaks
 * HTTP/2. HTTP/2 sends the host in {@code :authority} and no {@code Host} header, so a base URL
 * read from {@code Host} alone falls back to the configured {@code http://localhost:4577}: the wrong
 * port here, and the wrong scheme when the client reached the emulator over TLS (ALPN picks h2).
 * The client below speaks h2c with prior knowledge to {@code 127.0.0.1}, which differs from the
 * configured base URL in both host and port.
 */
@QuarkusTest
@TestProfile(Http2BaseUrlTest.MockedAciProfile.class)
class Http2BaseUrlTest {

    public static class MockedAciProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.aci.mocked", "true");
        }
    }

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";

    @TestHTTPResource("/")
    URL serverUrl;

    @Inject
    Vertx vertx;

    private HttpClient client;
    private String expectedBase;

    @BeforeEach
    void openHttp2Client() {
        int port = serverUrl.getPort();
        expectedBase = "http://127.0.0.1:" + port;
        client = vertx.createHttpClient(new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2)
                .setHttp2ClearTextUpgrade(false)
                .setDefaultHost("127.0.0.1")
                .setDefaultPort(port));
    }

    @AfterEach
    void closeHttp2Client() throws Exception {
        client.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void discoveryIssuerCarriesTheHttp2Authority() throws Exception {
        H2Response response = send(HttpMethod.GET, "/" + TENANT + "/v2.0/.well-known/openid-configuration",
                Map.of(), null);

        assertEquals(200, response.status());
        JsonObject discovery = new JsonObject(response.body());
        assertEquals(expectedBase + "/" + TENANT + "/v2.0", discovery.getString("issuer"));
        assertEquals(expectedBase + "/" + TENANT + "/oauth2/v2.0/token", discovery.getString("token_endpoint"));
    }

    @Test
    void managedIdentityUrlsCarryTheHttp2Authority() throws Exception {
        String scope = "/subscriptions/00000000-0000-0000-0000-0000000000a2";
        H2Response identity = send(HttpMethod.GET,
                scope + "/providers/Microsoft.ManagedIdentity/identities/default?api-version=2024-11-30",
                Map.of(), null);
        assertEquals(200, identity.status());
        assertEquals(expectedBase + scope + "/providers/Microsoft.ManagedIdentity/identities/default/credentials",
                new JsonObject(identity.body()).getJsonObject("properties").getString("clientSecretUrl"));

        H2Response token = send(HttpMethod.GET,
                "/metadata/identity/oauth2/token?resource=https://management.azure.com/&api-version=2018-02-01",
                Map.of("Metadata", "true"), null);
        assertEquals(200, token.status());
        String jwt = new JsonObject(token.body()).getString("access_token");
        JsonObject claims = new JsonObject(new String(
                Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8));
        assertTrue(claims.getString("iss").startsWith(expectedBase + "/"),
                () -> "issuer " + claims.getString("iss") + " should start with " + expectedBase);
    }

    @Test
    void aciOperationLocationCarriesTheHttp2Authority() throws Exception {
        String groups = "/subscriptions/test-sub-h2/resourceGroups/test-rg-h2"
                + "/providers/Microsoft.ContainerInstance/containerGroups/cg-h2";
        String api = "?api-version=2023-05-01";
        H2Response created = send(HttpMethod.PUT, groups + api, Map.of("Content-Type", "application/json"), """
                {"location": "westus", "properties": {"containers": [
                  {"name": "app", "properties": {"image": "busybox:latest"}}]}}
                """);
        assertEquals(201, created.status());

        H2Response started = send(HttpMethod.POST, groups + "/start" + api, Map.of(), null);

        assertEquals(202, started.status());
        assertTrue(started.location().startsWith(
                        expectedBase + "/subscriptions/test-sub-h2/providers/Microsoft.ContainerInstance/locations/westus/operations/"),
                () -> "Location " + started.location() + " should start with " + expectedBase);
    }

    private H2Response send(HttpMethod method, String uri, Map<String, String> headers, String body)
            throws Exception {
        Future<H2Response> response = client.request(method, uri).compose(request -> {
            headers.forEach(request::putHeader);
            return sendBody(request, body).compose(reply -> reply.body().map(buffer -> new H2Response(
                    reply.version(), reply.statusCode(), reply.getHeader("Location"), buffer.toString())));
        });
        H2Response result = response.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        assertEquals(HttpVersion.HTTP_2, result.version(), "the exchange must run over HTTP/2");
        return result;
    }

    private static Future<HttpClientResponse> sendBody(HttpClientRequest request, String body) {
        assertNull(request.headers().get("Host"), "an HTTP/2 request carries :authority, not Host");
        return body == null ? request.send() : request.send(body);
    }

    private record H2Response(HttpVersion version, int status, String location, String body) {
    }
}
