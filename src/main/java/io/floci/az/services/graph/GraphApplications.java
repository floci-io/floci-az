package io.floci.az.services.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.AzureRequest;
import io.floci.az.services.entra.EntraModels.AppRegistration;
import io.floci.az.services.entra.EntraModels.ClientSecret;
import io.floci.az.services.entra.EntraModels.ServicePrincipal;
import io.floci.az.services.entra.EntraStore;
import io.floci.az.services.entra.TokenIssuer;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Microsoft Graph {@code applications} slice: app registrations and their client secrets.
 * {@code GET /v1.0/applications} (with {@code $filter=appId eq '...'} or {@code displayName eq '...'}),
 * {@code GET /v1.0/applications(appId='...')}, {@code GET} / {@code PATCH} / {@code DELETE}
 * {@code /v1.0/applications/{id}}, {@code POST /v1.0/applications}, and the {@code addPassword} /
 * {@code removePassword} actions. A created registration gets a service principal too, so the
 * emulator's token endpoint and service-principal discovery know it.
 */
final class GraphApplications {

    static final Pattern BY_APP_ID = Pattern.compile("(?i)^applications\\(appId='([^']+)'\\)$");
    private static final Pattern FILTER_EQ = Pattern.compile("(?i)^\\s*(appId|displayName|id)\\s+eq\\s+'((?:[^']|'')*)'\\s*$");
    private static final Pattern FILTER_STARTS = Pattern.compile("(?i)^\\s*startswith\\((displayName|appId),\\s*'((?:[^']|'')*)'\\)\\s*$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SECRET_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789~._-";

    private final EntraStore store;
    private final ObjectMapper mapper;
    private final String baseContext;
    private final String tenantId;

    GraphApplications(EntraStore store, ObjectMapper mapper, String baseContext, String tenantId) {
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
            Optional<AppRegistration> app = store.findAppByClientId(byAppId.group(1));
            if (segments.length == 1) {
                return app.map(a -> method.equals("GET") ? json(application(a, true)) : methodNotAllowed())
                    .orElseGet(() -> notFound(byAppId.group(1)));
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
        Optional<AppRegistration> app = store.findAppByObjectId(segments[1]);
        if (app.isEmpty()) {
            app = store.findAppByClientId(segments[1]);
        }
        if (app.isEmpty()) {
            return notFound(segments[1]);
        }
        if (segments.length == 2) {
            return switch (method) {
                case "GET" -> json(application(app.get(), true));
                case "PATCH" -> patch(request, app.get());
                case "DELETE" -> {
                    store.removeApp(app.get().appId());
                    yield Response.status(204).build();
                }
                default -> methodNotAllowed();
            };
        }
        return action(request, method, app.get(), segments[2]);
    }

    private Response action(AzureRequest request, String method, AppRegistration app, String action) {
        if (!method.equals("POST")) {
            return methodNotAllowed();
        }
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "addpassword" -> addPassword(request, app);
            case "removepassword" -> removePassword(request, app);
            default -> graphError(400, "Request_BadRequest", "Unsupported action '" + action + "' on applications.");
        };
    }

    private Response list(String filter) {
        List<Map<String, Object>> value = new ArrayList<>();
        for (AppRegistration app : store.listApps()) {
            if (filter == null || filter.isBlank() || matches(app, filter)) {
                value.add(application(app, false));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("@odata.context", baseContext + "$metadata#applications");
        body.put("value", value);
        return json(body);
    }

    private boolean matches(AppRegistration app, String filter) {
        Matcher eq = FILTER_EQ.matcher(filter);
        if (eq.matches()) {
            String value = eq.group(2).replace("''", "'");
            return switch (eq.group(1).toLowerCase(Locale.ROOT)) {
                case "appid" -> app.appId().equalsIgnoreCase(value);
                case "id" -> app.objectId().equalsIgnoreCase(value);
                default -> app.displayName() != null && app.displayName().equalsIgnoreCase(value);
            };
        }
        Matcher starts = FILTER_STARTS.matcher(filter);
        if (starts.matches()) {
            String value = starts.group(2).replace("''", "'").toLowerCase(Locale.ROOT);
            String subject = starts.group(1).equalsIgnoreCase("appId") ? app.appId() : app.displayName();
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
        AppRegistration app = new AppRegistration(appId, objectId, name, tenantId, List.of());
        store.putApp(app);
        store.putServicePrincipal(new ServicePrincipal(TokenIssuer.deterministicGuid("sp:" + appId), appId, name, tenantId));
        return Response.status(201).entity(write(application(app, true))).type(MediaType.APPLICATION_JSON).build();
    }

    private Response patch(AzureRequest request, AppRegistration app) {
        Map<String, Object> body = readJson(request);
        if (body == null) {
            return graphError(400, "Request_BadRequest", "The request body is not valid JSON.");
        }
        String displayName = body.get("displayName") instanceof String s && !s.isBlank() ? s : app.displayName();
        store.putApp(new AppRegistration(app.appId(), app.objectId(), displayName, app.tenantId(), app.secrets()));
        return Response.status(204).build();
    }

    private Response addPassword(AzureRequest request, AppRegistration app) {
        Map<String, Object> body = readJson(request);
        Map<String, Object> given = body != null && body.get("passwordCredential") instanceof Map<?, ?> m
            ? castMap(m) : Map.of();
        Instant start = given.get("startDateTime") instanceof String s && !s.isBlank() ? parseInstant(s) : Instant.now();
        Instant end = given.get("endDateTime") instanceof String s && !s.isBlank() ? parseInstant(s)
            : start.plus(730, ChronoUnit.DAYS);
        if (start == null || end == null) {
            return graphError(400, "Request_BadRequest", "startDateTime and endDateTime must be ISO 8601 date-times.");
        }
        String secret = randomSecret();
        ClientSecret credential = new ClientSecret(UUID.randomUUID().toString(), secret, secret.substring(0, 3),
            given.get("displayName") instanceof String d ? d : null, start.toString(), end.toString());
        List<ClientSecret> secrets = new ArrayList<>(app.secrets() == null ? List.of() : app.secrets());
        secrets.add(credential);
        store.putApp(new AppRegistration(app.appId(), app.objectId(), app.displayName(), app.tenantId(), List.copyOf(secrets)));
        Map<String, Object> response = passwordCredential(credential);
        response.put("secretText", secret);
        response.put("@odata.context", baseContext + "$metadata#microsoft.graph.passwordCredential");
        return json(response);
    }

    private Response removePassword(AzureRequest request, AppRegistration app) {
        Map<String, Object> body = readJson(request);
        if (body == null || !(body.get("keyId") instanceof String keyId)) {
            return graphError(400, "Request_BadRequest", "A keyId is required.");
        }
        List<ClientSecret> secrets = new ArrayList<>(app.secrets() == null ? List.of() : app.secrets());
        boolean removed = secrets.removeIf(secret -> keyId.equalsIgnoreCase(secret.keyId()));
        if (!removed) {
            return graphError(404, "Request_ResourceNotFound", "No password credential found with key id '" + keyId + "'.");
        }
        store.putApp(new AppRegistration(app.appId(), app.objectId(), app.displayName(), app.tenantId(), List.copyOf(secrets)));
        return Response.status(204).build();
    }

    // ── Shapes ──────────────────────────────────────────────────────────────────

    /** The Graph {@code application} resource; {@code secretText} is never returned here. */
    Map<String, Object> application(AppRegistration app, boolean withContext) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (withContext) {
            body.put("@odata.context", baseContext + "$metadata#applications/$entity");
        }
        body.put("id", app.objectId());
        body.put("appId", app.appId());
        body.put("displayName", app.displayName());
        body.put("createdDateTime", null);
        body.put("signInAudience", "AzureADMyOrg");
        body.put("identifierUris", List.of());
        List<Map<String, Object>> credentials = new ArrayList<>();
        for (ClientSecret secret : app.secrets() == null ? List.<ClientSecret>of() : app.secrets()) {
            credentials.add(passwordCredential(secret));
        }
        body.put("passwordCredentials", credentials);
        body.put("keyCredentials", List.of());
        body.put("web", Map.of("redirectUris", List.of()));
        body.put("tags", List.of());
        return body;
    }

    private static Map<String, Object> passwordCredential(ClientSecret secret) {
        Map<String, Object> credential = new LinkedHashMap<>();
        credential.put("customKeyIdentifier", null);
        credential.put("displayName", secret.displayName());
        credential.put("endDateTime", secret.endDateTime());
        credential.put("hint", secret.hint());
        credential.put("keyId", secret.keyId());
        credential.put("secretText", null);
        credential.put("startDateTime", secret.startDateTime());
        return credential;
    }

    private static String randomSecret() {
        StringBuilder sb = new StringBuilder(40);
        for (int i = 0; i < 40; i++) {
            sb.append(SECRET_ALPHABET.charAt(RANDOM.nextInt(SECRET_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static Instant parseInstant(String text) {
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            try {
                return java.time.OffsetDateTime.parse(text).toInstant();
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
