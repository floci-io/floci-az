package io.floci.az.services.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.core.AzureRequest;
import io.floci.az.services.entra.DirectoryEntitySet;
import io.floci.az.services.entra.DirectoryObject;
import io.floci.az.services.entra.GraphDirectoryStore;
import io.floci.az.services.entra.TokenIssuer;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Microsoft Graph {@code applications} slice: app registrations and their client secrets, held in
 * the {@link GraphDirectoryStore} the token endpoint reads. {@code GET /v1.0/applications} (with
 * {@code $filter=appId eq '...'}, {@code displayName eq '...'} or {@code startswith(...)}),
 * {@code GET /v1.0/applications(appId='...')}, {@code GET} / {@code PATCH} / {@code DELETE}
 * {@code /v1.0/applications/{id}}, {@code POST /v1.0/applications}, and the {@code addPassword} /
 * {@code removePassword} actions. A created registration gets a service principal too, so the
 * emulator's token endpoint and service-principal discovery know it. A secret is returned once, by
 * {@code addPassword}; the store keeps only its hash.
 */
final class GraphApplications {

    static final Pattern BY_APP_ID = Pattern.compile("(?i)^applications\\(appId='([^']+)'\\)$");
    private static final Pattern FILTER_EQ = Pattern.compile("(?i)^\\s*(appId|displayName|id)\\s+eq\\s+'((?:[^']|'')*)'\\s*$");
    private static final Pattern FILTER_STARTS = Pattern.compile("(?i)^\\s*startswith\\((displayName|appId),\\s*'((?:[^']|'')*)'\\)\\s*$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SECRET_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789~._-";

    private final GraphDirectoryStore store;
    private final ObjectMapper mapper;
    private final String baseContext;
    private final String tenantId;

    GraphApplications(GraphDirectoryStore store, ObjectMapper mapper, String baseContext, String tenantId) {
        this.store = store;
        this.mapper = mapper;
        this.baseContext = baseContext;
        this.tenantId = tenantId;
    }

    /** Routes an {@code applications...} path; null when the path is not one. */
    Response handle(AzureRequest request, String[] segments) {
        String method = request.method().toUpperCase(Locale.ROOT);
        String head = segments[0];
        Matcher byAppId = BY_APP_ID.matcher(head);
        if (byAppId.matches()) {
            Optional<DirectoryObject> app = byAppId(byAppId.group(1));
            if (segments.length == 1) {
                return app.map(a -> method.equals("GET") ? json(application(a, true)) : methodNotAllowed())
                    .orElseGet(() -> notFound(byAppId.group(1)));
            }
            if (segments.length > 2) {
                return unknownSegment(segments[2]);
            }
            return app.isEmpty() ? notFound(byAppId.group(1)) : action(request, method, app.get(), segments[1]);
        }
        if (!head.equalsIgnoreCase("applications")) {
            return null;
        }
        if (segments.length == 1) {
            return switch (method) {
                case "GET" -> list(request.queryParams().get("$filter"));
                case "POST" -> create(request);
                default -> methodNotAllowed();
            };
        }
        Optional<DirectoryObject> app = byObjectId(segments[1]).or(() -> byAppId(segments[1]));
        if (app.isEmpty()) {
            return notFound(segments[1]);
        }
        if (segments.length == 2) {
            return switch (method) {
                case "GET" -> json(application(app.get(), true));
                case "PATCH" -> patch(request, app.get());
                case "DELETE" -> delete(app.get());
                default -> methodNotAllowed();
            };
        }
        if (segments.length > 3) {
            return unknownSegment(segments[3]);
        }
        return action(request, method, app.get(), segments[2]);
    }

    private Response unknownSegment(String segment) {
        return graphError(400, "Request_BadRequest", "Resource not found for the segment '" + segment + "'.");
    }

    private Response action(AzureRequest request, String method, DirectoryObject app, String action) {
        if (!method.equals("POST")) {
            return methodNotAllowed();
        }
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "addpassword" -> addPassword(request, app);
            case "removepassword" -> removePassword(request, app);
            default -> graphError(400, "Request_BadRequest", "Unsupported action '" + action + "' on applications.");
        };
    }

    private Optional<DirectoryObject> byAppId(String appId) {
        return store.findByAlternateKey(tenantId, DirectoryEntitySet.APPLICATIONS, "appId", appId);
    }

    private Optional<DirectoryObject> byObjectId(String id) {
        return store.get(tenantId, DirectoryEntitySet.APPLICATIONS, id);
    }

    private Response list(String filter) {
        boolean filtered = filter != null && !filter.isBlank();
        if (filtered && !FILTER_EQ.matcher(filter).matches() && !FILTER_STARTS.matcher(filter).matches()) {
            return graphError(400, "Request_BadRequest", "Unsupported or invalid $filter: '" + filter + "'.");
        }
        List<Map<String, Object>> value = new ArrayList<>();
        for (DirectoryObject app : store.list(tenantId, DirectoryEntitySet.APPLICATIONS)) {
            if (!filtered || matches(app, filter)) {
                value.add(application(app, false));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("@odata.context", baseContext + "$metadata#applications");
        body.put("value", value);
        return json(body);
    }

    private static boolean matches(DirectoryObject app, String filter) {
        Matcher eq = FILTER_EQ.matcher(filter);
        if (eq.matches()) {
            String value = eq.group(2).replace("''", "'");
            return switch (eq.group(1).toLowerCase(Locale.ROOT)) {
                case "appid" -> value.equalsIgnoreCase(app.text("appId"));
                case "id" -> value.equalsIgnoreCase(app.id());
                default -> app.text("displayName") != null && app.text("displayName").equalsIgnoreCase(value);
            };
        }
        Matcher starts = FILTER_STARTS.matcher(filter);
        if (starts.matches()) {
            String value = starts.group(2).replace("''", "'").toLowerCase(Locale.ROOT);
            String subject = starts.group(1).equalsIgnoreCase("appId") ? app.text("appId") : app.text("displayName");
            return subject != null && subject.toLowerCase(Locale.ROOT).startsWith(value);
        }
        return false;
    }

    private Response create(AzureRequest request) {
        Map<String, Object> body = readJson(request);
        if (body == null) {
            return graphError(400, "Request_BadRequest", "The request body is not valid JSON.");
        }
        Object displayName = body.get("displayName");
        if (!(displayName instanceof String name) || name.isBlank()) {
            return graphError(400, "Request_BadRequest", "A displayName is required to create an application.");
        }
        String appId = UUID.randomUUID().toString();
        String objectId = UUID.randomUUID().toString();
        DirectoryObject app = GraphDirectoryStore.application(tenantId, objectId, appId, name, List.of());
        synchronized (store) {
            store.put(app);
            store.put(GraphDirectoryStore.servicePrincipal(tenantId, TokenIssuer.deterministicGuid("sp:" + appId), appId, name));
        }
        return Response.status(201).entity(write(application(app, true))).type(MediaType.APPLICATION_JSON).build();
    }

    private Response patch(AzureRequest request, DirectoryObject app) {
        Map<String, Object> body = readJson(request);
        if (body == null) {
            return graphError(400, "Request_BadRequest", "The request body is not valid JSON.");
        }
        if (body.containsKey("displayName") && !(body.get("displayName") instanceof String s && !s.isBlank())) {
            return graphError(400, "Request_BadRequest", "displayName must be a non-empty string.");
        }
        Optional<DirectoryObject> updated = update(app.id(), current -> {
            if (body.get("displayName") instanceof String s) {
                current.put("displayName", s);
            }
        });
        return updated.isEmpty() ? notFound(app.id()) : Response.status(204).build();
    }

    private Response addPassword(AzureRequest request, DirectoryObject app) {
        Map<String, Object> body = readJson(request);
        if (body == null) {
            return graphError(400, "Request_BadRequest", "The request body is not valid JSON.");
        }
        Object credentialNode = body.get("passwordCredential");
        if (credentialNode != null && !(credentialNode instanceof Map<?, ?>)) {
            return graphError(400, "Request_BadRequest", "passwordCredential must be an object.");
        }
        Map<String, Object> given = credentialNode instanceof Map<?, ?> m ? castMap(m) : Map.of();
        // Defaults stand in for omitted values only; a value that is supplied must be valid.
        if (given.containsKey("displayName") && given.get("displayName") != null && !(given.get("displayName") instanceof String)) {
            return graphError(400, "Request_BadRequest", "displayName must be a string.");
        }
        Instant start;
        Instant end;
        try {
            start = suppliedInstant(given, "startDateTime", Instant.now());
            end = suppliedInstant(given, "endDateTime", start.plus(730, ChronoUnit.DAYS));
        } catch (IllegalArgumentException e) {
            return graphError(400, "Request_BadRequest", "startDateTime and endDateTime must be ISO 8601 date-times.");
        }
        if (!end.isAfter(start)) {
            return graphError(400, "Request_BadRequest", "endDateTime must be after startDateTime.");
        }
        String secret = randomSecret();
        ObjectNode credential = mapper.createObjectNode();
        credential.put("keyId", UUID.randomUUID().toString());
        credential.put("hint", secret.substring(0, 3));
        credential.put("displayName", given.get("displayName") instanceof String d ? d : null);
        credential.put("startDateTime", start.toString());
        credential.put("endDateTime", end.toString());
        credential.put(GraphDirectoryStore.SECRET_TEXT, secret); // hashed by the store on write
        Optional<DirectoryObject> updated = update(app.id(), current -> credentials(current).add(credential));
        if (updated.isEmpty()) {
            return notFound(app.id());
        }
        Map<String, Object> response = passwordCredential(credential);
        response.put("secretText", secret);
        response.put("@odata.context", baseContext + "$metadata#microsoft.graph.passwordCredential");
        return json(response);
    }

    private Response removePassword(AzureRequest request, DirectoryObject app) {
        Map<String, Object> body = readJson(request);
        if (body == null || !(body.get("keyId") instanceof String keyId)) {
            return graphError(400, "Request_BadRequest", "A keyId is required.");
        }
        boolean[] removed = {false};
        Optional<DirectoryObject> updated = update(app.id(), current -> {
            ArrayNode credentials = credentials(current);
            for (int i = credentials.size() - 1; i >= 0; i--) {
                if (keyId.equalsIgnoreCase(textOrNull(credentials.get(i), "keyId"))) {
                    credentials.remove(i);
                    removed[0] = true;
                }
            }
        });
        if (updated.isEmpty()) {
            return notFound(app.id());
        }
        if (!removed[0]) {
            return graphError(404, "Request_ResourceNotFound", "No password credential found with key id '" + keyId + "'.");
        }
        return Response.status(204).build();
    }

    private Response delete(DirectoryObject app) {
        synchronized (store) {
            store.delete(tenantId, DirectoryEntitySet.APPLICATIONS, app.id());
            store.findByAlternateKey(tenantId, DirectoryEntitySet.SERVICE_PRINCIPALS, "appId", app.text("appId"))
                .ifPresent(sp -> store.delete(tenantId, DirectoryEntitySet.SERVICE_PRINCIPALS, sp.id()));
        }
        return Response.status(204).build();
    }

    /**
     * Re-reads the application and writes it back with {@code change} applied to a copy of its properties,
     * under the store's lock so that overlapping changes compose; empty when the app no longer exists.
     */
    private Optional<DirectoryObject> update(String id, Consumer<ObjectNode> change) {
        synchronized (store) {
            Optional<DirectoryObject> current = byObjectId(id);
            if (current.isEmpty()) {
                return Optional.empty();
            }
            ObjectNode props = current.get().props().deepCopy();
            change.accept(props);
            DirectoryObject updated = new DirectoryObject(current.get().id(), current.get().tenantId(),
                current.get().entitySet(), props, current.get().created());
            store.put(updated);
            return Optional.of(updated);
        }
    }

    private static ArrayNode credentials(ObjectNode props) {
        JsonNode existing = props.get(GraphDirectoryStore.PASSWORD_CREDENTIALS);
        if (existing instanceof ArrayNode array) {
            return array;
        }
        return props.putArray(GraphDirectoryStore.PASSWORD_CREDENTIALS);
    }

    // ── Shapes ──────────────────────────────────────────────────────────────────

    /** The Graph {@code application} resource; {@code secretText} is never returned here. */
    Map<String, Object> application(DirectoryObject app, boolean withContext) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (withContext) {
            body.put("@odata.context", baseContext + "$metadata#applications/$entity");
        }
        body.put("id", app.id());
        body.put("appId", app.text("appId"));
        body.put("displayName", app.text("displayName"));
        body.put("createdDateTime", app.created() == null ? null : app.created().toString());
        body.put("signInAudience", "AzureADMyOrg");
        body.put("identifierUris", List.of());
        List<Map<String, Object>> credentials = new ArrayList<>();
        for (JsonNode credential : app.props().path(GraphDirectoryStore.PASSWORD_CREDENTIALS)) {
            credentials.add(passwordCredential(credential));
        }
        body.put("passwordCredentials", credentials);
        body.put("keyCredentials", List.of());
        body.put("web", Map.of("redirectUris", List.of()));
        body.put("tags", List.of());
        return body;
    }

    private static Map<String, Object> passwordCredential(JsonNode credential) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customKeyIdentifier", null);
        out.put("displayName", textOrNull(credential, "displayName"));
        out.put("endDateTime", textOrNull(credential, "endDateTime"));
        out.put("hint", textOrNull(credential, "hint"));
        out.put("keyId", textOrNull(credential, "keyId"));
        out.put("secretText", null);
        out.put("startDateTime", textOrNull(credential, "startDateTime"));
        return out;
    }

    private static String textOrNull(JsonNode node, String property) {
        return node != null && node.hasNonNull(property) ? node.get(property).asText() : null;
    }

    private static String randomSecret() {
        StringBuilder sb = new StringBuilder(40);
        for (int i = 0; i < 40; i++) {
            sb.append(SECRET_ALPHABET.charAt(RANDOM.nextInt(SECRET_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** The supplied date-time under {@code key}, {@code fallback} when absent or null; anything else is an error. */
    private static Instant suppliedInstant(Map<String, Object> given, String key, Instant fallback) {
        Object value = given.get(key);
        if (value == null) {
            return fallback;
        }
        Instant parsed = value instanceof String s ? parseInstant(s) : null;
        if (parsed == null) {
            throw new IllegalArgumentException(key);
        }
        return parsed;
    }

    private static Instant parseInstant(String text) {
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private Map<String, Object> readJson(AzureRequest request) {
        try {
            byte[] bytes = request.bodyStream() == null ? new byte[0] : request.bodyStream().readAllBytes();
            if (bytes.length == 0) {
                return Map.of();
            }
            Object parsed = mapper.readValue(bytes, Object.class);
            return parsed instanceof Map<?, ?> map ? castMap(map) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private Response json(Object body) {
        return Response.ok(write(body)).type(MediaType.APPLICATION_JSON).build();
    }

    private String write(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Response notFound(String id) {
        return graphError(404, "Request_ResourceNotFound",
            "Resource '" + id + "' does not exist or one of its queried reference-property objects are not present.");
    }

    private Response methodNotAllowed() {
        return Response.status(405).build();
    }

    private Response graphError(int status, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("innerError", Map.of("date", Instant.now().toString(), "request-id", UUID.randomUUID().toString(),
            "client-request-id", UUID.randomUUID().toString()));
        return Response.status(status).entity(write(Map.of("error", error))).type(MediaType.APPLICATION_JSON).build();
    }
}
