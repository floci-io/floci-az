package io.floci.az.compat;

import com.azure.core.amqp.AmqpTransportType;
import com.azure.core.credential.AccessToken;
import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.apache.qpid.proton.engine.SslDomain;
import org.junit.jupiter.api.Assumptions;
import reactor.core.publisher.Mono;

import jakarta.jms.ConnectionFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class EmulatorConfig {

    static final String DEV_KEY =
        "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==";
    static final String ACCOUNT = "devstoreaccount1";

    private static final String BASE =
        System.getenv().getOrDefault("FLOCI_AZ_ENDPOINT", "http://localhost:4577");

    /** HTTP base URL of the running emulator (e.g. {@code http://localhost:4577}). */
    static String httpBase() {
        return BASE;
    }

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
     * Builds a CosmosClient pointing at the floci-az emulator over HTTPS on the same port (4577).
     *
     * The azure-cosmos Java SDK always enforces TLS in gateway mode. floci-az serves both HTTP
     * and HTTPS on port 4577 via a protocol-sniffing proxy when FLOCI_AZ_TLS_ENABLED=true.
     *
     * The emulator exposes its current TLS certificate (static or self-signed) via
     * {@code GET /_floci-az/tls-cert}. We fetch it, write it to a temp PKCS12 truststore,
     * and set {@code javax.net.ssl.*} system properties so Reactor Netty (JDK SSL mode,
     * enabled via {@code -Dio.netty.handler.ssl.noOpenSsl=true} in the surefire argLine)
     * picks up the truststore when building its SSL context.
     */
    static CosmosClient buildCosmosClient() {
        try {
            installEmulatorTlsCert();
        } catch (Exception e) {
            throw new RuntimeException("Failed to install emulator TLS certificate", e);
        }
        // The Java Cosmos SDK (gateway mode) constructs request URLs from the
        // scheme+host+port of the endpoint, ignoring the path component.
        // floci-az uses path-based routing (/devstoreaccount1-cosmos/…) but the routing
        // filter also handles root-level Cosmos paths (/dbs, /colls, /) mapping them
        // to the default account, which is what the SDK produces.
        String httpsBase = BASE.replace("http://", "https://");
        return new CosmosClientBuilder()
                .endpoint(httpsBase)   // e.g. https://localhost:4577 or https://floci-az:4577
                .key(COSMOS_KEY)
                .gatewayMode()
                .endpointDiscoveryEnabled(false)
                .buildClient();
    }

    /**
     * Fetches the emulator's TLS certificate PEM from {@code GET {BASE}/_floci-az/tls-cert},
     * imports it into a temp PKCS12 KeyStore, and sets {@code javax.net.ssl.trustStore*}
     * system properties so Reactor Netty (with JDK SSL) trusts the self-signed cert.
     */
    static void installEmulatorTlsCert() throws Exception {
        String certUrl = BASE + "/_floci/tls-cert";
        HttpURLConnection conn = (HttpURLConnection) URI.create(certUrl).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);
        int status = conn.getResponseCode();
        if (status != 200) {
            return; // TLS not enabled or cert not available — skip truststore setup
        }
        String pem = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (pem.isBlank()) {
            return;
        }

        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setCertificateEntry("floci-az", cert);

        var tmp = Files.createTempFile("floci-az-trust-", ".p12");
        tmp.toFile().deleteOnExit();
        try (var os = Files.newOutputStream(tmp)) {
            ks.store(os, "changeit".toCharArray());
        }

        System.setProperty("javax.net.ssl.trustStore", tmp.toAbsolutePath().toString());
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
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

    // ── Event Hubs / AMQP ────────────────────────────────────────────────────

    static final String EVENTHUB_HOST =
        System.getenv().getOrDefault("EVENTHUB_HOST", "localhost");
    static final int EVENTHUB_AMQP_PORT =
        Integer.parseInt(System.getenv().getOrDefault("EVENTHUB_AMQP_PORT", "5672"));
    static final int EVENTHUB_AMQPS_PORT =
        Integer.parseInt(System.getenv().getOrDefault("EVENTHUB_AMQPS_PORT", "5671"));
    static final String EVENTHUB_NAMESPACE =
        System.getenv().getOrDefault("EVENTHUB_NAMESPACE", "emulatorNs1");
    static final String EVENTHUB_NAME =
        System.getenv().getOrDefault("EVENTHUB_NAME", "eh1");

    /**
     * True when the emulator is in mocked Event Hubs mode (no Artemis broker started).
     * AMQP tests should be skipped when this is true.
     * Set by {@link #ensureEventHubNamespace()}.
     */
    static boolean eventHubMocked = false;

    /**
     * Starts the default Event Hubs namespace on-demand via the floci-az management API.
     * Idempotent: returns 200 if the namespace is already running.
     *
     * Reads {@code mocked} from the JSON response and stores it in {@link #eventHubMocked}.
     * When mocked, AMQP data-plane tests should be skipped via {@code Assumptions.assumeTrue}.
     */
    static void ensureEventHubNamespace() throws Exception {
        String url = BASE + "/" + ACCOUNT + "-eventhub/namespaces/" + EVENTHUB_NAMESPACE;
        String body = String.format(
                "{\"amqpPort\":%d,\"amqpTlsPort\":%d}",
                EVENTHUB_AMQP_PORT, EVENTHUB_AMQPS_PORT);
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        if (status != 200 && status != 201) {
            throw new RuntimeException(
                    "Failed to start Event Hubs namespace '" + EVENTHUB_NAMESPACE + "', HTTP " + status);
        }

        String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> json = mapper.readValue(responseBody, Map.class);
            Object mockedValue = json.get("mocked");
            eventHubMocked = Boolean.TRUE.equals(mockedValue);
        } catch (Exception e) {
            // If we can't parse the response, leave eventHubMocked as false
        }
    }

    /**
     * Returns the AMQP entity address that Artemis has pre-configured as an ANYCAST address.
     * The hostname portion must be lowercase because ArtemisConfigGenerator lowercases it
     * when building the broker.xml addresses.
     */
    static String amqpEntityAddress() {
        return "amqp://" + EVENTHUB_HOST.toLowerCase() + "/" + EVENTHUB_NAMESPACE + "/" + EVENTHUB_NAME;
    }

    static String amqpCgAddress(String consumerGroup) {
        return amqpEntityAddress() + "/" + consumerGroup;
    }

    /** Creates a plain-AMQP JMS connection factory pointing at the Artemis sidecar. */
    static ConnectionFactory buildAmqpConnectionFactory() {
        return new JmsConnectionFactory("amqp://" + EVENTHUB_HOST + ":" + EVENTHUB_AMQP_PORT);
    }

    static SecretClient buildKeyVaultClient() {
        String httpsBase = BASE.replace("http://", "https://");
        String vaultUrl = httpsBase + "/" + ACCOUNT + "-keyvault";
        return new SecretClientBuilder()
                .vaultUrl(vaultUrl)
                .credential(req -> Mono.just(new AccessToken("fake-token", OffsetDateTime.now().plusHours(1))))
                .addPolicy(new ForceHttpPolicy())
                .disableChallengeResourceVerification()
                .buildClient();
    }

    // ── Service Bus ───────────────────────────────────────────────────────────

    static final String SERVICEBUS_HOST =
        System.getenv().getOrDefault("SERVICEBUS_HOST", "localhost");
    static final String SERVICEBUS_NAMESPACE =
        System.getenv().getOrDefault("SERVICEBUS_NAMESPACE", "default");

    /**
     * AMQPS (TLS) port resolved from the namespace API response (local) or the
     * {@code SERVICEBUS_AMQPS_PORT} env var (Docker compat tests).
     * Set by {@link #ensureServiceBusNamespace()}.
     */
    static int serviceBusAmqpsPort = 0;

    /**
     * True when the emulator is in mocked Service Bus mode (no Artemis broker started).
     * AMQP tests should be skipped when this is true.
     * Set by {@link #ensureServiceBusNamespace()}.
     */
    static boolean serviceBusMocked = false;

    /**
     * Starts a Service Bus namespace on-demand via the floci-az management API.
     * Idempotent: returns 200 if the namespace already exists.
     *
     * Reads {@code amqpsPort} from the JSON response and installs the self-signed
     * Artemis TLS certificate into the JVM default SSLContext so that proton-j
     * (the AMQP transport used by the Azure SDK) can verify it.
     *
     * The Azure Service Bus SDK 7.17.x always uses TLS regardless of the
     * {@code UseDevelopmentEmulator=true} flag — it hard-codes {@code enableSsl=true}
     * when {@code customEndpointAddress} is set.
     */
    static void ensureServiceBusNamespace() throws Exception {
        String url = BASE + "/" + ACCOUNT + "-servicebus/namespaces/" + SERVICEBUS_NAMESPACE;
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Content-Length", "2");
        try (OutputStream os = conn.getOutputStream()) {
            os.write("{}".getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        if (status != 200 && status != 201) {
            throw new RuntimeException(
                    "Failed to start Service Bus namespace '" + SERVICEBUS_NAMESPACE + "', HTTP " + status);
        }

        String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> json = mapper.readValue(responseBody, Map.class);

            // Prefer env var (Docker: SERVICEBUS_AMQPS_PORT=5671 = internal AMQPS port).
            String envPort = System.getenv("SERVICEBUS_AMQPS_PORT");
            if (envPort != null && !envPort.isEmpty()) {
                serviceBusAmqpsPort = Integer.parseInt(envPort);
            } else {
                serviceBusAmqpsPort = ((Number) json.get("amqpsPort")).intValue();
            }

            // Detect mocked mode (no Artemis broker) — AMQP tests should be skipped.
            Object mockedValue = json.get("mocked");
            serviceBusMocked = Boolean.TRUE.equals(mockedValue);

            // Install the Artemis self-signed cert so proton-j can verify it over TLS.
            String certPem = (String) json.get("tlsCertPem");
            if (certPem != null && !certPem.isEmpty()) {
                installArtemisServiceBusCert(certPem);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse namespace response: " + responseBody, e);
        }
    }

    /**
     * Installs a trust-all TrustManager into the JVM's default SSLContext.
     * Used for local emulator connections where self-signed certs are expected.
     * Acceptable for test-only code — never use in production.
     */
    static void installTrustAll() throws Exception {
        TrustManager trustAll = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{trustAll}, null);
        SSLContext.setDefault(ctx);
    }

    /**
     * Installs a trust-all TrustManager into the JVM's default SSLContext so that
     * proton-j (used by the Azure Service Bus SDK) accepts the Artemis self-signed cert.
     */
    static void installArtemisServiceBusCert(String certPem) throws Exception {
        installTrustAll();
    }

    /**
     * Sets SslDomain.VerifyMode.ANONYMOUS_PEER on the builder via reflection.
     *
     * The {@code verifyMode()} method is package-private in ServiceBusClientBuilder, so we must
     * use reflection to call it. ANONYMOUS_PEER disables both certificate chain validation and
     * hostname verification at the proton-j level, which is required because the Artemis sidecar
     * uses a self-signed cert that doesn't match the Docker service hostname.
     */
    private static void setAnonymousPeer(ServiceBusClientBuilder builder) {
        try {
            Method m = ServiceBusClientBuilder.class.getDeclaredMethod("verifyMode", SslDomain.VerifyMode.class);
            m.setAccessible(true);
            m.invoke(builder, SslDomain.VerifyMode.ANONYMOUS_PEER);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set ANONYMOUS_PEER on ServiceBusClientBuilder", e);
        }
    }

    /**
     * Creates a queue via the Azure spec path (/{entityName}) — the same path used by
     * {@code ServiceBusAdministrationClient}. Triggers lazy namespace auto-start.
     */
    static void createQueueViaSpecPath(String queueName, boolean requiresSession) throws Exception {
        String url = BASE + "/" + ACCOUNT + "-servicebus/" + queueName;
        String body = requiresSession
                ? "<entry xmlns=\"http://www.w3.org/2005/Atom\"><content type=\"application/xml\">"
                  + "<QueueDescription xmlns=\"http://schemas.microsoft.com/netservices/2010/10/servicebus/connect\">"
                  + "<RequiresSession>true</RequiresSession></QueueDescription></content></entry>"
                : "<entry xmlns=\"http://www.w3.org/2005/Atom\"><content type=\"application/xml\">"
                  + "<QueueDescription xmlns=\"http://schemas.microsoft.com/netservices/2010/10/servicebus/connect\">"
                  + "</QueueDescription></content></entry>";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("Content-Type", "application/atom+xml;charset=utf-8");
        conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }
        int status = conn.getResponseCode();
        if (status != 200 && status != 201) {
            throw new RuntimeException("createQueueViaSpecPath '" + queueName + "' failed, HTTP " + status);
        }
    }

    /** GET the Service Bus namespaces list; returns the raw JSON body. */
    static String getServiceBusNamespaces() throws Exception {
        String url = BASE + "/" + ACCOUNT + "-servicebus/namespaces";
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);
        conn.getResponseCode();
        InputStream is = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream();
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Creates a queue via the floci-az management API (ATOM XML). */
    static void ensureServiceBusQueue(String queueName, boolean requiresSession) throws Exception {
        String url = BASE + "/" + ACCOUNT + "-servicebus/" + SERVICEBUS_NAMESPACE
                + "/queues/" + queueName;
        String body = requiresSession
                ? "<entry xmlns=\"http://www.w3.org/2005/Atom\"><content type=\"application/xml\">"
                  + "<QueueDescription xmlns=\"http://schemas.microsoft.com/netservices/2010/10/servicebus/connect\">"
                  + "<RequiresSession>true</RequiresSession></QueueDescription></content></entry>"
                : "";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("Content-Type", "application/atom+xml;charset=utf-8");
        conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }
        int status = conn.getResponseCode();
        if (status != 200 && status != 201) {
            throw new RuntimeException(
                    "Failed to create Service Bus queue '" + queueName + "', HTTP " + status);
        }
    }

    /** Creates a topic via the floci-az management API. */
    static void ensureServiceBusTopic(String topicName) throws Exception {
        String url = BASE + "/" + ACCOUNT + "-servicebus/" + SERVICEBUS_NAMESPACE
                + "/topics/" + topicName;
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("Content-Type", "application/atom+xml;charset=utf-8");
        conn.setRequestProperty("Content-Length", "0");
        conn.getOutputStream().close();
        int status = conn.getResponseCode();
        if (status != 200 && status != 201) {
            throw new RuntimeException(
                    "Failed to create Service Bus topic '" + topicName + "', HTTP " + status);
        }
    }

    /** Creates a subscription via the floci-az management API. */
    static void ensureServiceBusSubscription(String topicName, String subName) throws Exception {
        String url = BASE + "/" + ACCOUNT + "-servicebus/" + SERVICEBUS_NAMESPACE
                + "/topics/" + topicName + "/subscriptions/" + subName;
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("Content-Type", "application/atom+xml;charset=utf-8");
        conn.setRequestProperty("Content-Length", "0");
        conn.getOutputStream().close();
        int status = conn.getResponseCode();
        if (status != 200 && status != 201) {
            throw new RuntimeException(
                    "Failed to create subscription '" + topicName + "/" + subName + "', HTTP " + status);
        }
    }

    /**
     * Creates or replaces a subscription rule via the floci-az management API.
     * {@code ruleDescriptionXml} is the inner {@code <RuleDescription>} element
     * (with xmlns declarations), as sent by ServiceBusAdministrationClient.
     */
    static void ensureServiceBusRule(String topicName, String subName,
                                      String ruleName, String ruleDescriptionXml) throws Exception {
        String url = BASE + "/" + ACCOUNT + "-servicebus/" + SERVICEBUS_NAMESPACE
                + "/topics/" + topicName + "/subscriptions/" + subName + "/rules/" + ruleName;
        String body = "<entry xmlns=\"http://www.w3.org/2005/Atom\"><content type=\"application/xml\">"
                + ruleDescriptionXml + "</content></entry>";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("Content-Type", "application/atom+xml;charset=utf-8");
        conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }
        int status = conn.getResponseCode();
        if (status != 200 && status != 201) {
            throw new RuntimeException("Failed to create rule '" + ruleName + "' on '"
                    + topicName + "/" + subName + "', HTTP " + status);
        }
    }

    /** Deletes a subscription rule (e.g. the implicit $Default) via the management API. */
    static void deleteServiceBusRule(String topicName, String subName, String ruleName) throws Exception {
        String url = BASE + "/" + ACCOUNT + "-servicebus/" + SERVICEBUS_NAMESPACE
                + "/topics/" + topicName + "/subscriptions/" + subName + "/rules/" + ruleName;
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("DELETE");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(30_000);
        int status = conn.getResponseCode();
        if (status != 200) {
            throw new RuntimeException("Failed to delete rule '" + ruleName + "' on '"
                    + topicName + "/" + subName + "', HTTP " + status);
        }
    }

    /**
     * Returns a ServiceBusClientBuilder connected to the emulator's AMQPS (TLS) port.
     *
     * The Azure Service Bus SDK 7.17.x always uses TLS when {@code customEndpointAddress}
     * is set ({@code enableSsl=true} hardcoded). We use ANONYMOUS_PEER via reflection to
     * disable certificate chain and hostname verification at the proton-j level, since the
     * Artemis self-signed cert cannot be validated against the Docker service hostname.
     */
    static ServiceBusClientBuilder serviceBusClientBuilder() {
        String connStr = String.format(
                "Endpoint=sb://%s;SharedAccessKeyName=RootManageSharedAccessKey;"
                + "SharedAccessKey=devkey;",
                SERVICEBUS_HOST);
        ServiceBusClientBuilder builder = new ServiceBusClientBuilder()
                .connectionString(connStr)
                .customEndpointAddress("https://" + SERVICEBUS_HOST + ":" + serviceBusAmqpsPort)
                .transportType(AmqpTransportType.AMQP);
        setAnonymousPeer(builder);
        return builder;
    }

    // ── Emulator reachability ─────────────────────────────────────────────────

    /**
     * Returns true if the emulator's HTTP health endpoint responds within 2 seconds.
     * Used by {@link #assumeEmulatorRunning()} to skip test classes gracefully when
     * the emulator is not started.
     */
    public static boolean isEmulatorReachable() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            client.send(req, BodyHandlers.discarding());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Skips the entire test class when the emulator is not reachable.
     * Call as the first statement in {@code @BeforeAll} so no test methods run
     * (and no timeout-induced failures accumulate) when floci-az is not started.
     *
     * <pre>
     *   &#64;BeforeAll void setup() {
     *       EmulatorConfig.assumeEmulatorRunning();
     *       // ... build clients ...
     *   }
     * </pre>
     */
    public static void assumeEmulatorRunning() {
        Assumptions.assumeTrue(
                isEmulatorReachable(),
                "floci-az emulator is not running at " + BASE + " — skipping. Start with: make run");
    }

    /**
     * Hits the floci-az control-plane endpoint to trigger on-demand engine startup.
     * Returns the parsed JSON response as a Map, or {@code null} when:
     * <ul>
     *   <li>The engine is not enabled (HTTP 503)</li>
     *   <li>The emulator is not running (ConnectException)</li>
     * </ul>
     * Callers use {@code assumeTrue(result != null)} to skip gracefully in both cases.
     */
    public static Map<String, Object> triggerCosmosEngine(String serviceTypeSuffix) {
        String url = BASE + "/devstoreaccount1-" + serviceTypeSuffix + "/connect";
        try {
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
            if (response.statusCode() == 503) {
                return null;  // engine not enabled
            }
            if (response.statusCode() != 200) {
                throw new RuntimeException("Unexpected status " + response.statusCode()
                        + " from " + url + ": " + response.body());
            }
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        } catch (ConnectException e) {
            return null;  // emulator not running — skip gracefully
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // java.net.http may wrap ConnectException inside an IOException
            Throwable cause = e.getCause();
            while (cause != null) {
                if (cause instanceof ConnectException) return null;
                cause = cause.getCause();
            }
            throw new RuntimeException("Failed to call " + url, e);
        }
    }

    private EmulatorConfig() {}
}
