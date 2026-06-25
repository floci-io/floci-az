package io.floci.az.core;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.auth.AuthPipeline;
import io.floci.az.services.arm.ArmHandler;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class AzureRoutingFilter {

    private static final Logger LOGGER = Logger.getLogger(AzureRoutingFilter.class);

    private final AuthPipeline authPipeline;
    private final AzureServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final ArmHandler armHandler;
    private final Vertx vertx;

    @Inject
    public AzureRoutingFilter(AuthPipeline authPipeline, AzureServiceRegistry serviceRegistry,
            EmulatorConfig config, ArmHandler armHandler, Vertx vertx) {
        this.authPipeline = authPipeline;
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.armHandler = armHandler;
        this.vertx = vertx;
    }

    /**
     * Cosmos DB top-level path segments that are used by the Java SDK when it
     * constructs URLs from just the {@code scheme://host:port} part of the
     * configured endpoint (discarding the path component).
     *
     * <p>When the Java Cosmos SDK receives an endpoint such as
     * {@code https://localhost:4577} it sends requests like {@code GET /dbs} or
     * {@code POST /dbs/mydb/colls/items/docs} rather than the path-prefixed
     * form {@code /devstoreaccount1-cosmos/dbs} that the Python and Node SDKs
     * produce.  We intercept these root-level Cosmos paths here and re-route
     * them to the Cosmos handler with the default account name.</p>
     */
    private static final java.util.Set<String> COSMOS_ROOT_SEGMENTS = java.util.Set.of(
        "dbs", "colls", "docs", "pkranges", "offers", "sprocs", "triggers", "udfs"
    );

    @ServerRequestFilter(preMatching = true)
    public Uni<Response> filter(ContainerRequestContext requestContext, @Context HttpHeaders httpHeaders) {
        // Capture context before switching threads
        String path0 = requestContext.getUriInfo().getPath();
        HttpHeaders headers = httpHeaders;
        // Capture Host header now (JAX-RS request scope may not propagate to the blocking thread).
        // Try both canonical and lowercase forms since HTTP header names are case-insensitive.
        String h = requestContext.getHeaders().getFirst("Host");
        if (h == null) h = requestContext.getHeaders().getFirst("host");
        final String capturedHost = h;

        return Uni.createFrom().completionStage(
            vertx.executeBlocking(() -> doFilter(requestContext, path0, headers, capturedHost))
                 .toCompletionStage()
        );
    }


    private Response doFilter(ContainerRequestContext requestContext, String rawPath, HttpHeaders headers, String capturedHost) {
        boolean secure = requestContext.getSecurityContext().isSecure();
        String path = rawPath;

        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        // Health and admin endpoints bypass
        if (path.equals("health") || path.startsWith("_floci/") || path.equals("ready") || path.startsWith("_admin")) {
            return null;
        }

        LOGGER.infof("Incoming request: %s %s", requestContext.getMethod(), path);

        // Host-based routing for Azure data-plane services.
        // capturedHost is extracted in filter() before executeBlocking because the JAX-RS
        // request scope may not propagate to the Vert.x blocking thread.
        String hostOnly = null;
        if (capturedHost != null) {
            hostOnly = capturedHost.contains(":") ? capturedHost.split(":")[0] : capturedHost;
        }

        // Key Vault: {vaultName}.vault.azure.net
        if (hostOnly != null && hostOnly.endsWith(".vault.azure.net")) {
            String kvAccount = hostOnly.substring(0, hostOnly.length() - ".vault.azure.net".length());
            Map<String, String> kvQueryParams = new HashMap<>();
            requestContext.getUriInfo().getQueryParameters().forEach((k, v) -> kvQueryParams.put(k, v.get(0)));
            AzureRequest kvRequest = new AzureRequest(
                requestContext.getMethod(), kvAccount, "keyvault",
                path, headers, requestContext.getEntityStream(), kvQueryParams, null, secure);
            AuthContext kvAuth = authPipeline.resolve(kvRequest);
            kvRequest = new AzureRequest(
                requestContext.getMethod(), kvAccount, "keyvault",
                path, headers, requestContext.getEntityStream(), kvQueryParams, kvAuth, secure);
            Optional<AzureServiceHandler> kvHandler = serviceRegistry.resolve("keyvault");
            if (kvHandler.isPresent()) {
                LOGGER.infof("Dispatching vault.azure.net request to KeyVaultHandler: %s %s (account=%s)",
                    requestContext.getMethod(), path, kvAccount);
                return kvHandler.get().handle(kvRequest);
            }
        }

        // ACS Email: {account}.communication.azure.com
        if (hostOnly != null && hostOnly.endsWith(".communication.azure.com")) {
            String emailAccount = hostOnly.substring(0, hostOnly.length() - ".communication.azure.com".length());
            Map<String, String> emailQueryParams = new HashMap<>();
            requestContext.getUriInfo().getQueryParameters().forEach((k, v) -> emailQueryParams.put(k, v.get(0)));
            AzureRequest emailRequest = new AzureRequest(
                requestContext.getMethod(), emailAccount, "email",
                path, headers, requestContext.getEntityStream(), emailQueryParams, null, secure);
            AuthContext emailAuth = authPipeline.resolve(emailRequest);
            emailRequest = new AzureRequest(
                requestContext.getMethod(), emailAccount, "email",
                path, headers, requestContext.getEntityStream(), emailQueryParams, emailAuth, secure);
            Optional<AzureServiceHandler> emailHandler = serviceRegistry.resolve("email");
            if (emailHandler.isPresent()) {
                LOGGER.infof("Dispatching communication.azure.com request to EmailHandler: %s %s (account=%s)",
                    requestContext.getMethod(), path, emailAccount);
                return emailHandler.get().handle(emailRequest);
            }
        }

        // Blob Storage: {account}.blob.core.windows.net
        if (hostOnly != null && hostOnly.endsWith(".blob.core.windows.net")) {
            String blobAccount = hostOnly.substring(0, hostOnly.length() - ".blob.core.windows.net".length());
            Map<String, String> blobQueryParams = new HashMap<>();
            requestContext.getUriInfo().getQueryParameters().forEach((k, v) -> blobQueryParams.put(k, v.get(0)));
            AzureRequest blobRequest = new AzureRequest(
                requestContext.getMethod(), blobAccount, "blob",
                path, headers, requestContext.getEntityStream(), blobQueryParams, null, secure);
            AuthContext blobAuth = authPipeline.resolve(blobRequest);
            blobRequest = new AzureRequest(
                requestContext.getMethod(), blobAccount, "blob",
                path, headers, requestContext.getEntityStream(), blobQueryParams, blobAuth, secure);
            Optional<AzureServiceHandler> blobHandler = serviceRegistry.resolve("blob");
            if (blobHandler.isPresent()) {
                LOGGER.infof("Dispatching blob.core.windows.net request to BlobServiceHandler: %s %s (account=%s)",
                    requestContext.getMethod(), path, blobAccount);
                return blobHandler.get().handle(blobRequest);
            }
        }

        // Queue Storage: {account}.queue.core.windows.net
        if (hostOnly != null && hostOnly.endsWith(".queue.core.windows.net")) {
            String queueAccount = hostOnly.substring(0, hostOnly.length() - ".queue.core.windows.net".length());
            Map<String, String> queueQueryParams = new HashMap<>();
            requestContext.getUriInfo().getQueryParameters().forEach((k, v) -> queueQueryParams.put(k, v.get(0)));
            AzureRequest queueRequest = new AzureRequest(
                requestContext.getMethod(), queueAccount, "queue",
                path, headers, requestContext.getEntityStream(), queueQueryParams, null, secure);
            AuthContext queueAuth = authPipeline.resolve(queueRequest);
            queueRequest = new AzureRequest(
                requestContext.getMethod(), queueAccount, "queue",
                path, headers, requestContext.getEntityStream(), queueQueryParams, queueAuth, secure);
            Optional<AzureServiceHandler> queueHandler = serviceRegistry.resolve("queue");
            if (queueHandler.isPresent()) {
                LOGGER.infof("Dispatching queue.core.windows.net request to QueueServiceHandler: %s %s (account=%s)",
                    requestContext.getMethod(), path, queueAccount);
                return queueHandler.get().handle(queueRequest);
            }
        }

        // Service Bus: {account}.servicebus.windows.net
        if (hostOnly != null && hostOnly.endsWith(".servicebus.windows.net")) {
            String sbAccount = hostOnly.substring(0, hostOnly.length() - ".servicebus.windows.net".length());
            Map<String, String> sbQueryParams = new HashMap<>();
            requestContext.getUriInfo().getQueryParameters().forEach((k, v) -> sbQueryParams.put(k, v.get(0)));
            AzureRequest sbRequest = new AzureRequest(
                requestContext.getMethod(), sbAccount, "servicebus",
                path, headers, requestContext.getEntityStream(), sbQueryParams, null, secure);
            AuthContext sbAuth = authPipeline.resolve(sbRequest);
            sbRequest = new AzureRequest(
                requestContext.getMethod(), sbAccount, "servicebus",
                path, headers, requestContext.getEntityStream(), sbQueryParams, sbAuth, secure);
            Optional<AzureServiceHandler> sbHandler = serviceRegistry.resolve("servicebus");
            if (sbHandler.isPresent()) {
                LOGGER.infof("Dispatching servicebus.windows.net request to ServiceBusHandler: %s %s (account=%s)",
                    requestContext.getMethod(), path, sbAccount);
                return sbHandler.get().handle(sbRequest);
            }
        }

        // Microsoft Entra ID (OpenID Connect provider) — tenant-rooted paths at the ARM base URL:
        //   {tenant}/oauth2/v2.0/token, {tenant}/oauth2/token,
        //   {tenant}/discovery/v2.0/keys,
        //   {tenant}[/v2.0]/.well-known/openid-configuration
        // where {tenant} is a tenant id or common/organizations/consumers.
        if (path.contains("oauth2/v2.0/token") || path.endsWith("oauth2/token")
                || path.endsWith("discovery/v2.0/keys")
                || path.endsWith(".well-known/openid-configuration")) {
            Optional<AzureServiceHandler> entraHandler = serviceRegistry.resolve("entra");
            if (entraHandler.isPresent()) {
                Map<String, String> entraQueryParams = new HashMap<>();
                requestContext.getUriInfo().getQueryParameters().forEach((k, v) -> entraQueryParams.put(k, v.get(0)));
                AzureRequest entraRequest = new AzureRequest(
                    requestContext.getMethod(), "entra", "entra",
                    path, headers, requestContext.getEntityStream(), entraQueryParams, null, secure);
                LOGGER.infof("Dispatching Entra request to EntraServiceHandler: %s %s",
                    requestContext.getMethod(), path);
                return entraHandler.get().handle(entraRequest);
            }
            // Entra disabled: fall through to JAX-RS (these paths 404).
            return null;
        }

        // ARM metadata endpoint — called by go-azure-sdk for environment discovery.
        // Return null so JAX-RS returns 404; the azurerm provider falls back to defaults.
        // Implementing the metadata response causes the provider to detect Azure Stack
        // and reject the configuration with an unsupported-environment error.
        if (path.startsWith("metadata/endpoints")) {
            return null;
        }

        // Microsoft Graph API — called by azurerm provider for service principal discovery
        if (path.startsWith("v1.0/")) {
            return null;
        }

        if (path.startsWith("dataCollectionRules/") || path.startsWith("v1/workspaces/")) {
            Map<String, String> monitorQueryParams = new HashMap<>();
            Map<String, List<String>> monitorQueryParamsMulti = new HashMap<>();
            requestContext.getUriInfo().getQueryParameters().forEach((k, v) -> {
                monitorQueryParams.put(k, v.get(0));
                monitorQueryParamsMulti.put(k, List.copyOf(v));
            });
            AzureRequest monitorRequest = new AzureRequest(
                requestContext.getMethod(), "monitor", "monitor", path, headers,
                requestContext.getEntityStream(), monitorQueryParams, monitorQueryParamsMulti, null, secure);
            AuthContext monitorAuth = authPipeline.resolve(monitorRequest);
            monitorRequest = new AzureRequest(
                requestContext.getMethod(), "monitor", "monitor", path, headers,
                requestContext.getEntityStream(), monitorQueryParams, monitorQueryParamsMulti, monitorAuth, secure);
            Optional<AzureServiceHandler> monitorHandler = serviceRegistry.resolve("monitor");
            if (monitorHandler.isPresent()) {
                LOGGER.infof("Dispatching Monitor request: %s %s", requestContext.getMethod(), path);
                return monitorHandler.get().handle(monitorRequest);
            }
        }

        // Key Vault data-plane paths arriving at the ARM base URL.
        // The azurerm v3 provider (when metadata/endpoints returns 404) sends all key vault
        // data-plane requests directly to the ARM base URL rather than to *.vault.azure.net.
        // Intercept well-known KV API path prefixes and route to KeyVaultHandler with a fixed
        // account name so both the provider and kv_get in BATS tests use the same namespace.
        if (path.startsWith("secrets/") || path.equals("secrets")
                || path.startsWith("certificates/") || path.equals("certificates")
                || path.startsWith("keys/") || path.equals("keys")
                || path.startsWith("deletedsecrets/") || path.startsWith("deletedcertificates/")
                || path.startsWith("deletedkeys/")) {
            String kvAccount = armHandler.getDefaultKvAccount();
            Map<String, String> kvQueryParams = new HashMap<>();
            requestContext.getUriInfo().getQueryParameters().forEach((k, v) -> kvQueryParams.put(k, v.get(0)));
            AzureRequest kvRequest = new AzureRequest(
                requestContext.getMethod(), kvAccount, "keyvault",
                path, headers, requestContext.getEntityStream(), kvQueryParams, null, secure);
            AuthContext kvAuth = authPipeline.resolve(kvRequest);
            kvRequest = new AzureRequest(
                requestContext.getMethod(), kvAccount, "keyvault",
                path, headers, requestContext.getEntityStream(), kvQueryParams, kvAuth, secure);
            Optional<AzureServiceHandler> kvHandler = serviceRegistry.resolve("keyvault");
            if (kvHandler.isPresent()) {
                LOGGER.infof("Routing ARM-base KV path to KeyVaultHandler: %s %s", requestContext.getMethod(), path);
                return kvHandler.get().handle(kvRequest);
            }
        }

        // ---------------------------------------------------------------
        // ACS Email data-plane paths arriving at the ARM base URL.
        // SDKs may POST /emails:send or GET /emails/operations/{id}
        // directly to the configured endpoint rather than via a
        // *.communication.azure.com host header.
        // Also handle /emailMessages inspection endpoints.
        // ---------------------------------------------------------------
        if (path.startsWith("emails:") || path.startsWith("emails/")
                || path.startsWith("emailMessages")) {
            Map<String, String> emailQueryParams = new HashMap<>();
            requestContext.getUriInfo().getQueryParameters().forEach((k, v) -> emailQueryParams.put(k, v.get(0)));
            AzureRequest emailRequest = new AzureRequest(
                requestContext.getMethod(), "default", "email",
                path, headers, requestContext.getEntityStream(), emailQueryParams, null, secure);
            AuthContext emailAuth = authPipeline.resolve(emailRequest);
            emailRequest = new AzureRequest(
                requestContext.getMethod(), "default", "email",
                path, headers, requestContext.getEntityStream(), emailQueryParams, emailAuth, secure);
            Optional<AzureServiceHandler> emailHandler = serviceRegistry.resolve("email");
            if (emailHandler.isPresent()) {
                LOGGER.infof("Routing email path to EmailHandler: %s %s", requestContext.getMethod(), path);
                return emailHandler.get().handle(emailRequest);
            }
        }

        // ---------------------------------------------------------------
        // Azure Kubernetes Service — ARM management-plane paths:
        //   subscriptions/{sub}/[resourceGroups/{rg}/]providers/Microsoft.ContainerService/...
        // ---------------------------------------------------------------
        if (path.startsWith("subscriptions/") && path.contains("/providers/Microsoft.ContainerService/")) {
            Response response = dispatchManagementPlane(requestContext, "aks", path, headers, secure);
            if (response != null) {
                return response;
            }
        }

        // ---------------------------------------------------------------
        // Azure Container Registry — ARM management-plane paths:
        //   subscriptions/{sub}/[resourceGroups/{rg}/]providers/Microsoft.ContainerRegistry/...
        //   subscriptions/{sub}/providers/Microsoft.ContainerRegistry/checkNameAvailability
        // ---------------------------------------------------------------
        if (path.startsWith("subscriptions/") && path.contains("/providers/Microsoft.ContainerRegistry/")) {
            Response response = dispatchManagementPlane(requestContext, "acr", path, headers, secure);
            if (response != null) {
                return response;
            }
        }

        // ---------------------------------------------------------------
        // Azure SQL Database — ARM management-plane paths:
        //   subscriptions/{sub}/[resourceGroups/{rg}/]providers/Microsoft.Sql/...
        //   subscriptions/{sub}/providers/Microsoft.Sql/checkNameAvailability
        // The subscriptionId and resourceGroupName are parsed by SqlHandler;
        // here we just detect the ARM prefix and dispatch the full path.
        // ---------------------------------------------------------------
        if (path.startsWith("subscriptions/") && path.contains("/providers/Microsoft.Sql/")) {
            Response response = dispatchManagementPlane(requestContext, "sql", path, headers, secure);
            if (response != null) {
                return response;
            }
        }

        // ---------------------------------------------------------------
        // Azure Database for PostgreSQL (Flexible Server) — ARM management-plane paths:
        //   subscriptions/{sub}/[resourceGroups/{rg}/]providers/Microsoft.DBforPostgreSQL/flexibleServers/...
        //   subscriptions/{sub}/providers/Microsoft.DBforPostgreSQL/checkNameAvailability
        // The api-version arrives only as a query param and is ignored here; routing is by namespace.
        // ---------------------------------------------------------------
        if (path.startsWith("subscriptions/") && path.contains("/providers/Microsoft.DBforPostgreSQL/")) {
            Map<String, String> pgQueryParams = new HashMap<>();
            requestContext.getUriInfo().getQueryParameters().forEach((k, v) -> pgQueryParams.put(k, v.get(0)));
            AzureRequest pgRequest = new AzureRequest(
                requestContext.getMethod(), "postgres", "postgres", path, headers,
                requestContext.getEntityStream(), pgQueryParams, null, secure);
            AuthContext pgAuth = authPipeline.resolve(pgRequest);
            pgRequest = new AzureRequest(
                requestContext.getMethod(), "postgres", "postgres", path, headers,
                requestContext.getEntityStream(), pgQueryParams, pgAuth, secure);
            Optional<AzureServiceHandler> pgHandler = serviceRegistry.resolve("postgres");
            if (pgHandler.isPresent()) {
                LOGGER.infof("Dispatching ARM PostgreSQL request to PostgresHandler: %s %s", requestContext.getMethod(), path);
                return pgHandler.get().handle(pgRequest);
            }
        }

        // ---------------------------------------------------------------
        // Azure Virtual Machines — ARM management-plane paths:
        //   subscriptions/{sub}/[resourceGroups/{rg}/]providers/Microsoft.Compute/virtualMachines/...
        //   subscriptions/{sub}/providers/Microsoft.Compute/locations/{loc}/operations/{opId}
        // ---------------------------------------------------------------
        if (path.startsWith("subscriptions/") && path.contains("/providers/Microsoft.Compute/")) {
            Response response = dispatchManagementPlane(requestContext, "vm", path, headers, secure);
            if (response != null) {
                return response;
            }
        }

        // ---------------------------------------------------------------
        // Azure Cache for Redis — ARM management-plane paths:
        //   subscriptions/{sub}/[resourceGroups/{rg}/]providers/Microsoft.Cache/redis/...
        // ---------------------------------------------------------------
        if (path.startsWith("subscriptions/") && path.contains("/providers/Microsoft.Cache/")) {
            Response response = dispatchManagementPlane(requestContext, "redis", path, headers, secure);
            if (response != null) {
                return response;
            }
        }

        // ---------------------------------------------------------------
        // Azure Communication Services (Email) — ARM management-plane paths:
        //   subscriptions/{sub}/[resourceGroups/{rg}/]providers/Microsoft.Communication/...
        // ---------------------------------------------------------------
        if (path.startsWith("subscriptions/") && path.contains("/providers/Microsoft.Communication/")) {
            Response response = dispatchManagementPlane(requestContext, "email", path, headers, secure);
            if (response != null) {
                return response;
            }
        }

        // ---------------------------------------------------------------
        // Azure Event Grid — ARM management-plane paths:
        //   subscriptions/{sub}/[resourceGroups/{rg}/]providers/Microsoft.EventGrid/topics/...
        //   {topicScope}/providers/Microsoft.EventGrid/eventSubscriptions/{name}
        // ---------------------------------------------------------------
        if (path.startsWith("subscriptions/") && path.contains("/providers/Microsoft.EventGrid/")) {
            Response response = dispatchManagementPlane(requestContext, "eventgrid", path, headers, secure);
            if (response != null) {
                return response;
            }
        }

        // ---------------------------------------------------------------
        // ARM general management-plane paths: subscriptions/{sub}/...
        // (resource groups, storage accounts, key vaults, etc. not served
        //  by the more-specific AKS and SQL handlers above)
        // ---------------------------------------------------------------
        if (path.startsWith("subscriptions/") || path.equals("subscriptions")
                || path.startsWith("subscriptions?")
                || path.startsWith("tenants/") || path.equals("tenants")
                || path.startsWith("tenants?")) {
            Map<String, String> armQueryParams = new HashMap<>();
            requestContext.getUriInfo().getQueryParameters().forEach((k, v) -> armQueryParams.put(k, v.get(0)));
            AzureRequest armRequest = new AzureRequest(
                requestContext.getMethod(), "arm", "arm", path, headers,
                requestContext.getEntityStream(), armQueryParams, null, secure);
            AuthContext armAuth = authPipeline.resolve(armRequest);
            armRequest = new AzureRequest(
                requestContext.getMethod(), "arm", "arm", path, headers,
                requestContext.getEntityStream(), armQueryParams, armAuth, secure);
            Optional<AzureServiceHandler> armHandler = serviceRegistry.resolve("arm");
            if (armHandler.isPresent()) {
                LOGGER.infof("Dispatching ARM request to ArmHandler: %s %s", requestContext.getMethod(), path);
                return armHandler.get().handle(armRequest);
            }
        }

        String[] parts = path.split("/", 2);
        String firstSegment = (parts.length > 0) ? parts[0] : "";
        boolean hasServiceBusSuffix = firstSegment.endsWith("-servicebus");

        // Service Bus root-level spec paths (like $namespaceinfo or $Resources/...) or AtomPub XML requests
        String contentType = requestContext.getHeaderString(HttpHeaders.CONTENT_TYPE);
        String accept = requestContext.getHeaderString(HttpHeaders.ACCEPT);
        boolean isServiceBusRequest = !hasServiceBusSuffix && (
                (contentType != null && contentType.contains("application/atom+xml"))
                || (accept != null && accept.contains("application/atom+xml"))
                || path.startsWith("$namespaceinfo")
                || path.startsWith("$Resources")
        );

        if (isServiceBusRequest) {
            String defaultAccount = "devstoreaccount1";
            Map<String, String> queryParams = new HashMap<>();
            requestContext.getUriInfo().getQueryParameters().forEach((k, v) -> queryParams.put(k, v.get(0)));
            AzureRequest azureRequest = new AzureRequest(
                requestContext.getMethod(), defaultAccount, "servicebus",
                path, headers, requestContext.getEntityStream(), queryParams, null, secure);
            AuthContext authContext = authPipeline.resolve(azureRequest);
            azureRequest = new AzureRequest(
                requestContext.getMethod(), defaultAccount, "servicebus",
                path, headers, requestContext.getEntityStream(), queryParams, authContext, secure);
            Optional<AzureServiceHandler> handler = serviceRegistry.resolve("servicebus");
            if (handler.isPresent()) {
                LOGGER.infof("Routing Service Bus AtomPub/spec request: %s %s → account=%s",
                    requestContext.getMethod(), path, defaultAccount);
                return handler.get().handle(azureRequest);
            }
        }

        // ---------------------------------------------------------------
        // Java Cosmos SDK compatibility: the SDK ignores the path component
        // of the configured endpoint and sends requests to the server root.
        // Route empty paths (DatabaseAccount GET) and known Cosmos segments
        // (dbs, colls, docs, …) to the Cosmos handler using the default
        // account so the Java SDK can operate without path-based routing.
        // ---------------------------------------------------------------
        if (firstSegment.isEmpty() || COSMOS_ROOT_SEGMENTS.contains(firstSegment)) {
            String defaultAccount = "devstoreaccount1";
            String resourcePath   = path; // e.g. "" | "dbs" | "dbs/mydb/colls/items"
            LOGGER.infof("Java-SDK cosmos root route: %s %s → account=%s resourcePath=%s",
                requestContext.getMethod(), path, defaultAccount, resourcePath);

            Map<String, String> queryParams = new HashMap<>();
            requestContext.getUriInfo().getQueryParameters().forEach((k, v) -> queryParams.put(k, v.get(0)));
            AzureRequest azureRequest = new AzureRequest(
                requestContext.getMethod(), defaultAccount, "cosmos",
                resourcePath, headers, requestContext.getEntityStream(),
                queryParams, null, secure);
            AuthContext authContext = authPipeline.resolve(azureRequest);
            azureRequest = new AzureRequest(
                requestContext.getMethod(), defaultAccount, "cosmos",
                resourcePath, headers, requestContext.getEntityStream(),
                queryParams, authContext, secure);
            Optional<AzureServiceHandler> handler = serviceRegistry.resolve("cosmos");
            if (handler.isPresent()) {
                LOGGER.infof("Dispatching to handler: %s", handler.get().getClass().getSimpleName());
                return handler.get().handle(azureRequest);
            }
        }

        if (parts.length < 1 || parts[0].isEmpty()) {
            return null;
        }

        String accountName = parts[0];
        String resourcePath = parts.length > 1 ? parts[1] : "";

        // NOTE: longer/more-specific suffixes must be checked before shorter ones that are
        // a suffix of them (e.g. "-cosmos-table" before "-table", "-cosmos-mongo" before "-cosmos").
        String serviceType = "blob";
        if (accountName.endsWith("-cosmos-mongo")) {
            serviceType = "cosmos-mongo";
            accountName = accountName.substring(0, accountName.length() - 13);
        } else if (accountName.endsWith("-cosmos-table")) {
            serviceType = "cosmos-table";
            accountName = accountName.substring(0, accountName.length() - 13);
        } else if (accountName.endsWith("-cosmos-cassandra")) {
            serviceType = "cosmos-cassandra";
            accountName = accountName.substring(0, accountName.length() - 17);
        } else if (accountName.endsWith("-cosmos-gremlin")) {
            serviceType = "cosmos-gremlin";
            accountName = accountName.substring(0, accountName.length() - 15);
        } else if (accountName.endsWith("-cosmos-postgresql")) {
            serviceType = "cosmos-postgresql";
            accountName = accountName.substring(0, accountName.length() - 18);
        } else if (accountName.endsWith("-cosmos-nosql")) {
            serviceType = "cosmos-nosql";
            accountName = accountName.substring(0, accountName.length() - 13);
        } else if (accountName.endsWith("-cosmos")) {
            serviceType = "cosmos";
            accountName = accountName.substring(0, accountName.length() - 7);
        } else if (accountName.endsWith("-queue")) {
            serviceType = "queue";
            accountName = accountName.substring(0, accountName.length() - 6);
        } else if (accountName.endsWith("-table")) {
            serviceType = "table";
            accountName = accountName.substring(0, accountName.length() - 6);
        } else if (accountName.endsWith("-functions")) {
            serviceType = "functions";
            accountName = accountName.substring(0, accountName.length() - 10);
        } else if (accountName.endsWith("-appconfig")) {
            serviceType = "appconfig";
            accountName = accountName.substring(0, accountName.length() - 10);
        } else if (accountName.endsWith("-keyvault")) {
            serviceType = "keyvault";
            accountName = accountName.substring(0, accountName.length() - 9);
        } else if (accountName.endsWith("-eventgrid")) {
            serviceType = "eventgrid";
            accountName = accountName.substring(0, accountName.length() - 10);
        } else if (accountName.endsWith("-eventhub")) {
            serviceType = "eventhub";
            accountName = accountName.substring(0, accountName.length() - 9);
        } else if (accountName.endsWith("-sql")) {
            serviceType = "sql";
            accountName = accountName.substring(0, accountName.length() - 4);
        } else if (accountName.endsWith("-postgres")) {
            serviceType = "postgres";
            accountName = accountName.substring(0, accountName.length() - 9);
        } else if (accountName.endsWith("-servicebus")) {
            serviceType = "servicebus";
            accountName = accountName.substring(0, accountName.length() - 11);
        } else if (accountName.endsWith("-apim")) {
            serviceType = "apim";
            accountName = accountName.substring(0, accountName.length() - 5);
        } else if (accountName.endsWith("-email")) {
            serviceType = "email";
            accountName = accountName.substring(0, accountName.length() - 6);
        } else {
            serviceType = resolveServiceType(requestContext, resourcePath);
        }
        LOGGER.infof("Resolved accountName: %s, serviceType: %s, resourcePath: %s", accountName, serviceType, resourcePath);
        Map<String, String> queryParams = new HashMap<>();
        Map<String, List<String>> queryParamsMulti = new HashMap<>();
        requestContext.getUriInfo().getQueryParameters().forEach((k, v) -> {
            queryParams.put(k, v.get(0));
            queryParamsMulti.put(k, List.copyOf(v));
        });

        AzureRequest azureRequest = new AzureRequest(
            requestContext.getMethod(),
            accountName,
            serviceType,
            resourcePath,
            headers,
            requestContext.getEntityStream(),
            queryParams,
            queryParamsMulti,
            null,
            secure
        );

        AuthContext authContext = authPipeline.resolve(azureRequest);
        azureRequest = new AzureRequest(
            requestContext.getMethod(),
            accountName,
            serviceType,
            resourcePath,
            headers,
            requestContext.getEntityStream(),
            queryParams,
            queryParamsMulti,
            authContext,
            secure
        );

        if (serviceRegistry.isKnown(serviceType) && !serviceRegistry.isEnabled(serviceType)) {
            LOGGER.warnf("Service disabled: %s", serviceType);
            return new AzureErrorResponse("ServiceDisabled",
                    "The " + serviceType + " service is disabled on this emulator.")
                    .toXmlResponse(Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
        }

        Optional<AzureServiceHandler> handler = serviceRegistry.resolve(serviceType);
        if (handler.isPresent()) {
            LOGGER.infof("Dispatching to handler: %s", handler.get().getClass().getSimpleName());
            return handler.get().handle(azureRequest);
        }

        LOGGER.warnf("No handler found for serviceType: %s", serviceType);
        return new AzureErrorResponse("ServiceNotImplemented", "The specified service is not implemented.")
                .toXmlResponse(Response.Status.NOT_IMPLEMENTED.getStatusCode());
    }

    /**
     * Builds an {@link AzureRequest} for a path-routed management-plane call (account name and
     * service type are both {@code serviceType}), resolves auth, and dispatches it to the matching
     * handler. Returns {@code null} when no handler is registered or the service is disabled, so the
     * caller can fall through to the next route.
     */
    private Response dispatchManagementPlane(ContainerRequestContext requestContext, String serviceType,
                                             String path, HttpHeaders headers, boolean secure) {
        Map<String, String> queryParams = new HashMap<>();
        requestContext.getUriInfo().getQueryParameters().forEach((k, v) -> queryParams.put(k, v.get(0)));
        InputStream entityStream = requestContext.getEntityStream();

        AzureRequest request = new AzureRequest(
            requestContext.getMethod(), serviceType, serviceType, path, headers,
            entityStream, queryParams, null, secure);
        AuthContext auth = authPipeline.resolve(request);
        request = new AzureRequest(
            requestContext.getMethod(), serviceType, serviceType, path, headers,
            entityStream, queryParams, auth, secure);

        Optional<AzureServiceHandler> handler = serviceRegistry.resolve(serviceType);
        if (handler.isPresent()) {
            LOGGER.infof("Dispatching ARM %s request to %s: %s %s", serviceType,
                handler.get().getClass().getSimpleName(), requestContext.getMethod(), path);
            return handler.get().handle(request);
        }
        return null;
    }

    private String resolveServiceType(ContainerRequestContext requestContext, String resourcePath) {
        String blobType = requestContext.getHeaderString("x-ms-blob-type");
        if (blobType != null) return "blob";

        if (resourcePath.contains("/messages") || resourcePath.endsWith("/messages")) {
            return "queue";
        }

        Map<String, List<String>> queryParams = requestContext.getUriInfo().getQueryParameters();
        List<String> restype = queryParams.get("restype");
        if (restype != null && restype.contains("queue")) {
            return "queue";
        }

        String queueMessageCount = requestContext.getHeaderString("x-ms-queue-message-count");
        if (queueMessageCount != null) return "queue";

        return "blob";
    }
}
