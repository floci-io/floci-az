package io.floci.az.core;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.RouteOutcome.Fallthrough;
import io.floci.az.core.RouteOutcome.Handled;
import io.floci.az.core.ServiceRoutes.SuffixRoute;
import io.floci.az.core.cloud.ScopeContext;
import io.floci.az.core.cloud.ScopeResolver;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

@ApplicationScoped
public class AzureRoutingFilter {

    private static final Logger LOGGER = Logger.getLogger(AzureRoutingFilter.class);

    private final AzureServiceRegistry serviceRegistry;
    private final RouteDispatcher dispatcher;
    private final ArmRouter armRouter;
    private final ScopeResolver scopeResolver;
    private final Vertx vertx;
    private final EmulatorConfig config;

    /**
     * The routing chain, in priority order. Each stage either handles the request, hands it to
     * JAX-RS, or declines so the next stage can try. Order is behaviour: the ARM stages must run
     * after the more specific provider stages, and the account-suffix terminal runs only once every
     * stage has declined. The ARM base URL stages live in {@link ArmRouter}; their position in the
     * chain is decided here.
     */
    private final List<Function<RoutingContext, RouteOutcome>> stages;

    @Inject
    public AzureRoutingFilter(AzureServiceRegistry serviceRegistry, RouteDispatcher dispatcher, ArmRouter armRouter,
            ScopeResolver scopeResolver, Vertx vertx, EmulatorConfig config) {
        this.serviceRegistry = serviceRegistry;
        this.dispatcher = dispatcher;
        this.armRouter = armRouter;
        this.scopeResolver = scopeResolver;
        this.vertx = vertx;
        this.config = config;
        this.stages = List.of(
            this::routeByHostSuffix,
            this::routeByHostServiceMarker,
            this::routeImds,
            this::routeAppServiceManagedIdentity,
            this::routeEntra,
            armRouter::routeArmMetadataEndpoints,
            this::routeMicrosoftGraph,
            armRouter::routeMonitor,
            armRouter::routeKeyVaultAtArmBase,
            armRouter::routeEmailAtArmBase,
            armRouter::routeArmProviders,
            armRouter::routeArmGeneric,
            this::routeServiceBusAtomPub,
            this::routeCosmosRoot
        );
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

    // ── Routing tables, assembled from the handlers at startup ──────────────────

    /** Host-suffix → serviceType. Suffixes are mutually exclusive; DFS maps to blob. */
    private volatile List<SuffixRoute> hostRoutes = List.of();

    /**
     * Service marker → serviceType, derived from each host suffix's first label ({@code
     * .blob.core.windows.net → blob}). Lets {@code {account}.{marker}.{any-host}} route host-style on
     * emulator hostnames ({@code devstoreaccount1.blob.localhost}, {@code devstoreaccount1.blob.floci-az})
     * exactly as it would on the production suffix.
     */
    private volatile Map<String, String> hostServiceMarkers = Map.of();

    /**
     * Account-name suffix → serviceType (e.g. {@code {account}-queue → queue}), sorted longest-first.
     * Any suffix that is a string-suffix of another is always shorter, so length-descending gives the
     * required most-specific-first match ({@code -cosmos-table} must beat {@code -table}).
     */
    private volatile List<SuffixRoute> accountSuffixRoutes = List.of();

    /**
     * Service types dispatched by a stage directly rather than named in a handler's routing table. The
     * ARM base URL stages declare theirs in {@link ArmRouter#LITERAL_ROUTE_SERVICE_TYPES}.
     */
    private static final Set<String> LITERAL_ROUTE_SERVICE_TYPES = Set.of(
        "managedidentity", "entra", "graph", "servicebus", "cosmos",
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

        for (AzureServiceHandler handler : serviceRegistry.handlers()) {
            ServiceRoutes routes = handler.routes();
            for (String hostSuffix : routes.hostSuffixes()) {
                hosts.add(new SuffixRoute(hostSuffix, handler.getServiceType()));
            }
            accounts.addAll(routes.accountSuffixes());
        }

        rejectDuplicateSuffixes("host", hosts);
        rejectDuplicateSuffixes("account", accounts);

        hosts.sort(LONGEST_SUFFIX_FIRST);
        accounts.sort(LONGEST_SUFFIX_FIRST);

        this.hostRoutes = List.copyOf(hosts);
        this.accountSuffixRoutes = List.copyOf(accounts);
        this.hostServiceMarkers = deriveHostServiceMarkers(hosts);

        LOGGER.infof("Routing tables: %d host, %d account-suffix, %d ARM provider routes",
            hostRoutes.size(), accountSuffixRoutes.size(), armRouter.providerRoutesInMatchOrder().size());
    }

    private static final Comparator<SuffixRoute> LONGEST_SUFFIX_FIRST =
        Comparator.comparingInt((SuffixRoute route) -> route.suffix().length()).reversed();

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

    /**
     * Derives the service-marker table from the registered host suffixes: the first label of each
     * suffix names the service ({@code .blob.core.windows.net → blob}, {@code .dfs.… → blob}). Two
     * suffixes producing the same marker for different service types would make
     * {@code {account}.{marker}.{host}} ambiguous — fail fast like the other duplicate checks.
     */
    static Map<String, String> deriveHostServiceMarkers(List<SuffixRoute> hostRoutes) {
        Map<String, String> markers = new HashMap<>();
        for (SuffixRoute route : hostRoutes) {
            String suffix = route.suffix();
            int start = suffix.startsWith(".") ? 1 : 0;
            int end = suffix.indexOf('.', start);
            String marker = end < 0 ? suffix.substring(start) : suffix.substring(start, end);
            String previous = markers.putIfAbsent(marker, route.serviceType());
            if (previous != null && !previous.equals(route.serviceType())) {
                throw new IllegalStateException("Two service types claim the host service marker '"
                    + marker + "': " + previous + " and " + route.serviceType());
            }
        }
        return Map.copyOf(markers);
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

    /**
     * Every service type the routing tables and stages can produce. Exposed for the drift test that
     * asserts each one actually resolves to a registered handler.
     */
    Set<String> routedServiceTypes() {
        Set<String> types = new TreeSet<>(LITERAL_ROUTE_SERVICE_TYPES);
        types.addAll(ArmRouter.LITERAL_ROUTE_SERVICE_TYPES);
        hostRoutes.forEach(route -> types.add(route.serviceType()));
        accountSuffixRoutes.forEach(route -> types.add(route.serviceType()));
        types.addAll(armRouter.providerServiceTypes());
        return types;
    }

    // ── Filter entry point ──────────────────────────────────────────────────────

    @ServerRequestFilter(preMatching = true)
    public Uni<Response> filter(ContainerRequestContext requestContext, @Context HttpHeaders httpHeaders,
                                @Context HttpServerRequest serverRequest) {
        URI requestUri = requestContext.getUriInfo().getRequestUri();
        if (wantsConsolePage(requestContext.getMethod(), requestContext.getUriInfo().getPath(),
                requestUri.getRawQuery(), httpHeaders.getHeaderString(HttpHeaders.ACCEPT),
                isAccountHost(requestUri.getHost()))) {
            // A browser opening the emulator's root gets floci-az's dashboard page, as floci-aws's root does; it
            // does not start the console. The rewrite happens here, before resource matching, and only for a
            // request that asks for HTML: the Cosmos SDKs read their DatabaseAccount at "/" with
            // Accept: application/json and still get it.
            requestContext.setRequestUri(requestContext.getUriInfo().getBaseUri().resolve(CONSOLE_PAGE_PATH));
            return Uni.createFrom().nullItem();
        }
        // Capture context before switching threads
        String path0 = requestContext.getUriInfo().getPath();
        String rawPath0 = serverRequest.path();
        // The query exactly as the client sent it. Proxying handlers forward it verbatim rather
        // than re-encoding the decoded parameters, which would not round-trip opaque values.
        String rawQuery0 = serverRequest.query();
        HttpHeaders headers = httpHeaders;
        // Capture the request authority/host now (JAX-RS request scope may not propagate to the
        // blocking thread). Under HTTP/2 the wire protocol uses :authority instead of a Host
        // header, while UriInfo exposes the resolved authority for both HTTP/1.1 and HTTP/2.
        // The routing host drops the port; the authority keeps it for the URLs handlers generate.
        String authority = requestAuthority(serverRequest);
        String h = requestContext.getUriInfo().getRequestUri().getHost();
        if (h == null || h.isBlank()) {
            // Fallback for servlet/JAX-RS implementations that do not expose an absolute request URI.
            h = authority;
        }
        final String capturedHost = h;
        String remoteAddress = serverRequest.remoteAddress() == null
                ? null : serverRequest.remoteAddress().hostAddress();

        // Unordered: an ordered executeBlocking runs one task at a time per event-loop context, so requests
        // sharing an event loop queued behind each other, and a request that calls back into floci-az (a
        // deployment downloading a linked template from floci's own blob storage) waited on itself.
        return Uni.createFrom().completionStage(
            vertx.executeBlocking(() -> doFilter(
                    requestContext, path0, rawPath0, rawQuery0, headers, capturedHost, remoteAddress,
                    authority), false)
                 .toCompletionStage()
        );
    }

    private Response doFilter(ContainerRequestContext requestContext, String decodedPath, String rawPath,
                              String rawQuery, HttpHeaders headers, String capturedHost,
                              String remoteAddress, String authority) {
        // Never trust a client-supplied account-suffix header: only dispatchByAccountSuffix may set it.
        // Header names are case-insensitive on the wire, so match keys case-insensitively.
        for (String header : new ArrayList<>(requestContext.getHeaders().keySet())) {
            if ("x-floci-account-suffix".equalsIgnoreCase(header)) {
                requestContext.getHeaders().remove(header);
            }
        }
        String path = trimLeadingSlash(decodedPath);
        String encodedPath = trimLeadingSlash(rawPath);

        if (isEmulatorAdminPath(path)) {
            return null;
        }

        LOGGER.infof("Incoming request: %s %s", requestContext.getMethod(), path);

        String host = hostWithoutPort(capturedHost);
        ScopeContext scope = scopeResolver.resolve(path, host, headers.getHeaderString(HttpHeaders.AUTHORIZATION));
        RoutingContext ctx = RoutingContext.of(requestContext, path, encodedPath, rawQuery, headers, host,
            remoteAddress, authority, scope);

        for (Function<RoutingContext, RouteOutcome> stage : stages) {
            RouteOutcome outcome = stage.apply(ctx);
            if (outcome instanceof Handled handled) {
                return handled.response();
            }
            if (outcome == Fallthrough.TO_JAX_RS) {
                return null;
            }
        }

        return dispatchByAccountSuffix(ctx);
    }

    /** Where a browser opening the emulator's root is served from: the dashboard, which starts nothing. */
    static final String CONSOLE_PAGE_PATH = "_floci/ui/dashboard";

    /**
     * A browser's {@code GET /} (or {@code HEAD /}) of the emulator itself: the root path with no query, on a
     * host that names no account, with HTML among what it accepts. {@code devstoreaccount1.blob.localhost/?comp=list}
     * is List Containers whatever the browser accepts.
     */
    static boolean wantsConsolePage(String method, String path, String query, String accept, boolean accountHost) {
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            return false;
        }
        if (query != null && !query.isEmpty()) {
            return false;
        }
        if (accountHost) {
            return false;
        }
        return accept != null && accept.toLowerCase(Locale.ROOT).contains("text/html");
    }

    /** Whether host-style routing reads this host as an account: a host suffix, or a service marker label. */
    private boolean isAccountHost(String host) {
        if (host == null) {
            return false;
        }
        return matchSuffix(hostRoutes, host) != null || hostServiceMarkerType(host) != null;
    }

    /** Health and admin endpoints bypass routing entirely. */
    private static boolean isEmulatorAdminPath(String path) {
        return path.equals("health") || path.equals("ready")
            || path.startsWith("_floci/") || path.startsWith("_admin");
    }

    private static String trimLeadingSlash(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /**
     * The {@code host[:port]} the client addressed. HTTP/1.1 carries it in {@code Host}, kept verbatim
     * so every URL built from it stays byte-identical. HTTP/2 carries it in {@code :authority}, which
     * Vert.x removes from the header map (and with it any {@code Host}) and exposes only through
     * {@link HttpServerRequest#authority()}.
     */
    static String requestAuthority(HttpServerRequest serverRequest) {
        String host = serverRequest.getHeader("Host");
        if (host != null && !host.isBlank()) {
            return host;
        }
        HostAndPort authority = serverRequest.authority();
        if (authority == null || authority.host() == null || authority.host().isBlank()) {
            return null;
        }
        return authority.port() < 0 ? authority.host() : authority.host() + ":" + authority.port();
    }

    /**
     * Strips the port and lowercases: hostnames are case-insensitive (RFC 4343), so every host
     * comparison — production suffixes and service markers alike — happens on the lowercase form.
     */
    private static String hostWithoutPort(String capturedHost) {
        if (capturedHost == null) {
            return null;
        }
        int colon = capturedHost.indexOf(':');
        String host = colon < 0 ? capturedHost : capturedHost.substring(0, colon);
        return host.toLowerCase(Locale.ROOT);
    }

    // ── Routing stages, in chain order ──────────────────────────────────────────

    /**
     * Host-based data-plane routing: {@code {account}.<host-suffix>} → serviceType. The host header is
     * extracted in {@link #filter} before {@code executeBlocking} because the JAX-RS request scope may
     * not propagate to the Vert.x blocking thread.
     */
    private RouteOutcome routeByHostSuffix(RoutingContext ctx) {
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
     * Host-style (production-style) addressing on arbitrary emulator hostnames:
     * {@code {account}.{service}.{rest…}} — e.g. {@code devstoreaccount1.blob.localhost} or
     * {@code devstoreaccount1.blob.floci-az} — routes exactly like the production suffix
     * {@code {account}.{service}.core.windows.net}. Real Azure and Azurite both address storage this
     * way; without this stage a host-style request has its container consumed as the account and
     * Create Container answers 501, which blocks the Functions host's AzureWebJobsStorage bootstrap
     * (#267). Path-style callers are unaffected: their hosts carry no service marker as the second
     * label, so they fall through to the account-suffix terminal unchanged.
     */
    private RouteOutcome routeByHostServiceMarker(RoutingContext ctx) {
        String host = ctx.host();
        if (host == null) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        String serviceType = hostServiceMarkerType(host);
        if (serviceType == null) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        String account = host.substring(0, host.indexOf('.'));
        return dispatchOrServiceDisabled(ctx, account, serviceType, ctx.path());
    }

    /** The service type of {@code {account}.{marker}.{at least one more label}}, or null. */
    private String hostServiceMarkerType(String host) {
        int firstDot = host.indexOf('.');
        if (firstDot <= 0) {
            return null;
        }
        int secondDot = host.indexOf('.', firstDot + 1);
        if (secondDot < 0 || secondDot == host.length() - 1) {
            return null;
        }
        return hostServiceMarkers.get(host.substring(firstDot + 1, secondDot));
    }

    /**
     * IMDS (Instance Metadata Service) — managed identity token endpoint:
     * {@code metadata/identity/oauth2/token?resource=...} (header {@code Metadata: true}).
     *
     * <p>Reached by azure-identity ManagedIdentityCredential when {@code AZURE_POD_IDENTITY_AUTHORITY_HOST}
     * points at the emulator. Must precede {@link #routeEntra} (the path also ends with
     * {@code oauth2/token}). Skips the auth pipeline: IMDS requests carry no Authorization header.
     */
    private RouteOutcome routeImds(RoutingContext ctx) {
        if (!ctx.path().startsWith("metadata/identity/")) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        // Managed Identity disabled: fall through to JAX-RS (404) rather than misrouting the path.
        return dispatchWithoutAuth(ctx, "managedidentity", "IMDS");
    }

    /**
     * App Service Managed Identity token endpoint: {@code {appServicePath}?resource=...} (header
     * {@code X-IDENTITY-HEADER: {appServiceHeaderSecret}}).
     *
     * <p>Reached by azidentity/azure_identity credential chains in App Service mode, which read
     * {@code IDENTITY_ENDPOINT}/{@code IDENTITY_HEADER} and GET the endpoint with that header. Runs
     * before the account-suffix fallback so the literal {@code msi/token} path is not read as a
     * storage account. Skips the auth pipeline: App Service MI requests carry no Authorization
     * header, exactly like IMDS.
     */
    private RouteOutcome routeAppServiceManagedIdentity(RoutingContext ctx) {
        if (!ctx.path().equalsIgnoreCase(config.services().managedIdentity().appServicePath())) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        if (!config.services().managedIdentity().enabled()
                || !config.services().managedIdentity().appServiceEnabled()) {
            return Fallthrough.TO_JAX_RS;
        }
        return dispatchWithoutAuth(ctx, "managedidentity", "AppServiceMI");
    }

    /**
     * Microsoft Entra ID (OpenID Connect provider) — tenant-rooted paths at the ARM base URL:
     * {@code {tenant}/oauth2/v2.0/authorize}, {@code {tenant}/oauth2/v2.0/token},
     * {@code {tenant}/oauth2/token}, {@code {tenant}/discovery/v2.0/keys},
     * {@code {tenant}[/v2.0]/.well-known/openid-configuration}, {@code common/userrealm/{username}},
     * where {@code {tenant}} is a tenant id or common/organizations/consumers.
     */
    private RouteOutcome routeEntra(RoutingContext ctx) {
        if (!ScopeResolver.isEntraPath(ctx.path())) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        // Entra disabled: fall through to JAX-RS (these paths 404).
        return dispatchWithoutAuth(ctx, "entra", "Entra");
    }

    /**
     * Microsoft Graph API — a narrow slice at {@code /v1.0/...}: service principal discovery (used by
     * the azurerm provider) plus group-membership management ({@code GraphServiceHandler}).
     */
    private RouteOutcome routeMicrosoftGraph(RoutingContext ctx) {
        if (!ctx.path().startsWith("v1.0/")) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        return dispatchOrServiceDisabled(ctx, "graph", "graph", ctx.path());
    }

    /** Service Bus root-level spec paths (e.g. {@code $namespaceinfo}, {@code $Resources/...}) or AtomPub XML. */
    private RouteOutcome routeServiceBusAtomPub(RoutingContext ctx) {
        String contentType = ctx.headers().getHeaderString(HttpHeaders.CONTENT_TYPE);
        String accept = ctx.headers().getHeaderString(HttpHeaders.ACCEPT);
        String userAgent = ctx.headers().getHeaderString(HttpHeaders.USER_AGENT);
        boolean isAtomPub = (contentType != null && contentType.contains("application/atom+xml"))
            || (accept != null && accept.contains("application/atom+xml"));
        boolean isDotNetAdministrationClient = userAgent != null
            && userAgent.contains("azsdk-net-Messaging.ServiceBus/")
            && ctx.queryParameters().containsKey("api-version");
        SuffixRoute accountSuffix = matchSuffix(accountSuffixRoutes, ctx.firstSegment());
        boolean hasExplicitServiceBusAccountPrefix = accountSuffix != null
            && "servicebus".equals(accountSuffix.serviceType())
            && !ctx.resourcePath().isEmpty();
        if (accountSuffix != null
                && (hasExplicitServiceBusAccountPrefix
                    || (!isAtomPub && !isDotNetAdministrationClient))) {
            return Fallthrough.TO_NEXT_STAGE; // account-suffix routing owns it
        }
        boolean isServiceBusRequest = isAtomPub || isDotNetAdministrationClient
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
    private RouteOutcome routeCosmosRoot(RoutingContext ctx) {
        String firstSegment = ctx.firstSegment();
        if (!firstSegment.isEmpty() && !COSMOS_ROOT_SEGMENTS.contains(firstSegment)) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        LOGGER.infof("Java-SDK cosmos root route: %s %s", ctx.method(), ctx.path());
        RouteOutcome outcome = dispatch(ctx, "devstoreaccount1", "cosmos", ctx.path());
        if (outcome instanceof Handled) {
            return outcome;
        }
        // A root request with no first segment cannot be resolved by the account-suffix terminal.
        return firstSegment.isEmpty() ? Fallthrough.TO_JAX_RS : Fallthrough.TO_NEXT_STAGE;
    }

    // ── Terminal: account-name routing ──────────────────────────────────────────

    /**
     * Terminal stage: {@code /{account}[-suffix]/{resourcePath}}. Unlike the stages above, an unknown
     * or disabled service produces an Azure error response here rather than declining — this is the
     * last chance to answer, so silence would surface as a bare 404.
     */
    private Response dispatchByAccountSuffix(RoutingContext ctx) {
        String accountName = ctx.firstSegment();
        String resourcePath = ctx.resourcePath();
        SuffixRoute route = matchSuffix(accountSuffixRoutes, accountName);
        if (accountName.isEmpty()) {
            return null;
        }

        String serviceType;
        if (route != null) {
            serviceType = route.serviceType();
            accountName = stripSuffix(accountName, route);
            ctx.mutableHeaders().putSingle("x-floci-account-suffix", route.suffix());
        } else {
            serviceType = resolveStorageServiceType(ctx, resourcePath);
        }

        LOGGER.infof("Resolved accountName: %s, serviceType: %s, resourcePath: %s",
            accountName, serviceType, resourcePath);

        if (serviceRegistry.isKnown(serviceType) && !serviceRegistry.isEnabled(serviceType)) {
            LOGGER.warnf("Service disabled: %s", serviceType);
            return RouteDispatcher.serviceDisabledResponse(serviceType);
        }

        Optional<AzureServiceHandler> handler = serviceRegistry.resolve(serviceType);
        if (handler.isEmpty()) {
            LOGGER.warnf("No handler found for serviceType: %s", serviceType);
            return new AzureErrorResponse("ServiceNotImplemented", "The specified service is not implemented.")
                    .toXmlResponse(Response.Status.NOT_IMPLEMENTED.getStatusCode());
        }

        LOGGER.infof("Dispatching to handler: %s", handler.get().getClass().getSimpleName());
        return handler.get().handle(dispatcher.buildRequest(ctx, accountName, serviceType, resourcePath));
    }

    /** Distinguishes blob from queue when the account name carries no service suffix. */
    private String resolveStorageServiceType(RoutingContext ctx, String resourcePath) {
        if (ctx.headers().getHeaderString("x-ms-blob-type") != null) {
            return "blob";
        }
        if (isQueueMessagePath(resourcePath)) {
            return "queue";
        }
        List<String> restype = ctx.queryParameters().get("restype");
        if (restype != null && restype.contains("queue")) {
            return "queue";
        }
        if (ctx.headers().getHeaderString("x-ms-queue-message-count") != null) {
            return "queue";
        }
        return "blob";
    }

    /** Queue message operations use exactly {@code {queue}/messages[/{messageId}]}. */
    private static boolean isQueueMessagePath(String resourcePath) {
        int queueSeparator = resourcePath.indexOf('/');
        if (queueSeparator < 0) {
            return false;
        }
        int messageStart = queueSeparator + 1;
        int messageEnd = resourcePath.indexOf('/', messageStart);
        String messageSegment = messageEnd < 0
            ? resourcePath.substring(messageStart)
            : resourcePath.substring(messageStart, messageEnd);
        return "messages".equals(messageSegment);
    }

    // ── Dispatch helpers ────────────────────────────────────────────────────────

    private RouteOutcome dispatchOrServiceDisabled(RoutingContext ctx, String account, String serviceType, String path) {
        return dispatcher.dispatchOrServiceDisabled(ctx, account, serviceType, path);
    }

    private RouteOutcome dispatch(RoutingContext ctx, String account, String serviceType, String path) {
        return dispatcher.dispatch(ctx, account, serviceType, path);
    }

    private RouteOutcome dispatchWithoutAuth(RoutingContext ctx, String serviceType, String label) {
        return dispatcher.dispatchWithoutAuth(ctx, serviceType, label);
    }
}
