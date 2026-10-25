package io.floci.az.services.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.services.entra.DirectoryEntitySet;
import io.floci.az.services.entra.DirectoryObject;
import io.floci.az.services.entra.DirectoryReader;
import io.floci.az.services.entra.DirectoryReader.DirectoryGroup;
import io.floci.az.services.entra.DirectoryReader.DirectoryServicePrincipal;
import io.floci.az.services.entra.DirectoryReader.DirectoryUser;
import io.floci.az.services.entra.GraphDirectoryStore;
import io.floci.az.services.entra.TenantResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Microsoft Graph emulation — a narrow slice at {@code /v1.0/...}, reached via
 * {@link io.floci.az.core.AzureRoutingFilter}'s path-shape dispatch (not account-suffix routed).
 *
 * <p>Directory data (users, groups, membership) lives in {@link GraphDirectoryStore}: it is the
 * same directory the Entra token claims are drawn from, so a token's {@code oid} and a Graph lookup
 * resolve to one identity. Every request acts in the default tenant's directory.
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
    private final GraphDirectoryStore store;
    private final DirectoryReader directory;
    private final TenantResolver tenants;
    private final AzureDirectory azureDirectory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    @Inject
    public GraphServiceHandler(EmulatorConfig config, GraphDirectoryStore store, DirectoryReader directory,
                               TenantResolver tenants, AzureDirectory azureDirectory) {
        this.config = config;
        this.store = store;
        this.directory = directory;
        this.tenants = tenants;
        this.azureDirectory = azureDirectory;
    }

    @Override public String getServiceType() { return "graph"; }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().graph().enabled();
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

        Response applications = new GraphApplications(store, mapper, random, graphBase(request),
                config.services().entra().defaultTenantId()).handle(request, segments);
        if (applications != null) {
            return applications;
        }

        if (segments.length == 1 && "groups".equals(segments[0])) {
            return "GET".equals(request.method()) ? listGroups(request) : methodNotAllowed();
        }
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

    private Response listGroups(AzureRequest request) {
        Predicate<DirectoryObject> filter;
        try {
            filter = GroupFilter.compile(request.queryParams().get("$filter"));
        } catch (IllegalArgumentException e) {
            return graphError(400, "Request_BadRequest", e.getMessage());
        }
        String expand = request.queryParams().get("$expand");
        boolean includeMembers = expand != null && !expand.isBlank();
        if (includeMembers && !"members".equals(expand.trim())) {
            return graphError(400, "Request_BadRequest", "Only $expand=members is supported for groups.");
        }
        String tenantId = tenantId();
        List<Map<String, Object>> groups = store.list(tenantId, DirectoryEntitySet.GROUPS).stream()
                .filter(filter)
                .sorted(Comparator.comparing(DirectoryObject::id))
                .map(group -> {
                    Map<String, Object> result = publicDirectoryObject(group);
                    if (includeMembers) {
                        List<Map<String, Object>> members = store.links(tenantId, group.id(), GraphDirectoryStore.MEMBERS)
                                .stream().sorted()
                                .map(id -> member(tenantId, id))
                                .flatMap(Optional::stream)
                                .map(this::publicDirectoryObject)
                                .toList();
                        result.put("members", members);
                    }
                    return result;
                }).toList();
        return json(Map.of("@odata.context", azureDirectory.cloudOfTenant(tenantId).endpoints().microsoftGraph()
                + "/v1.0/$metadata#groups"
                + (includeMembers ? "(members())" : ""), "value", groups));
    }

    private Optional<DirectoryObject> member(String tenantId, String id) {
        return store.get(tenantId, DirectoryEntitySet.USERS, id)
                .or(() -> store.get(tenantId, DirectoryEntitySet.GROUPS, id))
                .or(() -> store.get(tenantId, DirectoryEntitySet.SERVICE_PRINCIPALS, id));
    }

    private Map<String, Object> publicDirectoryObject(DirectoryObject object) {
        Map<String, Object> result = new LinkedHashMap<>();
        String type = switch (object.entitySet()) {
            case "users" -> "user";
            case "groups" -> "group";
            case "servicePrincipals" -> "servicePrincipal";
            default -> throw new IllegalArgumentException("Unsupported group member type: " + object.entitySet());
        };
        result.put("@odata.type", "#microsoft.graph." + type);
        result.put("id", object.id());
        result.put("displayName", object.text("displayName"));
        if ("user".equals(type)) {
            result.put("mail", object.text("mail"));
            result.put("userPrincipalName", object.text("userPrincipalName"));
        } else if ("group".equals(type)) {
            result.put("securityEnabled", object.props().path("securityEnabled").asBoolean(false));
        } else {
            result.put("appId", object.text("appId"));
        }
        return result;
    }

    /** azurerm calls {@code GET /v1.0/servicePrincipals?$filter=appId eq '{clientId}'} during provider init. */
    /** The principal the directory holds for the appId, so its id is the {@code oid} the token endpoint issues; a fixed id for an unknown app. */
    private Response servicePrincipals(String filter) {
        String appId = extractAppId(filter);
        String id = directory.servicePrincipalByAppId(config.services().entra().defaultTenantId(), appId)
                .map(DirectoryServicePrincipal::id)
                .orElse("00000000-0000-0000-0000-000000000010");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value", List.of(Map.of("id", id, "appId", appId)));
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
        String tenantId = tenantId();
        Optional<DirectoryUser> user = directory.user(tenantId, userIdOrUpn)
                .or(() -> directory.userByPrincipalName(tenantId, userIdOrUpn));
        if (user.isEmpty()) {
            return graphError(404, "Request_ResourceNotFound",
                    "Resource '" + userIdOrUpn + "' does not exist.");
        }
        boolean securityEnabledOnly = Boolean.TRUE.equals(readJsonBody(request).get("securityEnabledOnly"));

        List<String> value = directory.memberGroupIds(tenantId, user.get().id()).stream()
                .filter(groupId -> !securityEnabledOnly
                        || directory.group(tenantId, groupId).map(DirectoryGroup::securityEnabled).orElse(false))
                .sorted()
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("@odata.context", graphBase(request) + "$metadata#Collection(Edm.String)");
        body.put("value", value);
        return json(body);
    }

    private Response addMember(String groupId, AzureRequest request) {
        String tenantId = tenantId();
        if (directory.group(tenantId, groupId).isEmpty()) {
            return graphError(404, "Request_ResourceNotFound", "Resource '" + groupId + "' does not exist.");
        }
        Object odataId = readJsonBody(request).get("@odata.id");
        String memberId = odataId instanceof String s ? lastSegment(s) : null;
        if (memberId == null) {
            return graphError(400, "Request_BadRequest", "@odata.id is required.");
        }
        store.addLink(tenantId, groupId, GraphDirectoryStore.MEMBERS, memberId);
        return Response.noContent().build();
    }

    private Response removeMember(String groupId, String memberId) {
        String tenantId = tenantId();
        if (directory.group(tenantId, groupId).isEmpty()) {
            return graphError(404, "Request_ResourceNotFound", "Resource '" + groupId + "' does not exist.");
        }
        store.removeLink(tenantId, groupId, GraphDirectoryStore.MEMBERS, memberId);
        return Response.noContent().build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** The directory every Graph request acts in: the default tenant's, until callers carry their own. */
    private String tenantId() {
        return tenants.defaultTenant().directoryTenantId();
    }

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

    /** The {@code v1.0} base of the Graph endpoint of the request's cloud, for {@code @odata.context} values. */
    private static String graphBase(AzureRequest request) {
        CloudProfile cloud = request.scope() == null
            ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD) : request.scope().cloud();
        return cloud.endpoints().microsoftGraph() + "/v1.0/";
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
