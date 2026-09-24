package io.floci.az.services.signalr;

import io.floci.az.config.EmulatorConfig;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;

@ApplicationScoped
public class SignalRWebSockets {
    private static final Logger LOG = Logger.getLogger(SignalRWebSockets.class);
    private final EmulatorConfig config;
    private final Vertx vertx;
    private final SignalRConnections connections = new SignalRConnections();
    private final Map<String, Negotiated> pending = new ConcurrentHashMap<>();
    private long timer = -1;
    private record Negotiated(SignalRConnections.Hub hub, String id, String user, long expires) {}

    @Inject
    public SignalRWebSockets(EmulatorConfig config, Vertx vertx) { this.config = config; this.vertx = vertx; }

    void routes(@Observes Router router) {
        router.routeWithRegex("^/(?:[^/]+-signalr/)?(?:server/?|client/?|client/negotiate)$").order(-100).handler(this::handle);
        timer = vertx.setPeriodic(5000, ignored -> {
            pending.values().removeIf(value -> value.expires() <= System.currentTimeMillis());
            connections.tick();
        });
    }

    private void handle(RoutingContext context) {
        String path = context.request().path();
        String[] parts = path.substring(1).split("/");
        boolean prefixed = parts[0].endsWith("-signalr");
        String host = URI.create(context.request().absoluteURI()).getHost();
        if (!prefixed && !host.endsWith(".service.signalr.net") && context.request().getParam("hub") == null) {
            context.next();
            return;
        }
        if (!config.services().signalr().enabled()) { error(context, 503, "ServiceDisabled", "SignalR is disabled"); return; }
        String account = prefixed ? parts[0].substring(0, parts[0].length() - 8)
                : host.endsWith(".service.signalr.net") ? host.substring(0, host.length() - ".service.signalr.net".length()) : "default";
        String endpoint = parts[prefixed ? 1 : 0];
        boolean server = endpoint.equals("server");
        String hubName = context.request().getParam("hub");
        if (hubName == null || hubName.isBlank()) { error(context, 400, "BadRequest", "hub is required"); return; }
        SignalRConnections.Hub hub = new SignalRConnections.Hub(account, hubName.toLowerCase(Locale.ROOT));
        String authorization = context.request().getHeader("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7)
                : context.request().getParam("access_token");
        SignalRTokens.Claims claims;
        try {
            // The SDK deliberately omits the port from token audiences, including local endpoints.
            String audience = context.request().scheme() + "://" + host + (prefixed ? "/" + parts[0] : "") + "/" + endpoint + "/?hub="
                    + URLEncoder.encode(hubName, StandardCharsets.UTF_8);
            claims = SignalRTokens.verify(token, config.services().signalr().accessKey(), audience);
        } catch (IllegalArgumentException e) { error(context, 401, "Unauthorized", "Invalid SignalR access token"); return; }
        if (!server && path.endsWith("/negotiate") && context.request().method() == HttpMethod.POST) {
            if (!connections.hasServer(hub)) { error(context, 503, "NoServerConnection", "No application server connected"); return; }
            String id = UUID.randomUUID().toString();
            String connectionToken = UUID.randomUUID().toString();
            pending.put(connectionToken, new Negotiated(hub, id, claims.userId(), System.currentTimeMillis() + 30000));
            context.json(new JsonObject().put("negotiateVersion", 1).put("connectionId", id).put("connectionToken", connectionToken)
                    .put("availableTransports", List.of(Map.of("transport", "WebSockets", "transferFormats", List.of("Text", "Binary")))));
            return;
        }
        String expectedPath = (prefixed ? "/" + parts[0] : "") + "/" + endpoint;
        if (!(path.equals(expectedPath) || path.equals(expectedPath + "/")) || context.request().method() != HttpMethod.GET) {
            error(context, 404, "NotFound", "SignalR endpoint not found"); return;
        }
        String id = UUID.randomUUID().toString();
        if (!server && context.request().getParam("id") != null) {
            Negotiated negotiated = pending.get(context.request().getParam("id"));
            if (negotiated == null || !negotiated.hub().equals(hub) || !negotiated.user().equals(claims.userId())
                    || negotiated.expires() <= System.currentTimeMillis()
                    || !pending.remove(context.request().getParam("id"), negotiated)) {
                error(context, 404, "ConnectionNotFound", "Negotiated connection not found"); return;
            }
            id = negotiated.id();
        }
        String connectionId = id;
        context.request().toWebSocket().onSuccess(socket -> {
            if (server) { connections.server(hub, socket); }
            else { connections.client(hub, connectionId, claims, context.request().query(), socket); }
        }).onFailure(error -> LOG.debug("SignalR WebSocket upgrade failed", error));
    }

    private static void error(RoutingContext context, int status, String code, String message) {
        context.response().setStatusCode(status);
        context.json(new JsonObject().put("error", Map.of("code", code, "message", message)));
    }

    @PreDestroy
    void close() {
        if (timer != -1) { vertx.cancelTimer(timer); }
        connections.close();
        pending.clear();
    }
}
