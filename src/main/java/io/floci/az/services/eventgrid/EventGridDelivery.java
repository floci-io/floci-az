package io.floci.az.services.eventgrid;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.eventgrid.EventGridModels.EventSubscription;
import io.floci.az.services.eventgrid.EventGridModels.RetryPolicy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Delivers Event Grid notifications to subscriber webhooks.
 *
 * <p>Delivery is asynchronous and retried per the subscription's {@link RetryPolicy} with
 * exponential backoff. The subscription validation handshake (run synchronously when a webhook
 * subscription is created) and the CloudEvents abuse-protection probe live here too, since they
 * share the same outbound HTTP machinery.
 */
@ApplicationScoped
public class EventGridDelivery {

    private static final Logger LOG = Logger.getLogger(EventGridDelivery.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final long BACKOFF_BASE_MS = 200;
    private static final long BACKOFF_CAP_MS = 30_000;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private HttpClient httpClient;
    private ScheduledExecutorService scheduler;

    @PostConstruct
    void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "eventgrid-delivery");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Schedules delivery of a single already-rendered event to a subscription's webhook, retrying
     * on failure. {@code body} is a JSON array containing the one event (Event Grid always POSTs
     * an array).
     */
    public void deliver(EventSubscription sub, byte[] body, String contentType) {
        attempt(sub, body, contentType, 1);
    }

    private void attempt(EventSubscription sub, byte[] body, String contentType, int attempt) {
        scheduler.execute(() -> {
            int maxAttempts = Math.max(1, sub.retryPolicy().maxDeliveryAttempts());
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(sub.endpointUrl()))
                        .timeout(HTTP_TIMEOUT)
                        .header("Content-Type", contentType)
                        .header("aeg-event-type", "Notification")
                        .header("aeg-subscription-name", sub.name())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
                HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    LOG.debugv("Delivered event to {0} (attempt {1}, status {2})",
                            sub.endpointUrl(), attempt, resp.statusCode());
                    return;
                }
                LOG.debugv("Delivery to {0} got status {1} (attempt {2}/{3})",
                        sub.endpointUrl(), resp.statusCode(), attempt, maxAttempts);
            } catch (Exception e) {
                LOG.debugv("Delivery to {0} failed (attempt {1}/{2}): {3}",
                        sub.endpointUrl(), attempt, maxAttempts, e.getMessage());
            }
            reschedule(sub, body, contentType, attempt, maxAttempts);
        });
    }

    private void reschedule(EventSubscription sub, byte[] body, String contentType,
                            int attempt, int maxAttempts) {
        if (attempt >= maxAttempts) {
            LOG.warnv("Event Grid delivery to {0} (subscription {1}) exhausted {2} attempts; dead-lettering (dropped)",
                    sub.endpointUrl(), sub.name(), maxAttempts);
            return;
        }
        long delay = Math.min(BACKOFF_BASE_MS * (1L << Math.min(attempt - 1, 20)), BACKOFF_CAP_MS);
        scheduler.schedule(() -> attempt(sub, body, contentType, attempt + 1),
                delay, TimeUnit.MILLISECONDS);
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
                "eventTime", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString(),
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
            var node = MAPPER.readTree(body).path("validationResponse");
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
