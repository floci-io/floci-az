package io.floci.az.core;

import io.floci.az.core.RouteOutcome.Fallthrough;
import io.floci.az.core.RouteOutcome.Handled;
import io.floci.az.core.auth.AuthPipeline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a routing decision into a handler call: resolves the handler, builds the {@link AzureRequest}
 * (scope and auth included) and invokes it. Shared by {@link AzureRoutingFilter} and {@link ArmRouter} so
 * both build requests the same way.
 */
@ApplicationScoped
public class RouteDispatcher {

    private static final Logger LOG = Logger.getLogger(RouteDispatcher.class);

    /** How a resolved handler is called: directly, or through the ARM request gate. */
    @FunctionalInterface
    interface Invocation {
        Response invoke(AzureServiceHandler handler, AzureRequest request);
    }

    /** Calls the handler with the request as built. */
    static final Invocation DIRECT = AzureServiceHandler::handle;

    private final AuthPipeline authPipeline;
    private final AzureServiceRegistry serviceRegistry;

    @Inject
    public RouteDispatcher(AuthPipeline authPipeline, AzureServiceRegistry serviceRegistry) {
        this.authPipeline = authPipeline;
        this.serviceRegistry = serviceRegistry;
    }

    /**
     * Dispatches a request whose URL <em>unambiguously</em> names its service (a {@code .vault.azure.net}
     * host, a {@code /emails:send} path, ...). A disabled service answers {@code 503 ServiceDisabled}
     * rather than declining, because declining would let a later stage reinterpret the URL: a disabled
     * Key Vault would otherwise see {@code /secrets/foo} fall through to the account-suffix terminal,
     * which reads {@code secrets} as a storage account name and hands the request to the blob handler.
     */
    RouteOutcome dispatchOrServiceDisabled(RoutingContext ctx, String account, String serviceType, String path) {
        return dispatchOrServiceDisabled(ctx, account, serviceType, path, DIRECT);
    }

    RouteOutcome dispatchOrServiceDisabled(RoutingContext ctx, String account, String serviceType, String path,
                                           Invocation invocation) {
        if (serviceRegistry.isKnown(serviceType) && !serviceRegistry.isEnabled(serviceType)) {
            LOG.warnf("Service disabled: %s (%s %s)", serviceType, ctx.method(), path);
            return new Handled(serviceDisabledResponse(serviceType));
        }
        return dispatch(ctx, account, serviceType, path, invocation);
    }

    /**
     * Resolves auth and dispatches to the handler for {@code serviceType}. Declines
     * ({@link Fallthrough#TO_NEXT_STAGE}) when no handler is registered or the service is disabled, so
     * the caller can try the next route. Use this only where that fallthrough is intended: the ARM
     * provider stages fall back to the generic ARM handler, and the Cosmos root stage must not
     * claim {@code GET /} for Cosmos when Cosmos is off.
     */
    RouteOutcome dispatch(RoutingContext ctx, String account, String serviceType, String path) {
        return dispatch(ctx, account, serviceType, path, DIRECT);
    }

    RouteOutcome dispatch(RoutingContext ctx, String account, String serviceType, String path, Invocation invocation) {
        Optional<AzureServiceHandler> handler = serviceRegistry.resolve(serviceType);
        if (handler.isEmpty()) {
            return Fallthrough.TO_NEXT_STAGE;
        }
        LOG.infof("Dispatching %s request to %s: %s %s (account=%s)", serviceType,
            handler.get().getClass().getSimpleName(), ctx.method(), path, account);
        return new Handled(invocation.invoke(handler.get(), buildRequest(ctx, account, serviceType, path)));
    }

    /**
     * Dispatches without running the auth pipeline, for endpoints whose callers carry no Authorization
     * header (IMDS, Entra). A disabled service hands the request to JAX-RS instead of declining: these
     * paths must 404 rather than be reinterpreted as {@code /{account}/...} by a later stage.
     */
    RouteOutcome dispatchWithoutAuth(RoutingContext ctx, String serviceType, String label) {
        Optional<AzureServiceHandler> handler = serviceRegistry.resolve(serviceType);
        if (handler.isEmpty()) {
            return Fallthrough.TO_JAX_RS;
        }
        AzureRequest request = new AzureRequest(ctx.method(), serviceType, serviceType, ctx.path(),
            ctx.headers(), ctx.body(), singleValueQueryParams(ctx),
            Map.of(), null, ctx.secure(), ctx.host(), ctx.remoteAddress(), ctx.rawPath(), ctx.rawQuery(),
            ctx.authority()).withScope(ctx.scope());
        LOG.infof("Dispatching %s request to %s: %s %s", label,
            handler.get().getClass().getSimpleName(), ctx.method(), ctx.path());
        return new Handled(handler.get().handle(request));
    }

    /** Builds an authenticated {@link AzureRequest}: construct, attach the scope, resolve auth, re-stamp. */
    AzureRequest buildRequest(RoutingContext ctx, String account, String serviceType, String path) {
        Map<String, String> queryParams = new HashMap<>();
        Map<String, List<String>> queryParamsMulti = new HashMap<>();
        ctx.queryParameters().forEach((k, v) -> {
            queryParams.put(k, v.get(0));
            queryParamsMulti.put(k, List.copyOf(v));
        });

        AzureRequest request = new AzureRequest(ctx.method(), account, serviceType, path, ctx.headers(),
            ctx.body(), queryParams, queryParamsMulti, null, ctx.secure(),
            ctx.host(), ctx.remoteAddress(), ctx.rawPath(), ctx.rawQuery(),
            ctx.authority()).withScope(ctx.scope());
        return request.withAuthContext(authPipeline.resolve(request));
    }

    static Response serviceDisabledResponse(String serviceType) {
        return new AzureErrorResponse("ServiceDisabled",
                "The " + serviceType + " service is disabled on this emulator.")
                .toXmlResponse(Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
    }

    private static Map<String, String> singleValueQueryParams(RoutingContext ctx) {
        Map<String, String> queryParams = new HashMap<>();
        ctx.queryParameters().forEach((k, v) -> queryParams.put(k, v.get(0)));
        return queryParams;
    }
}
