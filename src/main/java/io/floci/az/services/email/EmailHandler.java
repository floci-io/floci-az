package io.floci.az.services.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.StoredObject;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmResources;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.arm.GlobalNameRegistry;
import io.floci.az.core.arm.GlobalNameSource;
import io.floci.az.core.arm.ResourceIndexContributor;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.cloud.CloudSuffix;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.email.EmailModels.CapturedEmail;
import io.floci.az.services.email.EmailModels.CommunicationResource;
import io.floci.az.services.email.EmailModels.EmailSendRequest;
import io.floci.az.services.email.EmailModels.EmailSendResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
public class EmailHandler implements AzureServiceHandler, Resettable, ResourceIndexContributor, GlobalNameSource {

    private static final Logger LOG = Logger.getLogger(EmailHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String NAMESPACE = "Microsoft.Communication";

    private static final String OPERATION_ID_HEADER = "Operation-Id";
    private static final String RETRY_AFTER_SECONDS = "3";

    private static final String STORE = "email";
    private static final String COMMUNICATION_SERVICES = "Microsoft.Communication/communicationServices";
    private static final String EMAIL_SERVICES = "Microsoft.Communication/emailServices";
    private static final String EMAIL_DOMAINS = "Microsoft.Communication/emailServices/domains";

    // Captured messages and the ARM resources share the email backend, so both honour
    // floci-az.storage.services.email and join the load/flush/reset lifecycle. Captured messages are keyed by
    // operation id; the ARM resources by canonical id, which starts with a slash. The ARM resources were
    // kept in memory before, so no older key shape exists on disk.
    private final StorageBackend<String, StoredObject> emailStorage;
    private final ArmResourceStore<CommunicationResource> communicationServices;
    private final ArmResourceStore<CommunicationResource> emailServices;
    private final ArmResourceStore<CommunicationResource> emailDomains;

    private final EmulatorConfig config;
    private final GlobalNameRegistry names;

    @Inject
    public EmailHandler(EmulatorConfig config, StorageFactory storageFactory, GlobalNameRegistry names) {
        this.config = config;
        this.names = names;
        this.emailStorage = storageFactory.create(STORE);
        this.communicationServices = armStore(storageFactory, COMMUNICATION_SERVICES);
        this.emailServices = armStore(storageFactory, EMAIL_SERVICES);
        this.emailDomains = armStore(storageFactory, EMAIL_DOMAINS);
    }

    private static ArmResourceStore<CommunicationResource> armStore(StorageFactory storageFactory, String type) {
        return ArmResourceStore.create(storageFactory, STORE, type, CommunicationResource.class,
                CommunicationResource::id, key -> false);
    }

    @Override
    public String getServiceType() { return "email"; }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().email().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .host(".communication.azure.com")
                .account("-email", "email")
                .provider("Microsoft.Communication")
                .build();
    }

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
            emailStorage.keys().stream().filter(EmailHandler::isCapturedEmailKey).toList().forEach(emailStorage::delete);
            return Response.ok(Map.of("message", "All captured emails cleared")).type("application/json").build();
        }

        // ── ARM: Microsoft.Communication provider ──────────────────────────
        if (ArmPaths.hasProvider(path, NAMESPACE)) {
            return handleArmCommunication(req, path, method);
        }

        return notFound("Unknown email path: " + path);
    }

    /** A captured message's key: an operation id, never an ARM id. */
    private static boolean isCapturedEmailKey(String key) {
        return !key.startsWith("/");
    }

    // ── POST /emails:send ──────────────────────────────────────────────────────

    private Response handleSendEmail(AzureRequest req) {
        try {
            EmailSendRequest sendRequest = MAPPER.readValue(req.bodyStream(), EmailSendRequest.class);

            String operationId = clientOperationId(req);
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
            String baseUrl = req.secure() ? config.baseUrlHttps() : config.effectiveBaseUrl();
            String operationLocation = baseUrl + "/emails/operations/" + operationId
                    + "?api-version=" + req.queryParams().getOrDefault("api-version", "2023-03-31");

            EmailSendResult result = new EmailSendResult(operationId, "Running");

            return Response.status(202)
                    .header("Operation-Location", operationLocation)
                    .header("Retry-After", RETRY_AFTER_SECONDS)
                    .header("x-ms-request-id", UUID.randomUUID().toString())
                    .entity(result)
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
            EmailSendResult result = new EmailSendResult(
                    captured.getOperationId(),
                    captured.getStatus()
            );
            return Response.ok(result).type("application/json").build();
        } catch (Exception e) {
            return serverError("Failed to read operation status: " + e.getMessage());
        }
    }

    // ── Inspection endpoints ─────────────────────────────────────────────────

    private Response handleListCapturedEmails() {
        List<Map<String, Object>> emails = new ArrayList<>();
        emailStorage.scan(EmailHandler::isCapturedEmailKey).forEach(so -> {
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

        // ── CommunicationServices_CheckNameAvailability (subscription level) ──
        if (tail.matches("(?i:checkNameAvailability)(\\?.*)?")) {
            return "POST".equals(method) ? checkNameAvailability(req) : Response.status(405).build();
        }

        // Every Communication resource lives in a resource group, so a path that names none addresses none.
        if (tail.matches("(communicationServices|emailServices)/[^/?]+.*")
                && ArmScope.of(path).map(ArmScope::resourceGroup).isEmpty()) {
            return notFound(tail.replaceAll("^([^/]+/[^/?]+).*", "$1"));
        }

        // ── communicationServices ──────────────────────────────────────────
        if (tail.matches("communicationServices(\\?.*)?") && "GET".equals(method)) {
            return list(communicationServices, path);
        }
        if (tail.matches("communicationServices/[^/?]+(\\?.*)?")) {
            String name = extractSegment(tail, "communicationServices");
            return handleCrud(req, path, method, communicationServices, "communicationServices/" + name,
                    (id, body, existing) -> new CommunicationResource(id.id(), id.name(), COMMUNICATION_SERVICES,
                            bodyString(body, "location", "eastus"), body.get("tags"), Map.of(
                                    "provisioningState", "Succeeded",
                                    "dataLocation", "United States",
                                    "hostName", id.name() + ".communication.azure.com",
                                    "immutableResourceId", existing
                                            .map(resource -> resource.properties().get("immutableResourceId"))
                                            .orElseGet(() -> UUID.randomUUID().toString()))));
        }

        // ── emailServices ─────────────────────────────────────────────────
        // Domain CRUD: emailServices/{name}/domains/{domain}
        if (tail.matches("emailServices/[^/]+/domains/[^/?]+(\\?.*)?")) {
            String domainName = extractSegment(tail, "domains");
            return handleCrud(req, path, method, emailDomains, "domains/" + domainName,
                    (id, body, existing) -> new CommunicationResource(id.id(), id.name(), EMAIL_DOMAINS,
                            bodyString(body, "location", "eastus"), body.get("tags"), Map.of(
                                    "provisioningState", "Succeeded",
                                    "domainManagement", bodyString(
                                            bodyMap(body, "properties"), "domainManagement", "CustomerManaged"),
                                    "fromSenderDomain", id.name(),
                                    "mailFromSenderDomain", id.name())));
        }
        if (tail.matches("emailServices(\\?.*)?") && "GET".equals(method)) {
            return list(emailServices, path);
        }
        if (tail.matches("emailServices/[^/?]+(\\?.*)?")) {
            String name = extractSegment(tail, "emailServices");
            return handleCrud(req, path, method, emailServices, "emailServices/" + name,
                    (id, body, existing) -> new CommunicationResource(id.id(), id.name(), EMAIL_SERVICES,
                            bodyString(body, "location", "eastus"), body.get("tags"), Map.of(
                                    "provisioningState", "Succeeded",
                                    "dataLocation", "United States")));
        }

        return notFound("Unknown Communication path: " + tail);
    }

    /**
     * {@code CommunicationServices_CheckNameAvailability}: a communication service name the name registry holds, in
     * any subscription, is {@code AlreadyExists}; the reasons and messages are the spec's examples. Only a
     * communication service name is global, so a body naming another type is always available.
     */
    private Response checkNameAvailability(AzureRequest req) {
        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        String name = ArmJson.string(body, "name", "");
        String type = ArmJson.string(body, "type", COMMUNICATION_SERVICES);
        boolean global = type.equalsIgnoreCase(COMMUNICATION_SERVICES) || type.equalsIgnoreCase("communicationServices");
        if (global && names.owner(cloud(req), CloudSuffix.COMMUNICATION, name).isPresent()) {
            return Response.ok(Map.of("nameAvailable", false, "reason", "AlreadyExists",
                    "message", "Requested name is unavailable for the requested type")).build();
        }
        return Response.ok(Map.of("nameAvailable", true, "reason", "NameAvailable",
                "message", "Requested name is available for the requested type")).build();
    }

    private static CloudProfile cloud(AzureRequest req) {
        return req.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD) : req.scope().cloud();
    }

    /** Builds the resource a PUT writes, from the request body and the resource it replaces, if any. */
    @FunctionalInterface
    private interface ResourceBuilder {
        CommunicationResource build(ArmResourceId id, Map<String, Object> body, Optional<CommunicationResource> existing);
    }

    /**
     * PUT, GET and DELETE of one resource. A communication service name is global: the gate's
     * {@code GlobalNameRule} answers a PUT for a name another resource owns before this runs, and a path under
     * another scope misses the owner's canonical key, so it reads as not found.
     */
    private Response handleCrud(AzureRequest req, String path, String method,
                                ArmResourceStore<CommunicationResource> store, String label, ResourceBuilder builder) {
        Optional<ArmResourceId> parsed = ArmResourceId.parse(path);
        if (parsed.isEmpty()) {
            return notFound(label);
        }
        ArmResourceId id = parsed.get();
        return switch (method) {
            case "PUT" -> {
                Map<String, Object> body = readBodyMap(req);
                // The read and the write are one store operation, so concurrent creates keep one resource, and the
                // id keeps the casing of the PUT that created it.
                ArmResourceStore.Written<CommunicationResource> written = store.createOrUpdate(id, existing ->
                        builder.build(existing.map(resource -> ArmResourceId.parse(resource.id()).orElseThrow())
                                .orElse(id), body, existing));
                LOG.infov("ARM: wrote {0}", written.stored().value().id());
                yield Response.status(201).entity(written.stored().value().toArm()).type("application/json").build();
            }
            case "GET" -> store.get(id)
                    .map(stored -> Response.ok(stored.value().toArm()).type("application/json").build())
                    .orElseGet(() -> notFound(label));
            case "DELETE" -> {
                store.delete(id);
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    /** The resources under the list path's subscription, and resource group when it names one. */
    private static Response list(ArmResourceStore<CommunicationResource> store, String path) {
        List<Map<String, Object>> value = ArmScope.of(path)
                .map(scope -> store.list(scope).stream().map(stored -> stored.value().toArm()).toList())
                .orElse(List.of());
        return Response.ok(Map.of("value", value)).type("application/json").build();
    }

    // ── Resource index ───────────────────────────────────────────────────────

    @Override
    public List<Map<String, Object>> listRgResources(String sub, String rg) {
        return indexEntries(new ArmScope(sub, rg));
    }

    @Override
    public List<Map<String, Object>> listSubscriptionResources(String sub) {
        return indexEntries(new ArmScope(sub, null));
    }

    @Override
    public boolean indexEnabled() {
        return config.services().email().enabled();
    }

    /** Joined the index with the resource-group cascade; listed only under {@code resource-group-delete}. */
    @Override
    public boolean inDefaultListing() {
        return false;
    }

    /** Communication services, email services and their domains in a subscription, or one of its groups. */
    private List<Map<String, Object>> indexEntries(ArmScope scope) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ArmResourceStore<CommunicationResource> store : List.of(communicationServices, emailServices, emailDomains)) {
            store.list(scope).forEach(stored -> entries.add(ArmResources.indexEntry(stored.value().toArm())));
        }
        return entries;
    }

    // ── GlobalNameSource ─────────────────────────────────────────────────────

    /** Every communication service owns {@code {name}.communication.azure.com}. */
    @Override
    public List<Claim> globalNames() {
        return communicationServices.all().stream()
                .map(stored -> new Claim(CloudSuffix.COMMUNICATION, stored.id().name(), stored.id()))
                .toList();
    }

    @Override
    public boolean holds(CloudSuffix suffix, String name, ArmResourceId owner) {
        return suffix == CloudSuffix.COMMUNICATION
            && communicationServices.get(owner).filter(stored -> name.equalsIgnoreCase(stored.id().name())).isPresent();
    }

    // ── Path parsing helpers ─────────────────────────────────────────────────

    private static String extractCommunicationPath(String fullPath) {
        return ArmPaths.afterProvider(fullPath, NAMESPACE, fullPath);
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

    // ── Header parsing helpers ───────────────────────────────────────────────

    private String clientOperationId(AzureRequest req) {
        return Optional.ofNullable(req.headers())
                .map(h -> h.getHeaderString(OPERATION_ID_HEADER))
                .filter(id -> !id.isBlank())
                // An operation id is a captured message's key, so it must not take the shape of an ARM resource's.
                .filter(EmailHandler::isCapturedEmailKey)
                .orElseGet(() -> UUID.randomUUID().toString());
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
        return ArmJson.string(map, key, defaultValue);
    }

    private static Map<String, Object> bodyMap(Map<String, Object> map, String key) {
        return ArmJson.cast(map.get(key));
    }

    // ── Standard error responses ─────────────────────────────────────────────

    private static Response notFound(String message) {
        return ArmErrors.notFound(message);
    }

    private static Response badRequest(String message) {
        return ArmErrors.error(400, "InvalidRequest", message);
    }

    private static Response serverError(String message) {
        return ArmErrors.error(500, "InternalError", message);
    }

    /** Wipes the captured emails and the Communication resources: {@code POST /_admin/reset}. */
    public void clear() {
        emailStorage.clear();
    }
}
