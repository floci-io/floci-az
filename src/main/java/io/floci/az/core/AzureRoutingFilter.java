package io.floci.az.core;

import io.floci.az.core.auth.AuthPipeline;
import io.floci.az.services.arm.ArmHandler;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import io.floci.az.core.ServiceRoutes.SuffixRoute;

@ApplicationScoped
public class AzureRoutingFilter {

    private static final Logger LOGGER = Logger.getLogger(AzureRoutingFilter.class);

    private final AuthPipeline authPipeline;
    private final AzureServiceRegistry serviceRegistry;
    private final ArmHandler armHandler;
    private final Vertx vertx;

    /**
     * The routing chain, in priority order. Each stage either handles the request, hands it to
     * JAX-RS, or declines so the next stage can try. Order is behaviour: the ARM stages must run
     * after the more specific provider stages, and the account-suffix terminal runs only once every
     * stage has declined.
     */
    private final List<Function<RoutingContext, Outcome>> stages;

    @Inject
    public AzureRoutingFilter(AuthPipeline authPipeline, AzureServiceRegistry serviceRegistry,
            ArmHandler armHandler, Vertx vertx) {
        this.authPipeline = authPipeline;
        this.serviceRegistry = serviceRegistry;
        this.armHandler = armHandler;
        this.vertx = vertx;
        this.stages = List.of(
            this::routeByHostSuffix,
            this::routeImds,
            this::routeEntra,
            this::routeArmMetadataEndpoints,
            this::routeMicrosoftGraph,
            this::routeMonitor,
            this::routeKeyVaultAtArmBase,
            this::routeEmailAtArmBase,
            this::routeArmProviders,
            this::routeArmGeneric,
            this::routeServiceBusAtomPub,
            this::routeCosmosRoot
        );
    }

    // ── Routing outcome ─────────────────────────────────────────────────────────

    /**
     * What a routing stage decided. Replaces the overloaded {@code null} return of the original
     * filter, where "no route matched, keep going" and "matched, but deliberately let JAX-RS answer"
     * were the same value.
     */
    private sealed interface Outcome permits Handled, Fallthrough {}

    /** A handler produced a response; routing stops here. */
    private record Handled(Response response) implements Outcome {}

    private enum Fallthrough implements Outcome {
        /** Decline: try the next routing stage. */
        TO_NEXT_STAGE,
        /** Stop routing and let JAX-RS answer (normally a 404). */
        TO_JAX_RS
    }

    /** Everything a routing stage needs, captured once per request. */
    private record RoutingContext(
        ContainerRequestContext requestContext,
        String path,
        HttpHeaders headers,
        String host,
        boolean secure
    ) {
        String method() {
            return requestContext.getMethod();
        }

        /** First path segment ({@code ""} for a root request). */
        String firstSegment() {
            int slash = path.indexOf('/');
            return slash < 0 ? path : path.substring(0, slash);
        }

        /** Everything after the first path segment, without the separating slash. */
        String resourcePath() {
            int slash = path.indexOf('/');
            return slash < 0 ? "" : path.substring(slash + 1);
        }
    }

    // ── Routing tables ──────────────────────────────────────────────────────────

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
    private static final Set<String> COSMOS_ROOT_SEGMENTS = Set.of(
        "dbs", "colls", "docs", "pkranges", "offers", "sprocs", "triggers", "udfs"
    );

    /** Well-known Key Vault data-plane path prefixes, as sent to the ARM base URL by azurerm v3. */
    private static final Set<String> KEY_VAULT_COLLECTIONS = Set.of(
        "secrets", "certificates", "keys", "deletedsecrets", "deletedcertificates", "deletedkeys"
    );

    /**
     * A resolved ARM provider route: the pre-built {@code /providers/<namespace>/} marker, the service
     * type of the handler that claimed it, and the handler's guard.
     */
    private record ResolvedProvider(String marker, String serviceType, Predicate<String> guard) {}

    // ── Routing tables, assembled from the handlers at startup ──────────────────

    /** Host-suffix → serviceType. Suffixes are mutually exclusive; DFS maps to blob. */
    private volatile List<SuffixRoute> hostRoutes = List.of();

    /**
     * Account-name suffix → serviceType (e.g. {@code {account}-queue → queue}), sorted longest-first.
     * Any suffix that is a string-suffix of another is always shorter, so length-descending gives the
     * required most-specific-first match ({@code -cosmos-table} must beat {@code -table}).
     */
    private volatile List<SuffixRoute> accountSuffixRoutes = List.of();

    /**
     * ARM provider routes, guarded routes first. Managed Identity is the only guarded route, and it
     * must be tried before the broader providers: an {@code identities/default} scope may itself be a
     * Compute/ContainerService resource, and Managed Identity's {@code /providers/} segment is always
     * the last one. Handler discovery order is arbitrary, so guarded-first is what makes this
     * deterministic; the namespace tiebreak keeps the rest stable.
     */
    private volatile List<ResolvedProvider> providerRoutes = List.of();

    /** Service types dispatched by a stage directly rather than named in a handler's routing table. */
    private static final Set<String> LITERAL_ROUTE_SERVICE_TYPES = Set.of(
        "managedidentity", "entra", "monitor", "keyvault", "email", "arm", "servicebus", "cosmos",
        "blob", "queue" // the storage fallback in resolveStorageServiceType
    );

    /**
     * Assembles the dispatch tables from every registered handler's {@link ServiceRoutes}. Two handlers
     * claiming the same host or account suffix is a wiring bug that would otherwise resolve by
     * discovery order — fail fast at startup instead.
     */
    @PostConstruct
    void buildRoutingTables() {
        List<SuffixRoute> hosts = new ArrayList<>();
        List<SuffixRoute> accounts = new ArrayList<>();
        List<ResolvedProvider> providers = new ArrayList<>();

        for (AzureServiceHandler handler : serviceRegistry.handlers()) {
            ServiceRoutes routes = handler.routes();
            for (String hostSuffix : routes.hostSuffixes()) {
                hosts.add(new SuffixRoute(hostSuffix, handler.getServiceType()));
            }
            accounts.addAll(routes.accountSuffixes());
            for (ServiceRoutes.ProviderRoute provider : routes.providers()) {
                providers.add(new ResolvedProvider(provider.marker(), handler.getServiceType(), provider.guard()));
            }
        }

        rejectDuplicateSuffixes("host", hosts);
        rejectDuplicateSuffixes("account", accounts);

        hosts.sort(LONGEST_SUFFIX_FIRST);
        accounts.sort(LONGEST_SUFFIX_FIRST);
        providers.sort(GUARDED_FIRST_THEN_MARKER);

        this.hostRoutes = List.copyOf(hosts);
        this.accountSuffixRoutes = List.copyOf(accounts);
        this.providerRoutes = List.copyOf(providers);

        LOGGER.infof("Routing tables: %d host, %d account-suffix, %d ARM provider routes",
            hostRoutes.size(), accountSuffixRoutes.size(), providerRoutes.size());
    }

    private static final Comparator<SuffixRoute> LONGEST_SUFFIX_FIRST =
        Comparator.comparingInt((SuffixRoute route) -> route.suffix().length()).reversed();

    private static final Comparator<ResolvedProvider> GUARDED_FIRST_THEN_MARKER =
        Comparator.comparing((ResolvedProvider route) -> route.guard() == ServiceRoutes.ProviderRoute.ALWAYS)
                  .thenComparing(ResolvedProvider::marker);

    static void rejectDuplicateSuffixes(String kind, List<SuffixRoute> routes) {
        Map<String, String> claimedBy = new HashMap<>();
        for (SuffixRoute route : routes) {
            String previous = claimedBy.putIfAbsent(route.suffix(), route.serviceType());
            if (previous != null) {
                throw new IllegalStateException("Two handlers claim the same " + kind + " suffix '"
                    + route.suffix() + "': " + previous + " and " + route.serviceType());
            }
        }
    }

    /** Returns the route whose suffix {@code value} ends with, or {@code null} if none matches. */
    private static SuffixRoute matchSuffix(List<SuffixRoute> routes, String value) {
        for (SuffixRoute route : routes) {
            if (value.endsWith(route.suffix())) {
                return route;
            }
        }
        return null;
    }

    private static String stripSuffix(String value, SuffixRoute route) {
        return value.substring(0, value.length() - route.suffix().length());
    }

    // ── Table accessors (package-private, for the equivalence/drift tests) ──────

    List<SuffixRoute> hostRoutes() {
        return hostRoutes;
    }

    List<SuffixRoute> accountSuffixRoutes() {
        return accountSuffixRoutes;
    }

    /** ARM provider routes as {@code marker → serviceType}, in match order. */
    List<Map.Entry<String, String>> providerRoutesInMatchOrder() {
        return providerRoutes.stream()
                .map(route -> Map.entry(route.marker(), route.serviceType()))
                .toList();
    }

    /**
     * Every service type the routing tables and stages can produce. Exposed for the drift test that
     * asserts each one actually resolves to a registered handler.
     */
    Set<String> routedServiceTypes() {
        Set<String> types = new java.util.TreeSet<>(LITERAL_ROUTE_SERVICE_TYPES);
        hostRoutes.forEach(route -> types.add(route.serviceType()));
        accountSuffixRoutes.forEach(route -> types.add(route.serviceType()));
        providerRoutes.forEach(route -> types.add(route.serviceType()));
        return types;
    }

    // ── Filter entry point ──────────────────────────────────────────────────────

    @ServerRequestFilter(preMatching = true)
    public Uni<Response> filter(ContainerRequestContext requestContext, @Context HttpHeaders httpHeaders) {
        // Capture context before switching threads
        String path0 = requestContext.getUriInfo().getPath();
        HttpHeaders headers = httpHeaders;
        // Capture Host header now (JAX-RS request scope may not propagate to the blocking thread).
        // Try both canonical and lowercase forms since HTTP header names are case-insensitive.
        String h = requestContext.getHeaders().getFirst("Host");
        if (h == null) {
            h = requestContext.getHeaders().getFirst("host");
        }
        final String capturedHost = h;

        return Uni.createFrom().completionStage(
            vertx.executeBlocking(() -> doFilter(requestContext, path0, headers, capturedHost))
                 .toCompletionStage()
        );
    }

    private Response doFilter(ContainerRequestContext requestContext, String rawPath, HttpHeaders headers,
                              String capturedHost) {
        String path = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;

        if (isEmulatorAdminPath(path)) {
            return null;
        }

        LOGGER.infof("Incoming request: %s %s", requestContext.getMethod(), path);

        RoutingContext ctx = new RoutingContext(requestContext, path, headers, hostWithoutPort(capturedHost),
            requestContext.getSecurityContext().isSecure());

        for (Function<RoutingContext, Outcome> stage : stages) {
            Outcome outcome = stage.apply(ctx);
            if (outcome instanceof Handled handled) {
                return handled.response();
            }
            if (outcome == Fallthrough.TO_JAX_RS) {
                return null;
            }
        }

        return dispatchByAccountSuffix(ctx);
    }

    /** Health and admin endpoints bypass routing entirely. */
    private static boolean isEmulatorAdminPath(String path) {
        return path.equals("health") || path.equals("ready")
            || path.startsWith("_floci/") || path.startsWith("_admin");
    }

    private static String hostWithoutPort(String capturedHost) {
        if (capturedHost == null) {
            return null;
        }
        int colon = capturedHost.indexOf(':');
        return colon < 0 ? capturedHost : capturedHost.substring(0, colon);
    }

    // ── Routing stages, in chain order ──────────────────────────────────────────

    /**
     * Host-based data-plane routing: {@code {account}.<host-suffix>} → serviceType. The host header is
     * extracted in {@link #filter} before {@code executeBlocking} because the JAX-RS request scope may
     * not propagate to the Vert.x blocking thread.
     */
    private Outcome routeByHostSuffix(RoutingContext ctx) {
        if (ctx.host() == null) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        SuffixRoute route = matchSuffix(hostRoutes, ctx.host());
        if (route == null) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        // Suffixes are mutually exclusive: on an absent handler, fall through rather than retry.
        return dispatchOrServiceDisabled(ctx, stripSuffix(ctx.host(), route), route.serviceType(), ctx.path());
    }

    /**
     * IMDS (Instance Metadata Service) — managed identity token endpoint:
     * {@code metadata/identity/oauth2/token?resource=...} (header {@code Metadata: true}).
     *
     * <p>Reached by azure-identity ManagedIdentityCredential when {@code AZURE_POD_IDENTITY_AUTHORITY_HOST}
     * points at the emulator. Must precede {@link #routeEntra} (the path also ends with
     * {@code oauth2/token}). Skips the auth pipeline: IMDS requests carry no Authorization header.
     */
    private Outcome routeImds(RoutingContext ctx) {
        if (!ctx.path().startsWith("metadata/identity/")) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        // Managed Identity disabled: fall through to JAX-RS (404) rather than misrouting the path.
        return dispatchWithoutAuth(ctx, "managedidentity", "IMDS");
    }

    /**
     * Microsoft Entra ID (OpenID Connect provider) — tenant-rooted paths at the ARM base URL:
     * {@code {tenant}/oauth2/v2.0/token}, {@code {tenant}/oauth2/token},
     * {@code {tenant}/discovery/v2.0/keys}, {@code {tenant}[/v2.0]/.well-known/openid-configuration},
     * where {@code {tenant}} is a tenant id or common/organizations/consumers.
     */
    private Outcome routeEntra(RoutingContext ctx) {
        String path = ctx.path();
        boolean isEntraPath = path.contains("oauth2/v2.0/token") || path.endsWith("oauth2/token")
            || path.endsWith("discovery/v2.0/keys")
            || path.endsWith(".well-known/openid-configuration");
        if (!isEntraPath) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        // Entra disabled: fall through to JAX-RS (these paths 404).
        return dispatchWithoutAuth(ctx, "entra", "Entra");
    }

    /**
     * ARM metadata endpoint — called by go-azure-sdk for environment discovery. Hand it to JAX-RS so it
     * 404s and the azurerm provider falls back to defaults. Implementing the metadata response causes
     * the provider to detect Azure Stack and reject the configuration as an unsupported environment.
     */
    private Outcome routeArmMetadataEndpoints(RoutingContext ctx) {
        return ctx.path().startsWith("metadata/endpoints") ? Fallthrough.TO_JAX_RS : Fallthrough.TO_NEXT_STAGE;
    }

    /** Microsoft Graph API — called by the azurerm provider for service principal discovery. */
    private Outcome routeMicrosoftGraph(RoutingContext ctx) {
        return ctx.path().startsWith("v1.0/") ? Fallthrough.TO_JAX_RS : Fallthrough.TO_NEXT_STAGE;
    }

    private Outcome routeMonitor(RoutingContext ctx) {
        if (!ctx.path().startsWith("dataCollectionRules/") && !ctx.path().startsWith("v1/workspaces/")) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        return dispatchOrServiceDisabled(ctx, "monitor", "monitor", ctx.path());
    }

    /**
     * Key Vault data-plane paths arriving at the ARM base URL. The azurerm v3 provider (when
     * {@code metadata/endpoints} returns 404) sends all key vault data-plane requests directly to the
     * ARM base URL rather than to {@code *.vault.azure.net}. Route them to the Key Vault handler with a
     * fixed account name so both the provider and {@code kv_get} in the BATS tests share a namespace.
     */
    private Outcome routeKeyVaultAtArmBase(RoutingContext ctx) {
        if (!KEY_VAULT_COLLECTIONS.contains(ctx.firstSegment())) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        return dispatchOrServiceDisabled(ctx, armHandler.getDefaultKvAccount(), "keyvault", ctx.path());
    }

    /**
     * ACS Email data-plane paths arriving at the ARM base URL. SDKs may POST {@code /emails:send} or GET
     * {@code /emails/operations/{id}} directly to the configured endpoint rather than via a
     * {@code *.communication.azure.com} host header. Also handles {@code /emailMessages} inspection.
     */
    private Outcome routeEmailAtArmBase(RoutingContext ctx) {
        String path = ctx.path();
        if (!path.startsWith("emails:") && !path.startsWith("emails/") && !path.startsWith("emailMessages")) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        return dispatchOrServiceDisabled(ctx, "default", "email", path);
    }

    /**
     * ARM management-plane provider routing: {@code subscriptions/{sub}/.../providers/Microsoft.X/...}.
     * Iterated in {@link #providerRoutes} order (guarded first): Managed Identity is guarded so its provider
     * segment must be the last {@code /providers/} in the path — identity-scoped children (role
     * assignments, locks, ...) have a later segment and fall through to {@link #routeArmGeneric}. All of
     * these must precede the generic ARM stage, which would otherwise swallow every path.
     */
    private Outcome routeArmProviders(RoutingContext ctx) {
        if (!ctx.path().startsWith("subscriptions/")) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        for (ResolvedProvider route : providerRoutes) {
            if (!ctx.path().contains(route.marker()) || !route.guard().test(ctx.path())) {
                continue;
            }
            // A disabled provider service declines, letting a later provider (or generic ARM) claim it.
            Outcome outcome = dispatch(ctx, route.serviceType(), route.serviceType(), ctx.path());
            if (outcome instanceof Handled) {
                return outcome;
            }
        }
        return Fallthrough.TO_NEXT_STAGE;
    }

    /**
     * ARM general management-plane paths ({@code subscriptions/{sub}/...}, {@code tenants/...}):
     * resource groups, storage accounts, key vaults, etc. that the more specific provider stages above
     * did not claim.
     */
    private Outcome routeArmGeneric(RoutingContext ctx) {
        String path = ctx.path();
        boolean isArmPath = path.startsWith("subscriptions/") || path.equals("subscriptions")
            || path.startsWith("tenants/") || path.equals("tenants");
        if (!isArmPath) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        return dispatchOrServiceDisabled(ctx, "arm", "arm", path);
    }

    /** Service Bus root-level spec paths (e.g. {@code $namespaceinfo}, {@code $Resources/...}) or AtomPub XML. */
    private Outcome routeServiceBusAtomPub(RoutingContext ctx) {
        if (ctx.firstSegment().endsWith("-servicebus")) {
            return Fallthrough.TO_NEXT_STAGE; // account-suffix routing owns it
        }
        ContainerRequestContext rc = ctx.requestContext();
        String contentType = rc.getHeaderString(HttpHeaders.CONTENT_TYPE);
        String accept = rc.getHeaderString(HttpHeaders.ACCEPT);
        boolean isServiceBusRequest = (contentType != null && contentType.contains("application/atom+xml"))
            || (accept != null && accept.contains("application/atom+xml"))
            || ctx.path().startsWith("$namespaceinfo")
            || ctx.path().startsWith("$Resources");
        if (!isServiceBusRequest) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        return dispatchOrServiceDisabled(ctx, "devstoreaccount1", "servicebus", ctx.path());
    }

    /**
     * Java Cosmos SDK compatibility: the SDK ignores the path component of the configured endpoint and
     * sends requests to the server root. Route empty paths (DatabaseAccount GET) and known Cosmos
     * segments (dbs, colls, docs, …) to the Cosmos handler using the default account so the Java SDK can
     * operate without path-based routing.
     */
    private Outcome routeCosmosRoot(RoutingContext ctx) {
        String firstSegment = ctx.firstSegment();
        if (!firstSegment.isEmpty() && !COSMOS_ROOT_SEGMENTS.contains(firstSegment)) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        LOGGER.infof("Java-SDK cosmos root route: %s %s", ctx.method(), ctx.path());
        Outcome outcome = dispatch(ctx, "devstoreaccount1", "cosmos", ctx.path());
        if (outcome instanceof Handled) {
            return outcome;
        }
        // A root request with no first segment cannot be resolved by the account-suffix terminal.
        return firstSegment.isEmpty() ? Fallthrough.TO_JAX_RS : Fallthrough.TO_NEXT_STAGE;
    }

    // ── Terminal: account-name routing ──────────────────────────────────────────

    /**
     * Terminal stage: {@code /{account}[-suffix]/{resourcePath}}. Unlike the stages above, an unknown or
     * disabled service produces an Azure error response here rather than declining — this is the last
     * chance to answer, so silence would surface as a bare 404.
     */
    private Response dispatchByAccountSuffix(RoutingContext ctx) {
        String accountName = ctx.firstSegment();
        if (accountName.isEmpty()) {
            return null;
        }
        String resourcePath = ctx.resourcePath();

        String serviceType;
        SuffixRoute route = matchSuffix(accountSuffixRoutes, accountName);
        if (route != null) {
            serviceType = route.serviceType();
            accountName = stripSuffix(accountName, route);
        } else {
            serviceType = resolveStorageServiceType(ctx.requestContext(), resourcePath);
        }

        LOGGER.infof("Resolved accountName: %s, serviceType: %s, resourcePath: %s",
            accountName, serviceType, resourcePath);

        if (serviceRegistry.isKnown(serviceType) && !serviceRegistry.isEnabled(serviceType)) {
            LOGGER.warnf("Service disabled: %s", serviceType);
            return serviceDisabledResponse(serviceType);
        }

        Optional<AzureServiceHandler> handler = serviceRegistry.resolve(serviceType);
        if (handler.isEmpty()) {
            LOGGER.warnf("No handler found for serviceType: %s", serviceType);
            return new AzureErrorResponse("ServiceNotImplemented", "The specified service is not implemented.")
                    .toXmlResponse(Response.Status.NOT_IMPLEMENTED.getStatusCode());
        }

        LOGGER.infof("Dispatching to handler: %s", handler.get().getClass().getSimpleName());
        return handler.get().handle(buildRequest(ctx, accountName, serviceType, resourcePath));
    }

    /** Distinguishes blob from queue when the account name carries no service suffix. */
    private String resolveStorageServiceType(ContainerRequestContext requestContext, String resourcePath) {
        if (requestContext.getHeaderString("x-ms-blob-type") != null) {
            return "blob";
        }
        if (resourcePath.contains("/messages") || resourcePath.endsWith("/messages")) {
            return "queue";
        }
        List<String> restype = requestContext.getUriInfo().getQueryParameters().get("restype");
        if (restype != null && restype.contains("queue")) {
            return "queue";
        }
        if (requestContext.getHeaderString("x-ms-queue-message-count") != null) {
            return "queue";
        }
        return "blob";
    }

    // ── Dispatch helpers ────────────────────────────────────────────────────────

    /**
     * Dispatches a request whose URL <em>unambiguously</em> names its service (a {@code .vault.azure.net}
     * host, a {@code /emails:send} path, ...). A disabled service answers {@code 503 ServiceDisabled}
     * rather than declining, because declining would let a later stage reinterpret the URL — a disabled
     * Key Vault would otherwise see {@code /secrets/foo} fall through to the account-suffix terminal,
     * which reads {@code secrets} as a storage account name and hands the request to the blob handler.
     */
    private Outcome dispatchOrServiceDisabled(RoutingContext ctx, String account, String serviceType, String path) {
        if (serviceRegistry.isKnown(serviceType) && !serviceRegistry.isEnabled(serviceType)) {
            LOGGER.warnf("Service disabled: %s (%s %s)", serviceType, ctx.method(), path);
            return new Handled(serviceDisabledResponse(serviceType));
        }
        return dispatch(ctx, account, serviceType, path);
    }

    /**
     * Resolves auth and dispatches to the handler for {@code serviceType}. Declines
     * ({@link Fallthrough#TO_NEXT_STAGE}) when no handler is registered or the service is disabled, so
     * the caller can try the next route. Use this only where that fallthrough is intended — the ARM
     * provider stages fall back to the generic {@link ArmHandler}, and the Cosmos root stage must not
     * claim {@code GET /} for Cosmos when Cosmos is off.
     */
    private Outcome dispatch(RoutingContext ctx, String account, String serviceType, String path) {
        Optional<AzureServiceHandler> handler = serviceRegistry.resolve(serviceType);
        if (handler.isEmpty()) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        LOGGER.infof("Dispatching %s request to %s: %s %s (account=%s)", serviceType,
            handler.get().getClass().getSimpleName(), ctx.method(), path, account);
        return new Handled(handler.get().handle(buildRequest(ctx, account, serviceType, path)));
    }

    private static Response serviceDisabledResponse(String serviceType) {
        return new AzureErrorResponse("ServiceDisabled",
                "The " + serviceType + " service is disabled on this emulator.")
                .toXmlResponse(Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
    }

    /**
     * Dispatches without running the auth pipeline, for endpoints whose callers carry no Authorization
     * header (IMDS, Entra). A disabled service hands the request to JAX-RS instead of declining: these
     * paths must 404 rather than be reinterpreted as {@code /{account}/...} by a later stage.
     */
    private Outcome dispatchWithoutAuth(RoutingContext ctx, String serviceType, String label) {
        Optional<AzureServiceHandler> handler = serviceRegistry.resolve(serviceType);
        if (handler.isEmpty()) {
            return Fallthrough.TO_JAX_RS;
        }
        AzureRequest request = new AzureRequest(ctx.method(), serviceType, serviceType, ctx.path(),
            ctx.headers(), ctx.requestContext().getEntityStream(), singleValueQueryParams(ctx.requestContext()),
            null, ctx.secure());
        LOGGER.infof("Dispatching %s request to %s: %s %s", label,
            handler.get().getClass().getSimpleName(), ctx.method(), ctx.path());
        return new Handled(handler.get().handle(request));
    }

    /** Builds an authenticated {@link AzureRequest}: construct, resolve auth, re-stamp. */
    private AzureRequest buildRequest(RoutingContext ctx, String account, String serviceType, String path) {
        Map<String, String> queryParams = new HashMap<>();
        Map<String, List<String>> queryParamsMulti = new HashMap<>();
        ctx.requestContext().getUriInfo().getQueryParameters().forEach((k, v) -> {
            queryParams.put(k, v.get(0));
            queryParamsMulti.put(k, List.copyOf(v));
        });

        AzureRequest request = new AzureRequest(ctx.method(), account, serviceType, path, ctx.headers(),
            ctx.requestContext().getEntityStream(), queryParams, queryParamsMulti, null, ctx.secure());
        return request.withAuthContext(authPipeline.resolve(request));
    }

    private static Map<String, String> singleValueQueryParams(ContainerRequestContext requestContext) {
        Map<String, String> queryParams = new HashMap<>();
        requestContext.getUriInfo().getQueryParameters().forEach((k, v) -> queryParams.put(k, v.get(0)));
        return queryParams;
    }
}
