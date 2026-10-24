package io.floci.az.core;

import io.floci.az.core.RouteOutcome.Fallthrough;
import io.floci.az.core.RouteOutcome.Handled;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmRequestGate;
import io.floci.az.core.cloud.ScopeResolver;
import io.floci.az.services.arm.ArmHandler;
import io.floci.az.services.monitor.MonitorHandler;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The routing stages for requests that arrive at the ARM base URL: the metadata anti-route, the data-plane
 * paths some clients send to the ARM base (Monitor ingestion and query, Key Vault, ACS Email), and the
 * management plane proper (provider namespaces, then the generic ARM handler).
 *
 * <p>{@link AzureRoutingFilter} owns the order of the whole chain and calls each stage here at its
 * position. Every management-plane dispatch goes through the {@link ArmRequestGate}: the provider stage,
 * the generic stage, and Monitor's diagnostic settings (an extension resource under an arbitrary resource
 * id). The data-plane stages and the metadata anti-route do not, because they address no ARM resource.
 */
@ApplicationScoped
public class ArmRouter {

    private static final Logger LOG = Logger.getLogger(ArmRouter.class);

    /** Well-known Key Vault data-plane path prefixes, as sent to the ARM base URL by azurerm v3. */
    private static final Set<String> KEY_VAULT_COLLECTIONS = Set.of(
        "secrets", "certificates", "keys", "deletedsecrets", "deletedcertificates", "deletedkeys", "rng"
    );

    /** The origin headers an internal request keeps: the caller's credentials and the scheme it used. */
    private static final List<String> FORWARDED_HEADERS = List.of(HttpHeaders.AUTHORIZATION, "X-Forwarded-Proto");

    /** Service types the ARM stages dispatch to directly rather than through a handler's routing table. */
    static final Set<String> LITERAL_ROUTE_SERVICE_TYPES = Set.of("monitor", "keyvault", "email", "arm");

    /**
     * A resolved ARM provider route: the pre-built {@code /providers/<namespace>/} marker, the service
     * type of the handler that claimed it, and the handler's guard.
     */
    record ResolvedProvider(String marker, String serviceType, Predicate<String> guard) {

        /**
         * True when {@code markerPath} carries this route's {@code /providers/<namespace>/} marker and passes its
         * guard. The marker is compared ignoring case, because ARM resolves a namespace however a client spells it.
         */
        boolean claims(String markerPath) {
            return ArmPaths.indexOfIgnoreCase(markerPath, marker) >= 0 && guard.test(markerPath);
        }
    }

    private static final Comparator<ResolvedProvider> GUARDED_FIRST_THEN_MARKER =
        Comparator.comparing((ResolvedProvider route) -> route.guard() == ServiceRoutes.ProviderRoute.ALWAYS)
                  .thenComparing(ResolvedProvider::marker);

    private final AzureServiceRegistry serviceRegistry;
    private final RouteDispatcher dispatcher;
    private final ArmHandler armHandler;
    private final ArmRequestGate gate;
    private final ScopeResolver scopeResolver;
    private final RouteDispatcher.Invocation gated;

    /**
     * The stages a request floci-az makes to itself runs through, in the routing filter's order. Every stage of
     * the chain ahead of these, and the ones after, matches only hosts and paths an ARM resource id never has.
     */
    private final List<Function<RoutingContext, RouteOutcome>> internalStages;

    /**
     * ARM provider routes, guarded routes first. Managed Identity is the only guarded route, and it
     * must be tried before the broader providers: an {@code identities/default} scope may itself be a
     * Compute/ContainerService resource, and Managed Identity's {@code /providers/} segment is always
     * the last one. Handler discovery order is arbitrary, so guarded-first is what makes this
     * deterministic; the namespace tiebreak keeps the rest stable.
     */
    private volatile List<ResolvedProvider> providerRoutes = List.of();

    @Inject
    public ArmRouter(AzureServiceRegistry serviceRegistry, RouteDispatcher dispatcher, ArmHandler armHandler,
                     ArmRequestGate gate, ScopeResolver scopeResolver) {
        this.serviceRegistry = serviceRegistry;
        this.dispatcher = dispatcher;
        this.armHandler = armHandler;
        this.gate = gate;
        this.scopeResolver = scopeResolver;
        this.gated = (handler, request) -> this.gate.serve(request, handler::handle);
        this.internalStages = List.of(this::routeMonitor, this::routeKeyVaultAtArmBase, this::routeEmailAtArmBase,
            this::routeArmProviders, this::routeArmGeneric);
    }

    /** Assembles the provider table from every registered handler's {@link ServiceRoutes}. */
    @PostConstruct
    void buildProviderRoutes() {
        List<ResolvedProvider> providers = new ArrayList<>();
        for (AzureServiceHandler handler : serviceRegistry.handlers()) {
            for (ServiceRoutes.ProviderRoute provider : handler.routes().providers()) {
                providers.add(new ResolvedProvider(provider.marker(), handler.getServiceType(), provider.guard()));
            }
        }
        rejectDuplicateProviders(providers);
        providers.sort(GUARDED_FIRST_THEN_MARKER);
        this.providerRoutes = List.copyOf(providers);
        LOG.debugv("ARM router: {0} provider routes", providerRoutes.size());
    }

    /**
     * Fails fast when two handlers claim the same ARM provider namespace, the provider counterpart of
     * {@link AzureRoutingFilter#rejectDuplicateSuffixes}. Without this, a duplicate would resolve silently
     * by the guarded-first / marker sort order instead of surfacing the wiring bug.
     */
    static void rejectDuplicateProviders(List<ResolvedProvider> providers) {
        Map<String, String> claimedBy = new HashMap<>();
        for (ResolvedProvider provider : providers) {
            String previous = claimedBy.putIfAbsent(provider.marker(), provider.serviceType());
            if (previous != null) {
                throw new IllegalStateException("Two handlers claim the same ARM provider '"
                    + provider.marker() + "': " + previous + " and " + provider.serviceType());
            }
        }
    }

    /** ARM provider routes as {@code marker → serviceType}, in match order. */
    List<Map.Entry<String, String>> providerRoutesInMatchOrder() {
        return providerRoutes.stream()
                .map(route -> Map.entry(route.marker(), route.serviceType()))
                .toList();
    }

    /** The service types whose provider route claims a slash-prefixed ARM path, in match order. */
    List<String> claimingServiceTypes(String markerPath) {
        return providerRoutes.stream()
                .filter(route -> route.claims(markerPath))
                .map(ResolvedProvider::serviceType)
                .toList();
    }

    /** The service types the provider table can produce. */
    List<String> providerServiceTypes() {
        return providerRoutes.stream().map(ResolvedProvider::serviceType).toList();
    }

    // ── Requests floci-az makes to itself ───────────────────────────────────────

    /**
     * Sends an ARM request on behalf of {@code origin}, through the same stages, request gate and handler a
     * client's request to {@code path} would reach, so the handler applies its own side effects. The request
     * carries no body; it keeps the origin's credentials, scheme and authority, so the handler sees the same
     * caller and generates the same URLs.
     *
     * @param method the HTTP method
     * @param path   an ARM path ({@code /subscriptions/...}), with or without a leading slash; no query
     * @param query  the query parameters, such as {@code api-version}
     * @param origin the request this one is made for
     * @return the handler's response; an ARM {@code 404 ResourceNotFound} when no stage claims the path
     */
    public Response dispatchInternal(String method, String path, Map<String, String> query, AzureRequest origin) {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        Map<String, String> initialHeaders = new LinkedHashMap<>();
        if (origin.headers() != null) {
            for (String name : FORWARDED_HEADERS) {
                initialHeaders.put(name, origin.headers().getHeaderString(name));
            }
        }
        String authority = RequestUrls.resolveAuthority(origin);
        if (authority != null) {
            initialHeaders.put(HttpHeaders.HOST, authority);
        }
        SyntheticHttpHeaders headers = new SyntheticHttpHeaders(initialHeaders);
        MultivaluedMap<String, String> queryParameters = new MultivaluedHashMap<>();
        query.forEach(queryParameters::putSingle);
        String rawQuery = query.isEmpty() ? null : query.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining("&"));
        RoutingContext ctx = new RoutingContext(method.toUpperCase(Locale.ROOT), relative, relative, rawQuery,
            headers, headers.map(), queryParameters, InputStream.nullInputStream(), origin.host(), origin.secure(),
            origin.remoteAddress(), authority, scopeResolver.resolve(relative, origin.host(), authorization(origin)));
        LOG.debugv("Internal ARM request: {0} {1}", ctx.method(), relative);
        for (Function<RoutingContext, RouteOutcome> stage : internalStages) {
            RouteOutcome outcome = stage.apply(ctx);
            if (outcome instanceof Handled handled) {
                return handled.response();
            }
            if (outcome == Fallthrough.TO_JAX_RS) {
                break;
            }
        }
        return ArmErrors.notFound("Resource not found: " + relative);
    }

    /** The caller's {@code Authorization} header, so an internal request is scoped to the same caller. */
    private static String authorization(AzureRequest origin) {
        return origin.headers() == null ? null : origin.headers().getHeaderString(HttpHeaders.AUTHORIZATION);
    }

    // ── Routing stages, in chain order ──────────────────────────────────────────

    /**
     * ARM metadata endpoint ({@code /metadata/endpoints}), handed to JAX-RS, where
     * {@code AzureIdentityController} answers it in the shape the api-version asks for, every endpoint at the
     * request's base URL: the single object go-azure-sdk (azurerm's {@code metadata_host}) and
     * {@code az cloud register} read, the cloud list of the az CLI's {@code ARM_CLOUD_METADATA_URL}
     * (2019-05-01), and the {@code cloudEndpoint} document azurerm's location validation reads (2018-01-01).
     */
    RouteOutcome routeArmMetadataEndpoints(RoutingContext ctx) {
        return ctx.path().startsWith("metadata/endpoints") ? Fallthrough.TO_JAX_RS : Fallthrough.TO_NEXT_STAGE;
    }

    /**
     * Monitor at the ARM base: Logs Ingestion ({@code dataCollectionRules/...}) and Log Analytics query
     * ({@code v1/workspaces/...}) data-plane paths, and diagnostic settings. Diagnostic settings live under
     * an arbitrary resource URI ({resourceUri}/providers/microsoft.insights/diagnosticSettings), so they must
     * be claimed here, before {@link #routeArmProviders} matches the resource's own provider namespace; being
     * an ARM extension resource, they pass the gate.
     */
    RouteOutcome routeMonitor(RoutingContext ctx) {
        boolean diagnosticSettings = MonitorHandler.isDiagnosticSettingsPath(ctx.path());
        if (!ctx.path().startsWith("dataCollectionRules/") && !ctx.path().startsWith("v1/workspaces/")
                && !diagnosticSettings) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        return dispatcher.dispatchOrServiceDisabled(ctx, "monitor", "monitor", ctx.path(),
            diagnosticSettings ? gated : RouteDispatcher.DIRECT);
    }

    /**
     * Key Vault data-plane paths arriving at the ARM base URL. The azurerm v3 provider (when
     * {@code metadata/endpoints} returns 404) sends all key vault data-plane requests directly to the
     * ARM base URL rather than to {@code *.vault.azure.net}. Route them to the Key Vault handler with a
     * fixed account name so both the provider and {@code kv_get} in the BATS tests share a namespace.
     */
    RouteOutcome routeKeyVaultAtArmBase(RoutingContext ctx) {
        if (!KEY_VAULT_COLLECTIONS.contains(ctx.firstSegment())) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        return dispatcher.dispatchOrServiceDisabled(ctx, armHandler.getDefaultKvAccount(), "keyvault", ctx.path());
    }

    /**
     * ACS Email data-plane paths arriving at the ARM base URL. SDKs may POST {@code /emails:send} or GET
     * {@code /emails/operations/{id}} directly to the configured endpoint rather than via a
     * {@code *.communication.azure.com} host header. Also handles {@code /emailMessages} inspection.
     */
    RouteOutcome routeEmailAtArmBase(RoutingContext ctx) {
        String path = ctx.path();
        if (!path.startsWith("emails:") && !path.startsWith("emails/") && !path.startsWith("emailMessages")) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        return dispatcher.dispatchOrServiceDisabled(ctx, "default", "email", path);
    }

    /**
     * ARM management-plane provider routing: {@code subscriptions/{sub}/.../providers/Microsoft.X/...}
     * and the tenant-rooted {@code providers/Microsoft.X/...} forms (built-in policy definitions,
     * management-group scopes). Iterated in {@link #providerRoutes} order (guarded first): Managed
     * Identity is guarded so its provider segment must be the last {@code /providers/} in the path;
     * identity-scoped children (role assignments, locks, ...) have a later segment and fall through to
     * {@link #routeArmGeneric}. Azure Policy is guarded so it claims only the policy resource types of
     * Microsoft.Authorization. All of these must precede the generic ARM stage, which would otherwise
     * swallow every path.
     */
    RouteOutcome routeArmProviders(RoutingContext ctx) {
        String path = ctx.path();
        boolean tenantRooted = path.startsWith("providers/");
        if ((!path.startsWith("subscriptions/") && !tenantRooted) || ArmPaths.isProviderRegistration(path)) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        // A tenant-rooted path has no segment before "providers/", so the "/providers/<ns>/" marker is
        // matched against the slash-prefixed path; guards see the same form.
        String markerPath = tenantRooted ? "/" + path : path;
        for (ResolvedProvider route : providerRoutes) {
            if (!route.claims(markerPath)) {
                continue;
            }
            // A disabled provider service declines, letting a later provider (or generic ARM) claim it.
            RouteOutcome outcome = dispatcher.dispatch(ctx, route.serviceType(), route.serviceType(), ctx.path(), gated);
            if (outcome instanceof Handled) {
                return outcome;
            }
        }
        return Fallthrough.TO_NEXT_STAGE;
    }

    /**
     * ARM general management-plane paths ({@code subscriptions/{sub}/...}, {@code tenants/...}):
     * resource groups, storage accounts, key vaults, etc. that the more specific provider stages above
     * did not claim. {@link ArmHandler} serves them itself or hands them to its provider lane.
     */
    RouteOutcome routeArmGeneric(RoutingContext ctx) {
        String path = ctx.path();
        boolean isArmPath = path.startsWith("subscriptions/") || path.equals("subscriptions")
            || path.startsWith("tenants/") || path.equals("tenants");
        if (!isArmPath) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        return dispatcher.dispatchOrServiceDisabled(ctx, "arm", "arm", path, gated);
    }
}
