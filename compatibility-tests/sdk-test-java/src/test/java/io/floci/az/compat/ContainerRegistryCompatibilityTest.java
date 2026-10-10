package io.floci.az.compat;

import com.azure.containers.containerregistry.ContainerRegistryClient;
import com.azure.containers.containerregistry.ContainerRegistryClientBuilder;
import com.azure.containers.containerregistry.ContainerRegistryContentClient;
import com.azure.containers.containerregistry.ContainerRegistryContentClientBuilder;
import com.azure.containers.containerregistry.models.OciDescriptor;
import com.azure.containers.containerregistry.models.OciImageManifest;
import com.azure.containers.containerregistry.models.UploadRegistryBlobResult;
import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.azure.core.util.BinaryData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.InetNameResolver;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Azure Container Registry through the Azure SDK for Java container-registry clients: the client
 * calls the data plane without credentials, answers the {@code 401} bearer challenge by trading an
 * Entra token at {@code /oauth2/exchange} and the refresh token at {@code /oauth2/token} for the
 * scope the challenge named, and retries. The clients start that exchange only when the challenge
 * carries both {@code service} and {@code scope}.
 *
 * <p>The clients address {@code https://{name}.azurecr.io}, and the host is how the emulator tells
 * which registry a request is for, so it has to survive. {@link RegistryHostPolicy} switches each
 * request to plain HTTP on the emulator's port, keeping the host; it runs after the SDK's
 * credential policy, which refuses a bearer token over {@code http://}. The HTTP client then
 * resolves every host name to the emulator, as a hosts entry for {@code *.azurecr.io} would.</p>
 *
 * <p>Needs the shared {@code registry:2} container: skipped in mocked mode or without Docker.</p>
 */
@DisplayName("Container Registry: SDK data plane through the bearer challenge")
class ContainerRegistryCompatibilityTest {

    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String RG = "acr-sdk-rg";
    private static final String REGISTRY = "acrsdk" + UUID.randomUUID().toString().substring(0, 8);
    private static final String LOGIN_SERVER = REGISTRY + ".azurecr.io";
    private static final String ENDPOINT = "https://" + LOGIN_SERVER;
    private static final String API = "2025-11-01";
    private static final String REPOSITORY = "sdk/hello";

    // java.net.http.HttpResponse is written qualified: it clashes with the azure-core HttpResponse a
    // pipeline policy returns.
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** The emulator accepts any Entra token at the exchange endpoint. */
    private static final TokenCredential CREDENTIAL =
            request -> Mono.just(new AccessToken("entra-token", OffsetDateTime.now().plusHours(1)));

    @BeforeAll
    static void createTheRegistry() throws Exception {
        EmulatorConfig.assumeEmulatorRunning();
        java.net.http.HttpResponse<String> put = HTTP.send(HttpRequest.newBuilder(URI.create(armUrl()))
                        .header("Authorization", "Bearer fake")
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"location\":\"eastus\","
                                + "\"sku\":{\"name\":\"Basic\"},\"properties\":{\"adminUserEnabled\":true}}"))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertTrue(put.statusCode() == 200 || put.statusCode() == 201, put.body());

        String state = "";
        for (int attempt = 0; attempt < 60; attempt++) {
            JsonNode registry = MAPPER.readTree(get(armUrl()));
            state = registry.path("properties").path("provisioningState").asText();
            if (state.equals("Succeeded") || state.equals("Failed")) {
                break;
            }
            Thread.sleep(2000);
        }
        assertEquals("Succeeded", state, "the registry did not provision");
    }

    @AfterAll
    static void deleteTheRegistry() throws Exception {
        HTTP.send(HttpRequest.newBuilder(URI.create(armUrl()))
                        .header("Authorization", "Bearer fake").DELETE().build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());
    }

    @Test
    void pushesAnImageAndListsItsRepositoryAfterAnsweringTheChallenge() {
        Set<String> paths = ConcurrentHashMap.newKeySet();
        RegistryHostPolicy routing = new RegistryHostPolicy(paths);

        ContainerRegistryContentClient content = new ContainerRegistryContentClientBuilder()
                .endpoint(ENDPOINT)
                .repositoryName(REPOSITORY)
                .credential(CREDENTIAL)
                .addPolicy(routing)
                .httpClient(emulatorConnection())
                .buildClient();
        ContainerRegistryClient registry = new ContainerRegistryClientBuilder()
                .endpoint(ENDPOINT)
                .credential(CREDENTIAL)
                .addPolicy(routing)
                .httpClient(emulatorConnection())
                .buildClient();

        assumeTheRegistryDataPlaneRuns(registry);

        UploadRegistryBlobResult config = content.uploadBlob(BinaryData.fromString("{}"));
        OciImageManifest manifest = new OciImageManifest()
                .setSchemaVersion(2)
                .setConfiguration(new OciDescriptor()
                        .setMediaType("application/vnd.oci.image.config.v1+json")
                        .setDigest(config.getDigest())
                        .setSizeInBytes(config.getSizeInBytes()))
                .setLayers(List.of());
        content.setManifest(manifest, "v1");

        List<String> repositories = registry.listRepositoryNames().stream().toList();

        assertTrue(repositories.contains(REPOSITORY), "listed repositories: " + repositories);
        assertTrue(paths.contains("/oauth2/exchange"),
                "the client never traded its Entra token, so it never answered a challenge: " + paths);
        assertTrue(paths.contains("/oauth2/token"),
                "the client never asked for a scoped access token: " + paths);
    }

    /** Mocked mode answers every authenticated repository request with UNSUPPORTED. */
    private static void assumeTheRegistryDataPlaneRuns(ContainerRegistryClient registry) {
        try {
            registry.listRepositoryNames().stream().count();
        } catch (HttpResponseException e) {
            Assumptions.assumeTrue(e.getResponse().getStatusCode() != 405
                            && e.getResponse().getStatusCode() != 502,
                    "the shared registry container is not running: " + e.getMessage());
            throw e;
        }
    }

    /** An HTTP client that connects to the emulator for every request, leaving the URL's host alone. */
    private static com.azure.core.http.HttpClient emulatorConnection() {
        // Qualified: reactor-netty's and azure-core's HttpClient clash with java.net.http.HttpClient.
        reactor.netty.http.client.HttpClient netty = reactor.netty.http.client.HttpClient.create()
                .resolver(new EmulatorResolverGroup(URI.create(EmulatorConfig.httpBase()).getHost()));
        return new NettyAsyncHttpClientBuilder(netty).build();
    }

    /** Resolves every host name to the emulator's address. */
    static final class EmulatorResolverGroup extends AddressResolverGroup<InetSocketAddress> {
        private final String emulatorHost;

        EmulatorResolverGroup(String emulatorHost) {
            this.emulatorHost = emulatorHost;
        }

        @Override
        protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
            return new InetNameResolver(executor) {
                @Override
                protected void doResolve(String host, Promise<InetAddress> promise) {
                    try {
                        promise.setSuccess(InetAddress.getByName(emulatorHost));
                    } catch (UnknownHostException e) {
                        promise.setFailure(e);
                    }
                }

                @Override
                protected void doResolveAll(String host, Promise<List<InetAddress>> promise) {
                    try {
                        promise.setSuccess(List.of(InetAddress.getByName(emulatorHost)));
                    } catch (UnknownHostException e) {
                        promise.setFailure(e);
                    }
                }
            }.asAddressResolver();
        }
    }

    private static String armUrl() {
        return EmulatorConfig.httpBase() + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ContainerRegistry/registries/" + REGISTRY + "?api-version=" + API;
    }

    private static String get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer fake").GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    /**
     * Moves a request for {@code https://{name}.azurecr.io/...} to plain HTTP on the emulator's
     * port, keeping the registry host, and records the paths the client called.
     */
    static final class RegistryHostPolicy implements HttpPipelinePolicy {
        private final Set<String> paths;

        RegistryHostPolicy(Set<String> paths) {
            this.paths = paths;
        }

        @Override
        public Mono<HttpResponse> process(HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
            URL url = context.getHttpRequest().getUrl();
            URI emulator = URI.create(EmulatorConfig.httpBase());
            paths.add(url.getPath());
            try {
                context.getHttpRequest().setUrl(new URL("http", url.getHost(), emulator.getPort(),
                        url.getFile()));
            } catch (MalformedURLException e) {
                return Mono.error(e);
            }
            return next.process();
        }
    }
}
