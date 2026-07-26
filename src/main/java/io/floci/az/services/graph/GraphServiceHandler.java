package io.floci.az.services.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.services.entra.EntraModels.Group;
import io.floci.az.services.entra.EntraModels.User;
import io.floci.az.services.entra.EntraStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Microsoft Graph emulation — a narrow slice at {@code /v1.0/...}, reached via
 * {@link io.floci.az.core.AzureRoutingFilter}'s path-shape dispatch (not account-suffix routed).
 *
 * <p>Directory data (users, groups, membership) lives in {@link EntraStore}: it is the same
 * directory the Entra token claims are drawn from, so a token's {@code oid} and a Graph lookup
 * resolve to one identity.
 *
 * <ul>
 *   <li>{@code GET v1.0/servicePrincipals} — service principal discovery, used by the azurerm
 *       provider during initialization</li>
 *   <li>{@code POST v1.0/users/{id}/getMemberGroups} — transitive group membership (direct only;
 *       no nested-group support yet)</li>
 *   <li>{@code POST v1.0/groups/{id}/members/$ref} — add a member</li>
 *   <li>{@code DELETE v1.0/groups/{id}/members/{id}/$ref} — remove a member</li>
 * </ul>
 */
@ApplicationScoped
public class GraphServiceHandler implements AzureServiceHandler {

    private static final Logger LOG = Logger.getLogger(GraphServiceHandler.class);

    private final EmulatorConfig config;
    private final EntraStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public GraphServiceHandler(EmulatorConfig config, EntraStore store) {
        this.config = config;
        this.store = store;
    }

    @Override public String getServiceType() { return "graph"; }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().graph().enabled();
    }

    @Override public boolean canHandle(AzureRequest request) {
        return "graph".equals(request.serviceType());
    }

    @Override
    public Response handle(AzureRequest request) {
        String path = stripSlashes(request.resourcePath());
        LOG.infof("GraphService: %s %s", request.method(), path);

        if (!path.startsWith("v1.0/")) {
            return notFound("Unsupported Graph endpoint: " + path);
        }
        String sub = path.substring("v1.0/".length());
        String[] segments = sub.split("/");

        if (segments.length == 1 && "servicePrincipals".equals(segments[0])) {
            return "GET".equals(request.method())
                    ? servicePrincipals(request.queryParams().get("$filter"))
                    : methodNotAllowed();
        }
        if (segments.length == 3 && "users".equals(segments[0]) && "getMemberGroups".equals(segments[2])) {
            return "POST".equals(request.method())
                    ? getMemberGroups(segments[1], request)
                    : methodNotAllowed();
        }
        if (segments.length == 4 && "groups".equals(segments[0]) && "members".equals(segments[2])
                && "$ref".equals(segments[3])) {
            return "POST".equals(request.method())
                    ? addMember(segments[1], request)
                    : methodNotAllowed();
        }
        if (segments.length == 5 && "groups".equals(segments[0]) && "members".equals(segments[2])
                && "$ref".equals(segments[4])) {
            return "DELETE".equals(request.method())
                    ? removeMember(segments[1], segments[3])
                    : methodNotAllowed();
        }
        return notFound("Unsupported Graph endpoint: " + path);
    }

    // ── Endpoints ────────────────────────────────────────────────────────────────

    /** azurerm calls {@code GET /v1.0/servicePrincipals?$filter=appId eq '{clientId}'} during provider init. */
    private Response servicePrincipals(String filter) {
        String appId = extractAppId(filter);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value", List.of(Map.of(
                "id",    "00000000-0000-0000-0000-000000000010",
                "appId", appId
        )));
        return json(body);
    }

    private static String extractAppId(String filter) {
        if (filter != null) {
            String[] parts = filter.split("'");
            if (parts.length >= 2) {
                return parts[1];
            }
        }
        return "00000000-0000-0000-0000-000000000003";
    }

    /** {@code id} may be the user's object id or userPrincipalName, as real Graph accepts both. */
    private Response getMemberGroups(String userIdOrUpn, AzureRequest request) {
        Optional<User> user = store.getUser(userIdOrUpn).or(() -> store.findUserByUpn(userIdOrUpn));
        if (user.isEmpty()) {
            return graphError(404, "Request_ResourceNotFound",
                    "Resource '" + userIdOrUpn + "' does not exist.");
        }
        boolean securityEnabledOnly = Boolean.TRUE.equals(readJsonBody(request).get("securityEnabledOnly"));

        List<String> value = store.memberGroups(user.get().objectId()).stream()
                .filter(groupId -> !securityEnabledOnly
                        || store.getGroup(groupId).map(Group::securityEnabled).orElse(false))
                .sorted()
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("@odata.context", "https://graph.microsoft.com/v1.0/$metadata#Collection(Edm.String)");
        body.put("value", value);
        return json(body);
    }

    private Response addMember(String groupId, AzureRequest request) {
        if (store.getGroup(groupId).isEmpty()) {
            return graphError(404, "Request_ResourceNotFound", "Resource '" + groupId + "' does not exist.");
        }
        Object odataId = readJsonBody(request).get("@odata.id");
        String memberId = odataId instanceof String s ? lastSegment(s) : null;
        if (memberId == null) {
            return graphError(400, "Request_BadRequest", "@odata.id is required.");
        }
        store.addMember(groupId, memberId);
        return Response.noContent().build();
    }

    private Response removeMember(String groupId, String memberId) {
        if (store.getGroup(groupId).isEmpty()) {
            return graphError(404, "Request_ResourceNotFound", "Resource '" + groupId + "' does not exist.");
        }
        store.removeMember(groupId, memberId);
        return Response.noContent().build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * {@code @odata.id} points at real Graph ({@code https://graph.microsoft.com/v1.0/directoryObjects/{id}}),
     * not this emulator — parse the trailing id rather than validating the host.
     */
    private static String lastSegment(String odataId) {
        if (odataId.isBlank()) {
            return null;
        }
        String trimmed = odataId.endsWith("/") ? odataId.substring(0, odataId.length() - 1) : odataId;
        int slash = trimmed.lastIndexOf('/');
        return slash < 0 ? trimmed : trimmed.substring(slash + 1);
    }

    private Map<String, Object> readJsonBody(AzureRequest request) {
        try {
            byte[] bytes = request.bodyStream() == null ? new byte[0] : request.bodyStream().readAllBytes();
            if (bytes.length == 0) {
                return Map.of();
            }
            return mapper.readValue(bytes, Map.class);
        } catch (IOException e) {
            return Map.of();
        }
    }

    private static String stripSlashes(String path) {
        String p = path;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private Response methodNotAllowed() {
        return Response.status(405).build();
    }

    private Response notFound(String message) {
        return graphError(404, "BadRequest", message);
    }

    private Response json(Object body) {
        try {
            return Response.ok(mapper.writeValueAsString(body)).type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            return graphError(500, "InternalServerError", "serialisation failed");
        }
    }

    private Response graphError(int status, String code, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", detail);
        try {
            return Response.status(status).entity(mapper.writeValueAsString(body))
                    .type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            return Response.status(status).build();
        }
    }
}
