package io.floci.az.services.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.services.email.EmailModels.CapturedEmail;
import io.floci.az.services.email.EmailModels.EmailSendRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP handler for Azure Communication Services Email.
 *
 * <h2>Data-plane endpoints</h2>
 * <pre>
 *   POST   /emails:send                     → 202, operation-id in header
 *   GET    /emails/operations/{operationId}  → operation status (Running / Succeeded)
 * </pre>
 *
 * <h2>Inspection endpoints (Mailpit-style)</h2>
 * <pre>
 *   GET    /emailMessages                    → list all captured emails
 *   GET    /emailMessages/{operationId}      → single captured email detail
 * </pre>
 *
 * <h2>ARM management-plane</h2>
 * <pre>
 *   PUT/GET/DELETE .../providers/Microsoft.Communication/communicationServices/{name}
 *   PUT/GET/DELETE .../providers/Microsoft.Communication/emailServices/{name}
 *   PUT/GET/DELETE .../providers/Microsoft.Communication/emailServices/{name}/domains/{domain}
 * </pre>
 *
 * <p>Emails are captured in-memory for local inspection; no actual delivery occurs.
 */
@ApplicationScoped
public class EmailHandler implements AzureServiceHandler, Resettable {

    private static final Logger LOG = Logger.getLogger(EmailHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String PROVIDER_COMMUNICATION = "/providers/Microsoft.Communication/";

    // In-memory stores — no persistence needed for email capture
    private final StorageBackend<String, StoredObject> emailStorage = new InMemoryStorage<>();
    private final Map<String, Map<String, Object>> communicationServices = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> emailServices = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> emailDomains = new LinkedHashMap<>();

    private final EmulatorConfig config;

    @Inject
    public EmailHandler(EmulatorConfig config) {
        this.config = config;
    }

    @Override
    public String getServiceType() { return "email"; }

    @Override
    public boolean canHandle(AzureRequest req) { return "email".equals(req.serviceType()); }

    @Override
    public Response handle(AzureRequest req) {
        String path   = req.resourcePath();
        String method = req.method().toUpperCase();

        LOG.debugf("EmailHandler %s %s", method, path);

        // ── Data-plane: POST /emails:send ──────────────────────────────────
        if (path.matches("emails:send(\\?.*)?") && "POST".equals(method)) {
            return handleSendEmail(req);
        }

        // ── Data-plane: GET /emails/operations/{operationId} ───────────────
        if (path.matches("emails/operations/[^/?]+(\\?.*)?") && "GET".equals(method)) {
            String operationId = extractSegment(path, "operations");
            return handleGetOperationStatus(operationId);
        }

        // ── Inspection: GET /emailMessages ─────────────────────────────────
        if (path.matches("emailMessages(\\?.*)?") && "GET".equals(method)) {
            return handleListCapturedEmails();
        }

        // ── Inspection: GET /emailMessages/{id} ────────────────────────────
        if (path.matches("emailMessages/[^/?]+(\\?.*)?") && "GET".equals(method)) {
            String operationId = extractAfterSegment(path, "emailMessages/");
            return handleGetCapturedEmail(operationId);
        }

        // ── Inspection: DELETE /emailMessages (clear all) ──────────────────
        if (path.matches("emailMessages(\\?.*)?") && "DELETE".equals(method)) {
            emailStorage.clear();
            return Response.ok(Map.of("message", "All captured emails cleared")).type("application/json").build();
        }

        // ── ARM: Microsoft.Communication provider ──────────────────────────
        if (path.contains(PROVIDER_COMMUNICATION)) {
            return handleArmCommunication(req, path, method);
        }

        return notFound("Unknown email path: " + path);
    }

    // ── POST /emails:send ──────────────────────────────────────────────────────

    private Response handleSendEmail(AzureRequest req) {
        try {
            EmailSendRequest sendRequest = MAPPER.readValue(req.bodyStream(), EmailSendRequest.class);

            String operationId = UUID.randomUUID().toString();
            String messageId   = UUID.randomUUID().toString();

            CapturedEmail captured = new CapturedEmail();
            captured.setOperationId(operationId);
            captured.setMessageId(messageId);
            captured.setStatus("Succeeded");   // Emulator: immediately succeed
            captured.setSentAt(Instant.now());
            captured.setRequest(sendRequest);

            byte[] data = MAPPER.writeValueAsBytes(captured);
            emailStorage.put(operationId, new StoredObject(
                    operationId, data, Map.of(), Instant.now(), operationId));

            LOG.infof("Email captured: operationId=%s from=%s subject=%s",
                    operationId,
                    sendRequest.getSenderAddress(),
                    sendRequest.getContent() != null ? sendRequest.getContent().getSubject() : "(none)");

            // ACS returns 202 Accepted with Operation-Location header for polling
            String baseUrl = config.effectiveBaseUrl();
            String operationLocation = baseUrl + "/emails/operations/" + operationId
                    + "?api-version=" + req.queryParams().getOrDefault("api-version", "2023-03-31");

            return Response.status(202)
                    .header("Operation-Location", operationLocation)
                    .header("x-ms-request-id", UUID.randomUUID().toString())
                    .entity(Map.of(
                            "id", operationId,
                            "status", "Running",
                            "error", Map.of()))
                    .type("application/json")
                    .build();

        } catch (Exception e) {
            LOG.errorf(e, "Error processing email send request");
            return badRequest("Invalid email send request: " + e.getMessage());
        }
    }

    // ── GET /emails/operations/{operationId} ────────────────────────────────

    private Response handleGetOperationStatus(String operationId) {
        Optional<StoredObject> found = emailStorage.get(operationId);
        if (found.isEmpty()) {
            return notFound("Operation '" + operationId + "' not found.");
        }

        try {
            CapturedEmail captured = MAPPER.readValue(found.get().data(), CapturedEmail.class);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", captured.getOperationId());
            result.put("status", captured.getStatus());
            if ("Succeeded".equals(captured.getStatus())) {
                result.put("resourceLocation", captured.getMessageId());
            }
            result.put("error", Map.of());

            return Response.ok(result).type("application/json").build();
        } catch (Exception e) {
            return serverError("Failed to read operation status: " + e.getMessage());
        }
    }

    // ── Inspection endpoints ─────────────────────────────────────────────────

    private Response handleListCapturedEmails() {
        List<Map<String, Object>> emails = new ArrayList<>();
        emailStorage.scan(k -> true).forEach(so -> {
            try {
                CapturedEmail captured = MAPPER.readValue(so.data(), CapturedEmail.class);
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("operationId", captured.getOperationId());
                summary.put("messageId", captured.getMessageId());
                summary.put("status", captured.getStatus());
                summary.put("sentAt", captured.getSentAt() != null ? captured.getSentAt().toString() : null);
                if (captured.getRequest() != null) {
                    summary.put("senderAddress", captured.getRequest().getSenderAddress());
                    if (captured.getRequest().getContent() != null) {
                        summary.put("subject", captured.getRequest().getContent().getSubject());
                    }
                    if (captured.getRequest().getRecipients() != null
                            && captured.getRequest().getRecipients().getTo() != null) {
                        summary.put("toCount", captured.getRequest().getRecipients().getTo().size());
                    }
                }
                emails.add(summary);
            } catch (Exception e) {
                LOG.debugv("Skipping unreadable email entry: {0}", e.getMessage());
            }
        });

        return Response.ok(Map.of("value", emails, "count", emails.size()))
                .type("application/json").build();
    }

    private Response handleGetCapturedEmail(String operationId) {
        Optional<StoredObject> found = emailStorage.get(operationId);
        if (found.isEmpty()) {
            return notFound("Email with operationId '" + operationId + "' not found.");
        }

        try {
            CapturedEmail captured = MAPPER.readValue(found.get().data(), CapturedEmail.class);
            return Response.ok(captured).type("application/json").build();
        } catch (Exception e) {
            return serverError("Failed to read captured email: " + e.getMessage());
        }
    }

    // ── ARM: Microsoft.Communication ─────────────────────────────────────────

    private Response handleArmCommunication(AzureRequest req, String path, String method) {
        String tail = extractCommunicationPath(path);

        // ── communicationServices ──────────────────────────────────────────
        // LIST
        if (tail.matches("communicationServices(\\?.*)?") && "GET".equals(method)) {
            return Response.ok(Map.of("value", new ArrayList<>(communicationServices.values())))
                    .type("application/json").build();
        }
        // Single resource CRUD
        if (tail.matches("communicationServices/[^/?]+(\\?.*)?")) {
            String name = extractSegment(tail, "communicationServices");
            return handleCommunicationServiceCrud(req, path, method, name);
        }

        // ── emailServices ─────────────────────────────────────────────────
        // Domain CRUD: emailServices/{name}/domains/{domain}
        if (tail.matches("emailServices/[^/]+/domains/[^/?]+(\\?.*)?")) {
            String emailServiceName = extractSegment(tail, "emailServices");
            String domainName = extractSegment(tail, "domains");
            return handleEmailDomainCrud(req, path, method, emailServiceName, domainName);
        }
        // emailServices LIST
        if (tail.matches("emailServices(\\?.*)?") && "GET".equals(method)) {
            return Response.ok(Map.of("value", new ArrayList<>(emailServices.values())))
                    .type("application/json").build();
        }
        // emailServices single CRUD
        if (tail.matches("emailServices/[^/?]+(\\?.*)?")) {
            String name = extractSegment(tail, "emailServices");
            return handleEmailServiceCrud(req, path, method, name);
        }

        return notFound("Unknown Communication path: " + tail);
    }

    private Response handleCommunicationServiceCrud(AzureRequest req, String path, String method, String name) {
        return switch (method) {
            case "PUT" -> {
                Map<String, Object> body = readBodyMap(req);
                String location = bodyString(body, "location", "eastus");
                Map<String, Object> resource = new LinkedHashMap<>();
                resource.put("id", armId(path, "communicationServices", name));
                resource.put("name", name);
                resource.put("type", "Microsoft.Communication/communicationServices");
                resource.put("location", location);
                resource.put("properties", Map.of(
                        "provisioningState", "Succeeded",
                        "dataLocation", "United States",
                        "hostName", name + ".communication.azure.com",
                        "immutableResourceId", UUID.randomUUID().toString()
                ));
                if (body.containsKey("tags")) {
                    resource.put("tags", body.get("tags"));
                }
                communicationServices.put(name, resource);
                LOG.infof("ARM: created communication service %s", name);
                yield Response.status(201).entity(resource).type("application/json").build();
            }
            case "GET" -> {
                Map<String, Object> resource = communicationServices.get(name);
                yield resource != null
                        ? Response.ok(resource).type("application/json").build()
                        : notFound("communicationServices/" + name);
            }
            case "DELETE" -> {
                communicationServices.remove(name);
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    private Response handleEmailServiceCrud(AzureRequest req, String path, String method, String name) {
        return switch (method) {
            case "PUT" -> {
                Map<String, Object> body = readBodyMap(req);
                String location = bodyString(body, "location", "eastus");
                Map<String, Object> resource = new LinkedHashMap<>();
                resource.put("id", armId(path, "emailServices", name));
                resource.put("name", name);
                resource.put("type", "Microsoft.Communication/emailServices");
                resource.put("location", location);
                resource.put("properties", Map.of(
                        "provisioningState", "Succeeded",
                        "dataLocation", "United States"
                ));
                if (body.containsKey("tags")) {
                    resource.put("tags", body.get("tags"));
                }
                emailServices.put(name, resource);
                LOG.infof("ARM: created email service %s", name);
                yield Response.status(201).entity(resource).type("application/json").build();
            }
            case "GET" -> {
                Map<String, Object> resource = emailServices.get(name);
                yield resource != null
                        ? Response.ok(resource).type("application/json").build()
                        : notFound("emailServices/" + name);
            }
            case "DELETE" -> {
                emailServices.remove(name);
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    private Response handleEmailDomainCrud(AzureRequest req, String path, String method,
                                            String emailServiceName, String domainName) {
        String key = emailServiceName + "/" + domainName;
        return switch (method) {
            case "PUT" -> {
                Map<String, Object> body = readBodyMap(req);
                String location = bodyString(body, "location", "eastus");
                Map<String, Object> resource = new LinkedHashMap<>();
                resource.put("id", armId(path, "domains", domainName));
                resource.put("name", domainName);
                resource.put("type", "Microsoft.Communication/emailServices/domains");
                resource.put("location", location);
                resource.put("properties", Map.of(
                        "provisioningState", "Succeeded",
                        "domainManagement", bodyString(
                                bodyMap(body, "properties"), "domainManagement", "CustomerManaged"),
                        "fromSenderDomain", domainName,
                        "mailFromSenderDomain", domainName
                ));
                if (body.containsKey("tags")) {
                    resource.put("tags", body.get("tags"));
                }
                emailDomains.put(key, resource);
                LOG.infof("ARM: created email domain %s under %s", domainName, emailServiceName);
                yield Response.status(201).entity(resource).type("application/json").build();
            }
            case "GET" -> {
                Map<String, Object> resource = emailDomains.get(key);
                yield resource != null
                        ? Response.ok(resource).type("application/json").build()
                        : notFound("domains/" + domainName);
            }
            case "DELETE" -> {
                emailDomains.remove(key);
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    // ── Path parsing helpers ─────────────────────────────────────────────────

    private static String extractCommunicationPath(String fullPath) {
        int idx = fullPath.indexOf(PROVIDER_COMMUNICATION);
        if (idx >= 0) {
            return fullPath.substring(idx + PROVIDER_COMMUNICATION.length());
        }
        return fullPath;
    }

    private static String extractSegment(String path, String segmentName) {
        Pattern p = Pattern.compile("/" + Pattern.quote(segmentName) + "/([^/?]+)");
        // Also try without leading slash for the beginning of path
        Matcher m = p.matcher("/" + path);
        return m.find() ? m.group(1) : "unknown";
    }

    private static String extractAfterSegment(String path, String marker) {
        int idx = path.indexOf(marker);
        if (idx < 0) return "unknown";
        String rest = path.substring(idx + marker.length());
        int q = rest.indexOf('?');
        return q >= 0 ? rest.substring(0, q) : rest;
    }

    private static String armId(String fullPath, String resourceType, String name) {
        // Build a best-effort ARM resource ID from the full path
        int idx = fullPath.indexOf("/providers/Microsoft.Communication/");
        if (idx >= 0) {
            return "/" + fullPath.substring(0, fullPath.indexOf('?') >= 0
                    ? fullPath.indexOf('?') : fullPath.length());
        }
        return "/subscriptions/default/providers/Microsoft.Communication/" + resourceType + "/" + name;
    }

    // ── Body parsing helpers ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> readBodyMap(AzureRequest req) {
        try {
            if (req.bodyStream() == null || req.bodyStream().available() == 0) {
                return Map.of();
            }
            return MAPPER.readValue(req.bodyStream(), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String bodyString(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return v instanceof String s ? s : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyMap(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Map<?,?> m ? (Map<String, Object>) m : Map.of();
    }

    // ── Standard error responses ─────────────────────────────────────────────

    private static Response notFound(String message) {
        return Response.status(404).entity(Map.of(
                "error", Map.of("code", "ResourceNotFound", "message", message)))
                .type("application/json").build();
    }

    private static Response badRequest(String message) {
        return Response.status(400).entity(Map.of(
                "error", Map.of("code", "InvalidRequest", "message", message)))
                .type("application/json").build();
    }

    private static Response serverError(String message) {
        return Response.status(500).entity(Map.of(
                "error", Map.of("code", "InternalError", "message", message)))
                .type("application/json").build();
    }

    /** Wipes all email data — used by {@code POST /_admin/reset}. */
    public void clearAll() {
        emailStorage.clear();
        communicationServices.clear();
        emailServices.clear();
        emailDomains.clear();
    }
}
