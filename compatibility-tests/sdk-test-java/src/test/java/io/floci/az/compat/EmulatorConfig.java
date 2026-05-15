package io.floci.az.compat;

import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import reactor.core.publisher.Mono;

import java.net.MalformedURLException;
import java.net.URL;

public final class EmulatorConfig {

    static final String DEV_KEY =
        "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==";
    static final String ACCOUNT = "devstoreaccount1";

    private static final String BASE =
        System.getenv().getOrDefault("FLOCI_AZ_ENDPOINT", "http://localhost:4577");

    static final String COSMOS_KEY =
        "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==";

    static final String BLOB_CONN = String.format(
        "DefaultEndpointsProtocol=http;AccountName=%s;AccountKey=%s;BlobEndpoint=%s/%s;",
        ACCOUNT, DEV_KEY, BASE, ACCOUNT);

    static final String QUEUE_CONN = String.format(
        "DefaultEndpointsProtocol=http;AccountName=%s;AccountKey=%s;QueueEndpoint=%s/%s-queue;",
        ACCOUNT, DEV_KEY, BASE, ACCOUNT);

    static final String TABLE_CONN = String.format(
        "DefaultEndpointsProtocol=http;AccountName=%s;AccountKey=%s;TableEndpoint=%s/%s-table;",
        ACCOUNT, DEV_KEY, BASE, ACCOUNT);

    /**
     * Builds a CosmosClient pointing at the floci-az emulator over HTTPS (port 4578).
     *
     * The azure-cosmos Java SDK always enforces TLS in gateway mode, so the emulator
     * must expose an HTTPS endpoint.  We disable certificate validation so the
     * self-signed cert is accepted without importing it into a trust store.
     */
    static CosmosClient buildCosmosClient() {
        // The Java Cosmos SDK (gateway mode) constructs request URLs from the
        // scheme+host+port of the endpoint, ignoring the path component.
        // floci-az normally uses path-based routing (/devstoreaccount1-cosmos/…),
        // but that is incompatible with the Java SDK's URL-building logic.
        //
        // Instead we point the SDK at the bare HTTPS root (https://localhost:4578)
        // and handle root-level Cosmos paths (/dbs, /colls, /) in the routing
        // filter on the server side, mapping them to the default account.
        String httpsBase = BASE.replace("http://", "https://").replace(":4577", ":4578");
        return new CosmosClientBuilder()
                .endpoint(httpsBase)   // e.g. https://localhost:4578
                .key(COSMOS_KEY)
                .gatewayMode()
                .endpointDiscoveryEnabled(false)
                .buildClient();
    }

    /**
     * Builds a ConfigurationClient pointing at the floci-az emulator.
     *
     * The App Configuration SDK assumes the Endpoint starts with https:// (8 chars) when
     * extracting the hostname. We satisfy that assumption by passing an https:// endpoint
     * in the connection string, then use ForceHttpPolicy to rewrite the URL back to http://
     * before each request is actually sent.
     */
    static ConfigurationClient buildAppConfigClient() {
        String httpsBase = BASE.replace("http://", "https://");
        String endpoint = httpsBase + "/" + ACCOUNT + "-appconfig";
        String connStr = String.format("Endpoint=%s;Id=%s;Secret=%s", endpoint, ACCOUNT, DEV_KEY);
        return new ConfigurationClientBuilder()
                .connectionString(connStr)
                .addPolicy(new ForceHttpPolicy())
                .buildClient();
    }

    /**
     * Rewrites https:// → http:// on every outgoing request so the SDK can talk to
     * a plain-HTTP emulator even though the connection string uses https://.
     */
    static final class ForceHttpPolicy implements HttpPipelinePolicy {
        @Override
        public Mono<HttpResponse> process(HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
            URL url = context.getHttpRequest().getUrl();
            if ("https".equals(url.getProtocol())) {
                try {
                    context.getHttpRequest().setUrl(
                            new URL("http", url.getHost(), url.getPort(), url.getFile()));
                } catch (MalformedURLException e) {
                    return Mono.error(e);
                }
            }
            return next.process();
        }
    }

    private EmulatorConfig() {}
}
