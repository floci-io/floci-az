package io.floci.az.services.eventgrid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.core.Resettable;
import io.floci.az.services.blob.BlobServiceHandler;
import io.floci.az.services.eventgrid.EventGridModels.DeadLetterDestination;
import io.floci.az.services.eventgrid.EventGridModels.EventSubscription;
import io.floci.az.services.eventgrid.EventGridModels.RetryPolicy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Delivers Event Grid notifications to subscriber webhooks.
 *
 * <p>Delivery is asynchronous and retried per the subscription's {@link RetryPolicy} with
 * exponential backoff. The subscription validation handshake (run synchronously when a webhook
 * subscription is created) and the CloudEvents abuse-protection probe live here too, since they
 * share the same outbound HTTP machinery. An event that exhausts its attempts is written to the
 * subscription's {@code StorageBlob} dead-letter destination through the in-process blob service,
 * or dropped when there is none.
 *
 * <p>Queued and in-flight deliveries are emulator state: {@code POST /_admin/reset} cancels them, so
 * an event published before a reset is neither retried nor dead-lettered after it.
 */
@ApplicationScoped
public class EventGridDelivery implements Resettable {

    private static final Logger LOG = Logger.getLogger(EventGridDelivery.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final long BACKOFF_BASE_MS = 200;
    private static final long BACKOFF_CAP_MS = 30_000;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final String DEAD_LETTER_STORAGE_BLOB = "StorageBlob";
    private static final String REASON_MAX_ATTEMPTS = "MaxDeliveryAttemptsExceeded";

    private final BlobServiceHandler blobService;
    private final Object resetLock = new Object();
    private HttpClient httpClient;
    private ScheduledThreadPoolExecutor scheduler;
    private long generation;

    @Inject
    public EventGridDelivery(BlobServiceHandler blobService) {
        this.blobService = blobService;
    }

    @PostConstruct
    void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "eventgrid-delivery");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Cancels every delivery accepted so far. Queued attempts and retries are discarded, and an
     * attempt already on the wire belongs to the previous generation, so it neither retries nor
     * dead-letters. Sharing {@code resetLock} with the dead-letter write means that once this
     * returns no event from before the reset can still reach a blob container.
     */
    @Override
    public void clear() {
        synchronized (resetLock) {
            generation++;
        }
        scheduler.getQueue().clear();
    }

    private long currentGeneration() {
        synchronized (resetLock) {
            return generation;
        }
    }

    @PreDestroy
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * One event on its way to one subscription, plus the time it was accepted for delivery and the
     * reset generation it was accepted in.
     */
    private record Pending(EventSubscription sub, byte[] body, String contentType, Instant publishTime,
                           long generation) {
    }

    /** What the last delivery attempt observed: a documented outcome and, when one came back, the HTTP status. */
    private record Outcome(String lastDeliveryOutcome, Integer lastHttpStatusCode, Instant attemptTime) {
    }

    /**
     * Schedules delivery of a single already-rendered event to a subscription's webhook, retrying
     * on failure. {@code body} is a JSON array containing the one event (Event Grid always POSTs
     * an array).
     */
    public void deliver(EventSubscription sub, byte[] body, String contentType) {
        attempt(new Pending(sub, body, contentType, Instant.now(), currentGeneration()), 1);
    }

    private void attempt(Pending p, int attempt) {
        scheduler.execute(() -> {
            if (p.generation() != currentGeneration()) {
                return;
            }
            EventSubscription sub = p.sub();
            int maxAttempts = Math.max(1, sub.retryPolicy().maxDeliveryAttempts());
            Outcome outcome;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(sub.endpointUrl()))
                        .timeout(HTTP_TIMEOUT)
                        .header("Content-Type", p.contentType())
                        .header("aeg-event-type", "Notification")
                        .header("aeg-subscription-name", sub.name())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(p.body()))
                        .build();
                HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    LOG.debugv("Delivered event to {0} (attempt {1}, status {2})",
                            sub.endpointUrl(), attempt, resp.statusCode());
                    return;
                }
                LOG.debugv("Delivery to {0} got status {1} (attempt {2}/{3})",
                        sub.endpointUrl(), resp.statusCode(), attempt, maxAttempts);
                outcome = new Outcome(outcomeForStatus(resp.statusCode()), resp.statusCode(), Instant.now());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.debugv("Delivery to {0} interrupted (attempt {1}/{2})", sub.endpointUrl(), attempt, maxAttempts);
                return;
            } catch (Exception e) {
                LOG.debugv("Delivery to {0} failed (attempt {1}/{2}): {3}",
                        sub.endpointUrl(), attempt, maxAttempts, e.getMessage());
                outcome = new Outcome(outcomeForException(e), null, Instant.now());
            }
            reschedule(p, attempt, maxAttempts, outcome);
        });
    }

    private void reschedule(Pending p, int attempt, int maxAttempts, Outcome outcome) {
        if (p.generation() != currentGeneration()) {
            LOG.debugv("Delivery to {0} (subscription {1}) abandoned: the emulator was reset",
                    p.sub().endpointUrl(), p.sub().name());
            return;
        }
        if (attempt >= maxAttempts) {
            deadLetterOrDrop(p, attempt, outcome);
            return;
        }
        long delay = Math.min(BACKOFF_BASE_MS * (1L << Math.min(attempt - 1, 20)), BACKOFF_CAP_MS);
        scheduler.schedule(() -> attempt(p, attempt + 1), delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Ends delivery of an event that exhausted its attempts. Without a dead-letter destination the
     * event is dropped, as Azure does. With a {@code StorageBlob} destination it is written to the
     * container as {@code <SUBSCRIPTION NAME IN UPPER CASE>/<yyyy>/<M>/<d>/<H>/<guid>.json} (UTC,
     * not zero-padded), holding a JSON array of the event with the dead-letter properties appended,
     * the layout and payload Azure documents. A destination that cannot be resolved drops the event.
     */
    private void deadLetterOrDrop(Pending p, int attempts, Outcome outcome) {
        EventSubscription sub = p.sub();
        DeadLetterDestination destination = sub.resolveDeadLetterDestination();
        if (destination == null) {
            LOG.warnv("Event Grid delivery to {0} (subscription {1}) exhausted {2} attempts; "
                    + "no dead-letter destination, event dropped", sub.endpointUrl(), sub.name(), attempts);
            return;
        }
        String account = storageAccountName(destination.resourceId());
        if (!DEAD_LETTER_STORAGE_BLOB.equalsIgnoreCase(destination.endpointType())
                || account == null || destination.blobContainerName() == null) {
            LOG.warnv("Event Grid subscription {0} has an unusable dead-letter destination "
                    + "(endpointType={1}, resourceId={2}, blobContainerName={3}); event dropped",
                    sub.name(), destination.endpointType(), destination.resourceId(),
                    destination.blobContainerName());
            return;
        }
        try {
            byte[] payload = deadLetterPayload(p, attempts, outcome);
            String blobName = deadLetterBlobName(sub.name(), outcome.attemptTime());
            boolean written;
            synchronized (resetLock) {
                if (p.generation() != generation) {
                    LOG.debugv("Event Grid subscription {0} skipped dead-lettering: the emulator was reset",
                            sub.name());
                    return;
                }
                written = blobService.putBlockBlob(account, destination.blobContainerName(), blobName,
                        payload, "application/json");
            }
            if (written) {
                LOG.infov("Event Grid subscription {0} dead-lettered an event to {1}/{2}/{3} after {4} attempts",
                        sub.name(), account, destination.blobContainerName(), blobName, attempts);
            } else {
                LOG.warnv("Event Grid dead-letter container {0}/{1} for subscription {2} does not exist; event dropped",
                        account, destination.blobContainerName(), sub.name());
            }
        } catch (IOException e) {
            LOG.warnv("Event Grid subscription {0} could not build the dead-letter payload: {1}; event dropped",
                    sub.name(), e.getMessage());
        }
    }

    private byte[] deadLetterPayload(Pending p, int attempts, Outcome outcome) throws IOException {
        boolean cloudEvents = EventGridModels.SCHEMA_CLOUD_EVENT.equalsIgnoreCase(p.sub().eventDeliverySchema());
        JsonNode root = MAPPER.readTree(p.body());
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode event : root.isArray() ? root : MAPPER.createArrayNode().add(root)) {
            ObjectNode copy = event.isObject() ? ((ObjectNode) event).deepCopy() : MAPPER.createObjectNode();
            if (cloudEvents) {
                copy.put("deadletterreason", REASON_MAX_ATTEMPTS);
                copy.put("deliveryattempts", attempts);
                copy.put("lastdeliveryoutcome", outcome.lastDeliveryOutcome());
                copy.put("publishtime", p.publishTime().toString());
            } else {
                copy.put("deadLetterReason", REASON_MAX_ATTEMPTS);
                copy.put("deliveryAttempts", attempts);
                copy.put("lastDeliveryOutcome", outcome.lastDeliveryOutcome());
                if (outcome.lastHttpStatusCode() != null) {
                    copy.put("lastHttpStatusCode", outcome.lastHttpStatusCode());
                }
                copy.put("publishTime", p.publishTime().toString());
                copy.put("lastDeliveryAttemptTime", outcome.attemptTime().toString());
            }
            out.add(copy);
        }
        return MAPPER.writeValueAsBytes(out);
    }

    static String deadLetterBlobName(String subscriptionName, Instant at) {
        ZonedDateTime utc = at.atZone(ZoneOffset.UTC);
        return subscriptionName.toUpperCase(Locale.ROOT) + "/" + utc.getYear() + "/" + utc.getMonthValue()
                + "/" + utc.getDayOfMonth() + "/" + utc.getHour() + "/" + UUID.randomUUID() + ".json";
    }

    /** The storage account name from a {@code Microsoft.Storage/storageAccounts} ARM id, or {@code null}. */
    static String storageAccountName(String resourceId) {
        if (resourceId == null) {
            return null;
        }
        String[] parts = resourceId.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("storageAccounts".equalsIgnoreCase(parts[i]) && !parts[i + 1].isBlank()) {
                return parts[i + 1];
            }
        }
        return null;
    }

    /**
     * Maps a non-2xx webhook status to a documented {@code lastDeliveryOutcome}. Statuses the
     * documented list has no entry for fall back to {@code Busy} (5xx) or {@code BadRequest} (4xx).
     */
    static String outcomeForStatus(int status) {
        return switch (status) {
            case 400 -> "BadRequest";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "NotFound";
            case 408 -> "TimedOut";
            case 413 -> "PayloadTooLarge";
            default -> status >= 500 || status == 429 ? "Busy" : "BadRequest";
        };
    }

    private static String outcomeForException(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException) {
                return "TimedOut";
            }
            if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) {
                return "ResolutionError";
            }
        }
        return "SocketError";
    }

    /**
     * Runs the subscription validation handshake for a webhook destination. Tolerant by design:
     * any failure is logged and reported as not-validated, but the caller still provisions the
     * subscription so local development is not blocked by an unreachable endpoint.
     *
     * @return {@code true} when the subscriber proved ownership (echoed the validation code, passed
     *         the CloudEvents abuse-protection probe, or returned 2xx).
     */
    public boolean validate(EventSubscription sub, String topicResourceId) {
        if (EventGridModels.SCHEMA_CLOUD_EVENT.equalsIgnoreCase(sub.eventDeliverySchema())) {
            return validateCloudEvents(sub);
        }
        return validateEventGrid(sub, topicResourceId);
    }

    private boolean validateEventGrid(EventSubscription sub, String topicResourceId) {
        String code = UUID.randomUUID().toString();
        String validationUrl = sub.endpointUrl() + (sub.endpointUrl().contains("?") ? "&" : "?")
                + "id=" + code;
        Map<String, Object> event = Map.of(
                "id", UUID.randomUUID().toString(),
                "topic", topicResourceId,
                "subject", "",
                "eventType", "Microsoft.EventGrid.SubscriptionValidationEvent",
                "eventTime", OffsetDateTime.now(ZoneOffset.UTC).toString(),
                "metadataVersion", "1",
                "dataVersion", "1",
                "data", Map.of("validationCode", code, "validationUrl", validationUrl));
        try {
            byte[] body = MAPPER.writeValueAsBytes(List.of(event));
            HttpRequest req = HttpRequest.newBuilder(URI.create(sub.endpointUrl()))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("aeg-event-type", "SubscriptionValidation")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return false;
            }
            String echoed = extractValidationResponse(resp.body());
            return code.equals(echoed) || echoed == null;
        } catch (Exception e) {
            LOG.warnv("Subscription validation POST to {0} failed: {1}", sub.endpointUrl(), e.getMessage());
            return false;
        }
    }

    private String extractValidationResponse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(body).path("validationResponse");
            return node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean validateCloudEvents(EventSubscription sub) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(sub.endpointUrl()))
                    .timeout(HTTP_TIMEOUT)
                    .header("WebHook-Request-Origin", "eventgrid.azure.net")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            boolean allowed = resp.headers().firstValue("WebHook-Allowed-Origin").isPresent();
            return allowed && resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            LOG.warnv("CloudEvents abuse-protection probe to {0} failed: {1}", sub.endpointUrl(), e.getMessage());
            return false;
        }
    }
}
