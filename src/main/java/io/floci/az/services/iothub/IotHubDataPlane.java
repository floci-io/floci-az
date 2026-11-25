package io.floci.az.services.iothub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.services.iothub.IotHubModels.Device;
import io.floci.az.services.iothub.IotHubModels.IotHubResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The service API a hub serves on {@code https://{hub}.azure-devices.net}: its device identity registry, as
 * {@code az iot hub device-identity} and the service SDKs manage it, and the bulk key regeneration
 * {@code device-identity renew-key} calls. Twins, modules, jobs, configurations and the
 * device endpoints are not served yet and answer {@code 404}.
 *
 * <p>Every request is checked against the hub's shared access policies ({@link IotHubSas}). Errors are answered in
 * the shape the IoT Hub service SDKs parse, {@code {"Message": "ErrorCode:{code};{text}", "ExceptionMessage":
 * "Tracking ID:..."}}, with the code also in the {@code iothub-errorcode} header.
 */
@ApplicationScoped
public class IotHubDataPlane {

    private static final Logger LOG = Logger.getLogger(IotHubDataPlane.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JSON = "application/json; charset=utf-8";
    private static final String ERROR_CODE_HEADER = "iothub-errorcode";

    /**
     * Device IDs, from Learn's identity registry page: up to 128 ASCII letters and digits plus
     * {@code - . % _ * ? ! ( ) , : = @ $ '}. They are case-sensitive.
     */
    private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9\\-.%_*?!(),:=@$']{1,128}");
    /** "A 128 character-long string", in any UTF-8 characters. */
    private static final int MAX_STATUS_REASON = 128;
    /** The symmetric keys IoT Hub accepts are 16 to 64 bytes, base64. */
    private static final int MIN_KEY_BYTES = 16;
    private static final int MAX_KEY_BYTES = 64;

    private static final String SAS = "sas";
    private static final String SELF_SIGNED = "selfSigned";
    private static final List<String> AUTH_TYPES = List.of(SAS, SELF_SIGNED, "certificateAuthority", "none");
    private static final String ENABLED = "enabled";
    private static final String DISABLED = "disabled";

    /** The two queries {@code az iot hub device-identity list} sends, without and with {@code --edge-enabled}. */
    private static final String ALL_DEVICES = "select * from devices";
    private static final String EDGE_DEVICES = "select * from devices where capabilities.iotedge = true";
    /** The page size of a query that names none, and the largest a query may ask for. */
    private static final int DEFAULT_PAGE = 100;
    private static final int MAX_PAGE = 1000;
    private static final String CONTINUATION = "x-ms-continuation";
    /** The keys {@code POST /devices/keys/regenerate} renews, by its {@code policyKey}. */
    private static final String PRIMARY_KEY = "primaryKey";
    private static final String SECONDARY_KEY = "secondaryKey";
    private static final String BOTH_KEYS = "both";

    /** What a device reports at run time, before it has ever connected. */
    private static final String NEVER = "0001-01-01T00:00:00Z";
    private static final DateTimeFormatter TRACKING_TIME = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss")
            .withZone(ZoneOffset.UTC);
    /** .NET ticks at the Unix epoch: Azure's generation IDs are tick counts. */
    private static final long EPOCH_TICKS = 621_355_968_000_000_000L;
    private static final long TICKS_PER_MILLI = 10_000L;

    /** Per instance, not static: a static one would be built into the native image with a fixed seed. */
    private final SecureRandom random = new SecureRandom();
    private final IotHubArmService arm;
    private final IotHubDevices devices;

    @Inject
    public IotHubDataPlane(IotHubArmService arm, IotHubDevices devices) {
        this.arm = arm;
        this.devices = devices;
    }

    /** A request refused part way, with the answer to send. */
    private static final class Refused extends RuntimeException {
        private final transient Response response;

        Refused(Response response) {
            super(null, null, false, false);
            this.response = response;
        }

        Response response() {
            return response;
        }
    }

    /** Serves one request addressed to a hub's host. */
    public Response handle(AzureRequest req) {
        String hubName = req.accountName();
        Optional<IotHubResource> hub = hubName == null || hubName.isEmpty()
                ? Optional.empty() : arm.findByName(req, hubName);
        if (hub.isEmpty()) {
            return error(404, "IotHubNotFound", "IoT hub '" + hubName + "' does not exist.");
        }
        List<String> segments;
        try {
            segments = segments(req);
        } catch (IllegalArgumentException malformed) {
            // The HTTP server refuses such a path first; this answers one that reaches the registry another way.
            return error(400, "ArgumentInvalid", "The request path holds a malformed percent escape.");
        }
        String method = req.method().toUpperCase(Locale.ROOT);
        boolean regenerate = segments.equals(List.of("devices", "keys", "regenerate"));
        if (segments.isEmpty() || !segments.getFirst().equals("devices") || (segments.size() > 2 && !regenerate)) {
            return error(404, "ResourceNotFound", "This IoT Hub operation is not served yet.");
        }
        if (regenerate && !"POST".equals(method)) {
            return methodNotAllowed();
        }
        boolean query = segments.size() == 2 && segments.get(1).equals("query") && "POST".equals(method);
        IotHubSas.Access access = "GET".equals(method) || query ? IotHubSas.Access.READ : IotHubSas.Access.WRITE;
        Optional<String> refusal = IotHubSas.refusal(req, hub.get(), IotHubArmService.hostName(req, hub.get().name()),
                access, Instant.now());
        if (refusal.isPresent()) {
            return error(401, "IotHubUnauthorizedAccess", refusal.get());
        }

        Context context = new Context(IotHubArmService.cloudName(req), hub.get().name());
        try {
            if (query) {
                return query(req, context);
            }
            if (regenerate) {
                return regenerateKeys(req, context);
            }
            if (segments.size() == 1) {
                return "GET".equals(method) ? listDevices(req, context) : methodNotAllowed();
            }
            String deviceId = segments.get(1);
            return switch (method) {
                case "PUT" -> createOrUpdate(req, context, deviceId);
                case "GET" -> devices.get(context.cloud(), context.hub(), deviceId)
                        .map(device -> json(200, deviceToWire(device)))
                        .orElseGet(() -> deviceNotFound(deviceId));
                case "DELETE" -> delete(req, context, deviceId);
                default -> methodNotAllowed();
            };
        } catch (Refused refused) {
            return refused.response();
        } catch (ArmJson.InvalidBodyException e) {
            return error(400, "ArgumentInvalid", "The request body is not a JSON object.");
        }
    }

    /** The cloud and the hub a request addresses. */
    private record Context(String cloud, String hub) {
    }

    // ── Devices ──────────────────────────────────────────────────────────────

    /**
     * {@code PUT /devices/{id}}: without {@code If-Match} it creates the device, with it it replaces one that exists.
     * Keys left out of a {@code sas} identity are generated, as Azure generates them.
     */
    private Response createOrUpdate(AzureRequest req, Context context, String deviceId) {
        refuseDeviceId(deviceId);
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Object sentId = body.get("deviceId");
        if (sentId != null && !deviceId.equals(sentId)) {
            return error(400, "ArgumentInvalid", "The deviceId in the body, '" + sentId
                    + "', does not match the device ID in the request URI, '" + deviceId + "'.");
        }
        String ifMatch = ifMatch(req);
        Device written;
        synchronized (devices.writeLock()) {
            // Checked under the lock the hub's delete takes to clear its devices, so no device outlives its hub.
            if (arm.findByName(req, context.hub()).isEmpty()) {
                return error(404, "IotHubNotFound", "IoT hub '" + context.hub() + "' does not exist.");
            }
            Optional<Device> existing = devices.get(context.cloud(), context.hub(), deviceId);
            if (ifMatch == null && existing.isPresent()) {
                return error(409, "DeviceAlreadyExists", "A device with ID '" + deviceId
                        + "' is already registered.");
            }
            if (ifMatch != null && existing.isEmpty()) {
                return deviceNotFound(deviceId);
            }
            if (ifMatch != null && !"*".equals(ifMatch) && !ifMatch.equals(existing.get().etag())) {
                return preconditionFailed(deviceId);
            }
            written = device(deviceId, body, existing);
            devices.put(context.cloud(), context.hub(), written);
        }
        LOG.debugv("IoT Hub: wrote device {0} of hub {1}", deviceId, context.hub());
        return json(200, deviceToWire(written));
    }

    /** {@code DELETE /devices/{id}}: {@code If-Match} names the ETag to delete, and {@code *} or none any. */
    private Response delete(AzureRequest req, Context context, String deviceId) {
        String ifMatch = ifMatch(req);
        synchronized (devices.writeLock()) {
            Optional<Device> existing = devices.get(context.cloud(), context.hub(), deviceId);
            if (existing.isEmpty()) {
                return deviceNotFound(deviceId);
            }
            if (ifMatch != null && !"*".equals(ifMatch) && !ifMatch.equals(existing.get().etag())) {
                return preconditionFailed(deviceId);
            }
            devices.delete(context.cloud(), context.hub(), deviceId);
        }
        return Response.noContent().build();
    }

    /**
     * {@code POST /devices/keys/regenerate}: new keys for the listed {@code sas} devices, the primary, the secondary or
     * both as {@code policyKey} names. A device that is missing or does not use symmetric keys is reported in
     * {@code errors} and the others are renewed.
     */
    @SuppressWarnings("unchecked")
    private Response regenerateKeys(AzureRequest req, Context context) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        String policyKey = body.get("policyKey") instanceof String sent
                ? List.of(PRIMARY_KEY, SECONDARY_KEY, BOTH_KEYS).stream()
                        .filter(known -> known.equalsIgnoreCase(sent)).findFirst().orElse(null)
                : null;
        if (policyKey == null) {
            return error(400, "ArgumentInvalid", "The policyKey must be one of primaryKey, secondaryKey and both.");
        }
        if (!(body.get("devices") instanceof List<?> sentDevices) || sentDevices.isEmpty()
                || !sentDevices.stream().allMatch(entry -> entry instanceof Map<?, ?> map
                        && map.get("id") instanceof String)) {
            return error(400, "ArgumentInvalid", "The devices must be a list of objects with an id.");
        }
        List<Map<String, Object>> rotated = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        synchronized (devices.writeLock()) {
            if (arm.findByName(req, context.hub()).isEmpty()) {
                return error(404, "IotHubNotFound", "IoT hub '" + context.hub() + "' does not exist.");
            }
            for (Object entry : sentDevices) {
                String deviceId = (String) ((Map<String, Object>) entry).get("id");
                Optional<Device> existing = devices.get(context.cloud(), context.hub(), deviceId);
                if (existing.isEmpty() || !SAS.equals(existing.get().authType())) {
                    Map<String, Object> failure = new LinkedHashMap<>();
                    failure.put("id", deviceId);
                    failure.put("errorCode", existing.isEmpty() ? "DeviceNotFound" : "ArgumentInvalid");
                    failure.put("errorStatus", existing.isEmpty() ? "Device '" + deviceId + "' is not registered."
                            : "Device '" + deviceId + "' does not authenticate with symmetric keys.");
                    errors.add(failure);
                    continue;
                }
                Device device = existing.get();
                boolean primary = !SECONDARY_KEY.equals(policyKey);
                boolean secondary = !PRIMARY_KEY.equals(policyKey);
                Device renewed = new Device(device.deviceId(), device.generationId(), device.version() + 1,
                        device.status(), device.statusReason(), device.statusUpdatedTime(), device.authType(),
                        primary ? IotHubKeys.newKey(random) : device.primaryKey(),
                        secondary ? IotHubKeys.newKey(random) : device.secondaryKey(), device.primaryThumbprint(),
                        device.secondaryThumbprint(), device.iotEdge(), device.deviceScope(), device.parentScopes());
                devices.put(context.cloud(), context.hub(), renewed);
                Map<String, Object> keys = new LinkedHashMap<>();
                keys.put("id", deviceId);
                keys.put("primaryKey", renewed.primaryKey());
                keys.put("secondaryKey", renewed.secondaryKey());
                rotated.add(keys);
            }
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("policyKey", policyKey);
        answer.put("errors", errors);
        answer.put("rotatedKeys", rotated);
        return json(200, answer);
    }

    /** {@code GET /devices}: the hub's identities, at most {@code top} of them (1000 when none is given). */
    private Response listDevices(AzureRequest req, Context context) {
        int top = pageSize(req.queryParams().get("top"), MAX_PAGE);
        List<Map<String, Object>> page = devices.list(context.cloud(), context.hub()).stream()
                .limit(top)
                .map(IotHubDataPlane::deviceToWire)
                .toList();
        return json(200, page);
    }

    /** The device a {@code PUT} leaves: the body over Azure's defaults, keeping what an identity never changes. */
    private Device device(String deviceId, Map<String, Object> body, Optional<Device> existing) {
        String status = status(body.get("status"), existing);
        String statusReason = optionalString(body.get("statusReason"), "statusReason");
        if (statusReason != null && statusReason.length() > MAX_STATUS_REASON) {
            throw refused("The statusReason is longer than " + MAX_STATUS_REASON + " characters.");
        }
        String now = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
        boolean statusChanged = existing.map(device -> !device.status().equals(status)).orElse(true);
        String generationId = existing.map(Device::generationId).orElseGet(this::newGenerationId);

        Map<String, Object> authentication = object(body.get("authentication"), "authentication");
        String authType = authType(authentication.get("type"));
        Map<String, Object> symmetricKey = object(authentication.get("symmetricKey"), "authentication.symmetricKey");
        Map<String, Object> thumbprint = object(authentication.get("x509Thumbprint"),
                "authentication.x509Thumbprint");
        String primaryKey = null;
        String secondaryKey = null;
        String primaryThumbprint = null;
        String secondaryThumbprint = null;
        if (SAS.equals(authType)) {
            primaryKey = keyOrNew(symmetricKey.get("primaryKey"), "primaryKey");
            secondaryKey = keyOrNew(symmetricKey.get("secondaryKey"), "secondaryKey");
        } else if (SELF_SIGNED.equals(authType)) {
            primaryThumbprint = optionalString(thumbprint.get("primaryThumbprint"), "primaryThumbprint");
            secondaryThumbprint = optionalString(thumbprint.get("secondaryThumbprint"), "secondaryThumbprint");
            if (primaryThumbprint == null || primaryThumbprint.isBlank()) {
                throw refused("A selfSigned device needs a primaryThumbprint.");
            }
        }

        Map<String, Object> capabilities = object(body.get("capabilities"), "capabilities");
        boolean iotEdge = Boolean.TRUE.equals(capabilities.get("iotEdge"));
        String deviceScope = optionalString(body.get("deviceScope"), "deviceScope");
        if (iotEdge) {
            // An edge device's scope is its own, generated once and kept. A leaf that becomes an edge device gets a new
            // one: the scope it had was its parent's.
            deviceScope = existing.filter(Device::iotEdge).map(Device::deviceScope)
                    .orElse("ms-azure-iot-edge://" + deviceId + "-" + generationId);
        }
        return new Device(deviceId, generationId, existing.map(device -> device.version() + 1).orElse(1L), status,
                statusReason, statusChanged ? now : existing.get().statusUpdatedTime(), authType, primaryKey,
                secondaryKey, primaryThumbprint, secondaryThumbprint, iotEdge, deviceScope,
                stringList(body.get("parentScopes")));
    }

    /** The {@code status} sent, in any case, or the device's own; a new device is enabled. */
    private static String status(Object sent, Optional<Device> existing) {
        if (sent == null) {
            return existing.map(Device::status).orElse(ENABLED);
        }
        if (sent instanceof String text) {
            String lower = text.toLowerCase(Locale.ROOT);
            if (ENABLED.equals(lower) || DISABLED.equals(lower)) {
                return lower;
            }
        }
        throw refused("The status '" + sent + "' is not one of enabled and disabled.");
    }

    /** The authentication type sent, in Azure's spelling; {@code sas} when none is. */
    private static String authType(Object sent) {
        if (sent == null) {
            return SAS;
        }
        if (sent instanceof String text) {
            Optional<String> known = AUTH_TYPES.stream().filter(type -> type.equalsIgnoreCase(text)).findFirst();
            if (known.isPresent()) {
                return known.get();
            }
        }
        throw refused("The authentication type '" + sent + "' is not one of " + String.join(", ", AUTH_TYPES) + ".");
    }

    /** The key sent, which must be base64 of 16 to 64 bytes, or a new one. */
    private String keyOrNew(Object sent, String field) {
        String key = optionalString(sent, field);
        if (key == null || key.isEmpty()) {
            return IotHubKeys.newKey(random);
        }
        try {
            int length = Base64.getDecoder().decode(key).length;
            if (length >= MIN_KEY_BYTES && length <= MAX_KEY_BYTES) {
                return key;
            }
        } catch (IllegalArgumentException ignored) {
            // Not base64: safe to drop, since the refusal below answers it as it answers a key of the wrong length.
        }
        throw refused("The " + field + " must be base64 of " + MIN_KEY_BYTES + " to " + MAX_KEY_BYTES + " bytes.");
    }

    /** A generation ID in the shape of Azure's: a tick count, unique to this identity. */
    private String newGenerationId() {
        long ticks = EPOCH_TICKS + Instant.now().toEpochMilli() * TICKS_PER_MILLI + random.nextInt((int) TICKS_PER_MILLI);
        return Long.toString(ticks);
    }

    private static void refuseDeviceId(String deviceId) {
        if (!DEVICE_ID.matcher(deviceId).matches()) {
            throw new Refused(error(400, "ArgumentInvalid", "The device ID '" + deviceId + "' is not valid: it must "
                    + "be 1 to 128 ASCII letters, digits and the characters - . % _ * ? ! ( ) , : = @ $ '."));
        }
    }

    // ── Query ────────────────────────────────────────────────────────────────

    /**
     * {@code POST /devices/query}: the device twins, for the two queries the az CLI sends. Each page holds
     * {@code x-ms-max-item-count} twins, and a page that is not the last carries a {@code x-ms-continuation} token
     * for the next one.
     */
    private Response query(AzureRequest req, Context context) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Object sent = body.get("query");
        String normalized = sent instanceof String text
                ? text.trim().replaceAll("\\s+", " ").replaceAll("\\s*=\\s*", " = ").toLowerCase(Locale.ROOT) : "";
        boolean edgeOnly = EDGE_DEVICES.equals(normalized);
        if (!edgeOnly && !ALL_DEVICES.equals(normalized)) {
            return error(400, "SqlQueryInvalid", "floci-az serves only the queries '" + ALL_DEVICES + "' and '"
                    + EDGE_DEVICES + "'.");
        }
        List<Device> matching = devices.list(context.cloud(), context.hub()).stream()
                .filter(device -> !edgeOnly || device.iotEdge())
                .toList();
        int pageSize = pageSize(header(req, "x-ms-max-item-count"), DEFAULT_PAGE);
        int offset = continuation(header(req, CONTINUATION));
        // The offset is clamped before the page is measured from it: a token's offset can be any int, and adding the
        // page size to one near the top would overflow. An offset past the end, as when devices were deleted between
        // pages, answers an empty last page.
        int start = Math.min(offset, matching.size());
        int end = start + Math.min(pageSize, matching.size() - start);
        List<Map<String, Object>> page = new ArrayList<>();
        for (Device device : matching.subList(start, end)) {
            page.add(twinToWire(device));
        }
        Response.ResponseBuilder answer = Response.ok(write(page)).type(JSON).header("x-ms-item-type", "Twin");
        if (end < matching.size()) {
            answer.header(CONTINUATION, Base64.getEncoder().encodeToString(
                    Integer.toString(end).getBytes(StandardCharsets.US_ASCII)));
        }
        return answer.build();
    }

    /** The offset a continuation token stands for; a token floci-az did not issue is refused. */
    private static int continuation(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(new String(Base64.getDecoder().decode(token), StandardCharsets.US_ASCII));
            if (offset >= 0) {
                return offset;
            }
        } catch (IllegalArgumentException ignored) {
            // Not a token floci-az issued: safe to drop, since the refusal below answers it.
        }
        throw new Refused(error(400, "ArgumentInvalid", "The continuation token is not valid."));
    }

    private static int pageSize(String sent, int fallback) {
        if (sent == null || sent.isBlank()) {
            return fallback;
        }
        try {
            int size = Integer.parseInt(sent.trim());
            if (size > 0) {
                return Math.min(size, MAX_PAGE);
            }
        } catch (NumberFormatException ignored) {
            // Not a number: safe to drop, since the refusal below answers it.
        }
        throw refused("The page size '" + sent + "' is not a positive number.");
    }

    // ── Wire shapes ──────────────────────────────────────────────────────────

    /** The service API's {@code Device}. */
    static Map<String, Object> deviceToWire(Device device) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceId", device.deviceId());
        body.put("generationId", device.generationId());
        body.put("etag", device.etag());
        body.put("connectionState", "Disconnected");
        body.put("status", device.status());
        body.put("statusReason", device.statusReason());
        body.put("connectionStateUpdatedTime", NEVER);
        body.put("statusUpdatedTime", device.statusUpdatedTime());
        body.put("lastActivityTime", NEVER);
        body.put("cloudToDeviceMessageCount", 0);
        body.put("authentication", authentication(device));
        body.put("capabilities", Map.of("iotEdge", device.iotEdge()));
        if (device.deviceScope() != null) {
            body.put("deviceScope", device.deviceScope());
        }
        body.put("parentScopes", device.parentScopes());
        return body;
    }

    private static Map<String, Object> authentication(Device device) {
        Map<String, Object> symmetricKey = new LinkedHashMap<>();
        symmetricKey.put("primaryKey", device.primaryKey());
        symmetricKey.put("secondaryKey", device.secondaryKey());
        Map<String, Object> thumbprint = new LinkedHashMap<>();
        thumbprint.put("primaryThumbprint", device.primaryThumbprint());
        thumbprint.put("secondaryThumbprint", device.secondaryThumbprint());
        Map<String, Object> authentication = new LinkedHashMap<>();
        authentication.put("symmetricKey", symmetricKey);
        authentication.put("x509Thumbprint", thumbprint);
        authentication.put("type", device.authType());
        return authentication;
    }

    /**
     * A device's twin as a query answers it. floci-az serves no twin properties yet, so the desired and reported
     * properties are empty, at version 1.
     */
    static Map<String, Object> twinToWire(Device device) {
        Map<String, Object> twin = new LinkedHashMap<>();
        twin.put("deviceId", device.deviceId());
        twin.put("etag", "AAAAAAAAAAE=");
        twin.put("deviceEtag", device.etag());
        twin.put("status", device.status());
        twin.put("statusReason", device.statusReason());
        twin.put("statusUpdateTime", device.statusUpdatedTime());
        twin.put("connectionState", "Disconnected");
        twin.put("lastActivityTime", NEVER);
        twin.put("cloudToDeviceMessageCount", 0);
        twin.put("authenticationType", device.authType());
        Map<String, Object> thumbprint = new LinkedHashMap<>();
        thumbprint.put("primaryThumbprint", device.primaryThumbprint());
        thumbprint.put("secondaryThumbprint", device.secondaryThumbprint());
        twin.put("x509Thumbprint", thumbprint);
        twin.put("modelId", "");
        twin.put("version", 1);
        twin.put("capabilities", Map.of("iotEdge", device.iotEdge()));
        if (device.deviceScope() != null) {
            twin.put("deviceScope", device.deviceScope());
        }
        twin.put("parentScopes", device.parentScopes());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("desired", emptyProperties(device.statusUpdatedTime()));
        properties.put("reported", emptyProperties(device.statusUpdatedTime()));
        twin.put("properties", properties);
        return twin;
    }

    private static Map<String, Object> emptyProperties(String lastUpdated) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("$metadata", Map.of("$lastUpdated", lastUpdated));
        properties.put("$version", 1);
        return properties;
    }

    // ── Request reading ──────────────────────────────────────────────────────

    /**
     * The path's segments, each percent-decoded once. They are read from the path as the client encoded it, since a
     * device ID may hold {@code ?} or {@code %}, which a decoded path would mistake for a query or an escape.
     */
    private static List<String> segments(AzureRequest req) {
        String path = req.rawPath() != null ? req.rawPath() : req.resourcePath();
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                // URLDecoder would turn '+' into a space; in a path it is a literal '+'.
                segments.add(URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8));
            }
        }
        return segments;
    }

    /** {@code If-Match} without its quotes or weak marker, or null when it is absent. */
    private static String ifMatch(AzureRequest req) {
        String value = header(req, "If-Match");
        if (value == null || value.isBlank()) {
            return null;
        }
        String etag = value.trim();
        if (etag.startsWith("W/")) {
            etag = etag.substring(2);
        }
        if (etag.length() >= 2 && etag.startsWith("\"") && etag.endsWith("\"")) {
            etag = etag.substring(1, etag.length() - 1);
        }
        return etag;
    }

    private static String header(AzureRequest req, String name) {
        return req.headers() == null ? null : req.headers().getHeaderString(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object sent, String field) {
        if (sent == null) {
            return Map.of();
        }
        if (sent instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw refused("The " + field + " is not a JSON object.");
    }

    private static String optionalString(Object sent, String field) {
        if (sent == null || sent instanceof String) {
            return (String) sent;
        }
        throw refused("The " + field + " is not a string.");
    }

    private static List<String> stringList(Object sent) {
        if (sent == null) {
            return List.of();
        }
        if (sent instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return list.stream().map(String.class::cast).toList();
        }
        throw refused("The parentScopes is not a list of strings.");
    }

    // ── Answers ──────────────────────────────────────────────────────────────

    private static Response json(int status, Object body) {
        return Response.status(status).type(JSON).entity(write(body)).build();
    }

    private static String write(Object body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("An IoT Hub answer could not be written as JSON", e);
        }
    }

    /** An error in the shape the IoT Hub service SDKs parse, with its code in {@code iothub-errorcode}. */
    static Response error(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Message", "ErrorCode:" + code + ";" + message);
        body.put("ExceptionMessage", "Tracking ID:" + UUID.randomUUID().toString().replace("-", "")
                + "-G:0-TimeStamp:" + TRACKING_TIME.format(Instant.now()));
        return Response.status(status).type(JSON).header(ERROR_CODE_HEADER, code).entity(write(body)).build();
    }

    private static Refused refused(String message) {
        return new Refused(error(400, "ArgumentInvalid", message));
    }

    private static Response deviceNotFound(String deviceId) {
        return error(404, "DeviceNotFound", "Device '" + deviceId + "' is not registered.");
    }

    private static Response preconditionFailed(String deviceId) {
        return error(412, "PreconditionFailed", "The ETag of device '" + deviceId
                + "' does not match the one in If-Match.");
    }

    private static Response methodNotAllowed() {
        return error(405, "MethodNotAllowed", "The method is not allowed on this resource.");
    }
}
