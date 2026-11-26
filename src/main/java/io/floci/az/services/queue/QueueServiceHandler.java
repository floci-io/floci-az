package io.floci.az.services.queue;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureErrorResponse;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.SignedIdentifiers;
import io.floci.az.core.StoredObject;
import io.floci.az.core.XmlBuilder;
import io.floci.az.core.XmlUtils;
import io.floci.az.core.auth.AuthenticationFailed;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@ApplicationScoped
public class QueueServiceHandler implements AzureServiceHandler, Resettable {

    private static final Logger LOGGER = Logger.getLogger(QueueServiceHandler.class);
    private static final AtomicLong SEND_SEQUENCE = new AtomicLong();
    private static final DateTimeFormatter RFC1123_DATE_TIME = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneId.of("GMT"));

    private static final String NS_PREFIX = "__ns__:";
    private static final StoredObject NS_SENTINEL =
            new StoredObject("", new byte[0], Map.of(), Instant.EPOCH, "");
    private static final long DEFAULT_MESSAGE_TTL_SECONDS = 604800;
    private static final long DEFAULT_VISIBILITY_TIMEOUT_SECONDS = 30;
    private static final long MAX_VISIBILITY_TIMEOUT_SECONDS = 604800;
    // Below this, a stored _visibleAt predates epoch-millis storage and is in epoch seconds.
    private static final long LEGACY_EPOCH_SECONDS_CEILING = 100_000_000_000L;
    private static final String NEVER_EXPIRES = "Fri, 31 Dec 9999 23:59:59 GMT";
    // Permission letters a queue's stored access policy may grant: read, add, update, process.
    private static final String QUEUE_SAS_PERMISSIONS = "raup";
    private static final int MESSAGE_LOCK_COUNT = 64;

    private final StorageBackend<String, StoredObject> store;
    private final XmlMapper xmlMapper = new XmlMapper();

    private final EmulatorConfig config;
    // Set Queue Metadata and Set Queue ACL each rewrite one half of the queue record; serialise them
    // so neither drops the other's half. Create and delete take the same lock, so a rewrite cannot
    // bring back a queue deleted between its read and its write.
    private final Object queueRecordLock = new Object();

    // Every step that reads a queue's messages and then writes or removes them holds the queue's message
    // lock. The store has no compare-and-set, so without it two receives both see one message as visible
    // and both return it, and a receive can write back a message that Delete or Clear Messages removed
    // after the receive read it. A queue always maps to the same one of a fixed set of locks, so the set
    // does not grow with the queues that come and go; two queues that share a lock only wait for each other.
    private final Object[] messageLocks = new Object[MESSAGE_LOCK_COUNT];

    @Inject
    public QueueServiceHandler(StorageFactory storageFactory, EmulatorConfig config) {
        this.config = config;
        this.store = storageFactory.create("queue");
        Arrays.setAll(messageLocks, i -> new Object());
    }

    @Override
    public String getServiceType() {
        return "queue";
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().queue().enabled();
    }


    @Override

    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .host(".queue.core.windows.net")
                .account("-queue", "queue")
                .build();

    }

    @Override
    public Response handle(AzureRequest request) {
        String path = request.resourcePath();
        String method = request.method();
        Map<String, String> query = request.queryParams();

        LOGGER.infof("QueueService handling: %s %s", method, path);

        Response response;
        if (request.authContext() != null && !request.authContext().isValid()) {
            response = AuthenticationFailed.xml(request.authContext().failureDetail());
        } else if (path.isEmpty() || path.equals("/")) {
            if ("GET".equalsIgnoreCase(method) && "list".equals(query.get("comp"))) {
                response = listQueues(request);
            } else if ("service".equals(query.get("restype")) && "properties".equals(query.get("comp"))) {
                if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
                    response = getQueueServiceProperties();
                } else if ("PUT".equalsIgnoreCase(method)) {
                    response = Response.accepted().build();
                } else {
                    response = new AzureErrorResponse(
                            "NotImplemented", "The requested operation is not implemented.")
                            .toXmlResponse(501);
                }
            } else {
                response = new AzureErrorResponse("NotImplemented", "The requested operation is not implemented.")
                        .toXmlResponse(501);
            }
        } else {
            String[] parts = path.split("/");
            String queueName = parts[0];
            String subPath = parts.length > 1 ? parts[1] : "";

            if (subPath.isEmpty()) {
                if ("acl".equals(query.get("comp"))) {
                    response = handleQueueAcl(request, queueName);
                } else if ("PUT".equalsIgnoreCase(method) && "metadata".equals(query.get("comp"))) {
                    response = setQueueMetadata(request, queueName);
                } else if ("GET".equalsIgnoreCase(method) && "metadata".equals(query.get("comp"))) {
                    response = getQueueMetadata(request, queueName);
                } else if ("PUT".equalsIgnoreCase(method)) {
                    response = createQueue(request, queueName);
                } else if ("DELETE".equalsIgnoreCase(method)) {
                    response = deleteQueue(request, queueName);
                } else if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
                    response = getQueue(request, queueName);
                } else {
                    response = new AzureErrorResponse("NotImplemented", "The requested operation is not implemented.")
                            .toXmlResponse(501);
                }
            } else if ("messages".equals(subPath)) {
                if ("POST".equalsIgnoreCase(method)) {
                    response = putMessage(request, queueName);
                } else if ("GET".equalsIgnoreCase(method)) {
                    response = getMessages(request, queueName, "true".equals(query.get("peekonly")));
                } else if ("PUT".equalsIgnoreCase(method) && parts.length > 2) {
                    response = updateMessage(request, queueName, parts[2], query.get("popreceipt"));
                } else if ("DELETE".equalsIgnoreCase(method)) {
                    if (parts.length > 2) {
                        response = deleteMessage(request, queueName, parts[2], query.get("popreceipt"));
                    } else {
                        response = clearMessages(request, queueName);
                    }
                } else {
                    response = new AzureErrorResponse("NotImplemented", "The requested operation is not implemented.")
                            .toXmlResponse(501);
                }
            } else {
                response = new AzureErrorResponse("NotImplemented", "The requested operation is not implemented.")
                        .toXmlResponse(501);
            }
        }

        // An AuthenticationFailed carries the request id its message names.
        String requestId = Objects.requireNonNullElseGet(
                response.getHeaderString("x-ms-request-id"), () -> UUID.randomUUID().toString());
        return Response.fromResponse(response)
                .header("x-ms-request-id", null)
                .header("x-ms-request-id", requestId)
                .header("x-ms-version", request.headers().getHeaderString("x-ms-version"))
                .header("Date", RFC1123_DATE_TIME.format(Instant.now()))
                .build();
    }

    private Response getQueueServiceProperties() {
        String xml = new XmlBuilder()
            .start("StorageServiceProperties")
                .start("Logging")
                    .elem("Version", "1.0")
                    .elem("Delete", "false")
                    .elem("Read", "false")
                    .elem("Write", "false")
                    .start("RetentionPolicy").elem("Enabled", "false").end("RetentionPolicy")
                .end("Logging")
                .start("HourMetrics")
                    .elem("Version", "1.0")
                    .elem("Enabled", "false")
                    .start("RetentionPolicy").elem("Enabled", "false").end("RetentionPolicy")
                .end("HourMetrics")
                .start("MinuteMetrics")
                    .elem("Version", "1.0")
                    .elem("Enabled", "false")
                    .start("RetentionPolicy").elem("Enabled", "false").end("RetentionPolicy")
                .end("MinuteMetrics")
                .selfClose("Cors")
            .end("StorageServiceProperties")
            .build();
        return Response.ok(xml, "application/xml").build();
    }

    private Response createQueue(AzureRequest request, String queueName) {
        String key = nsKey(request.accountName(), queueName);
        synchronized (queueRecordLock) {
            if (store.get(key).isPresent()) {
                return Response.status(Response.Status.NO_CONTENT).build();
            }
            store.put(key,
                    new StoredObject("", new byte[0], readMetadataHeaders(request), Instant.now(), ""));
        }
        return Response.status(Response.Status.CREATED).build();
    }

    private Response deleteQueue(AzureRequest request, String queueName) {
        removeQueue(request.accountName(), queueName);
        return Response.status(Response.Status.NO_CONTENT).build();
    }

    /** Whether {@code queueName} exists in {@code accountName}, for the ARM queue resource. */
    public boolean queueExists(String accountName, String queueName) {
        return store.get(nsKey(accountName, queueName)).isPresent();
    }

    /**
     * Deletes a queue and its messages, as Delete Queue does, for the ARM queue resource.
     *
     * @return {@code false} when there was no such queue
     */
    public boolean deleteQueue(String accountName, String queueName) {
        // The check and the delete are one step under the queue record lock (re-entrant), so of two overlapping
        // deletes only one finds the queue and answers that it removed it.
        synchronized (queueRecordLock) {
            if (!queueExists(accountName, queueName)) {
                return false;
            }
            removeQueue(accountName, queueName);
            return true;
        }
    }

    private void removeQueue(String accountName, String queueName) {
        synchronized (queueRecordLock) {
            store.delete(nsKey(accountName, queueName));
        }
        removeMessages(accountName, queueName);
    }

    private void removeMessages(String accountName, String queueName) {
        String keyPrefix = objKey(accountName, queueName, "");
        synchronized (messageLock(accountName, queueName)) {
            store.keys().stream()
                    .filter(k -> k.startsWith(keyPrefix))
                    .toList()
                    .forEach(store::delete);
        }
    }

    private Response listQueues(AzureRequest request) {
        String prefix = request.queryParams().getOrDefault("prefix", "");
        String nsFilter = NS_PREFIX + request.accountName() + "/" + prefix;
        boolean includeMetadata = includes(request.queryParams().get("include"), "metadata");

        List<QueueModels.QueueItem> queues = store.keys().stream()
                .filter(k -> k.startsWith(nsFilter))
                .map(k -> {
                    String name = k.substring(NS_PREFIX.length() + request.accountName().length() + 1);
                    Map<String, String> metadata = includeMetadata
                            ? store.get(k).map(StoredObject::metadata).orElseGet(Collections::emptyMap)
                            : Collections.emptyMap();
                    return new QueueModels.QueueItem(name, metadata);
                })
                .collect(Collectors.toList());

        QueueModels.QueueListResponse response = new QueueModels.QueueListResponse(
                "http://localhost:4577/" + request.accountName(),
                prefix, "", 1000, queues, ""
        );

        return Response.ok(XmlUtils.toXml(response)).type(MediaType.APPLICATION_XML).build();
    }

    private Response getQueueMetadata(AzureRequest request, String queueName) {
        Optional<StoredObject> queue = store.get(nsKey(request.accountName(), queueName));
        if (queue.isEmpty()) {
            return new AzureErrorResponse("QueueNotFound", "The specified queue does not exist.")
                    .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
        }

        Response.ResponseBuilder rb = Response.ok()
                .header("x-ms-approximate-messages-count", approximateMessageCount(request, queueName));
        queue.get().metadata().forEach((name, value) -> rb.header("x-ms-meta-" + name, value));
        return rb.build();
    }

    private Response setQueueMetadata(AzureRequest request, String queueName) {
        String key = nsKey(request.accountName(), queueName);
        synchronized (queueRecordLock) {
            Optional<StoredObject> queue = store.get(key);
            if (queue.isEmpty()) {
                return queueNotFound();
            }
            store.put(key, new StoredObject("", queue.get().data(), readMetadataHeaders(request), Instant.now(), ""));
        }
        return Response.status(Response.Status.NO_CONTENT).build();
    }

    private Response handleQueueAcl(AzureRequest request, String queueName) {
        String method = request.method();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            return getQueueAcl(request, queueName);
        }
        if ("PUT".equalsIgnoreCase(method)) {
            return setQueueAcl(request, queueName);
        }
        return new AzureErrorResponse("NotImplemented", "The requested operation is not implemented.")
                .toXmlResponse(501);
    }

    private Response getQueueAcl(AzureRequest request, String queueName) {
        Optional<StoredObject> queue = store.get(nsKey(request.accountName(), queueName));
        if (queue.isEmpty()) {
            return queueNotFound();
        }
        byte[] stored = queue.get().data();
        String xml = stored.length == 0
                ? SignedIdentifiers.emptyXml()
                : new String(stored, StandardCharsets.UTF_8);
        return Response.ok(xml, MediaType.APPLICATION_XML).build();
    }

    private Response setQueueAcl(AzureRequest request, String queueName) {
        List<SignedIdentifiers.SignedIdentifier> identifiers;
        try {
            identifiers = SignedIdentifiers.parse(
                    new String(request.bodyStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | XMLStreamException e) {
            LOGGER.debugv("Rejecting queue ACL body for {0}: {1}", queueName, e.getMessage());
            return SignedIdentifiers.invalidXmlDocument().toXmlResponse(Response.Status.BAD_REQUEST.getStatusCode());
        }
        if (!SignedIdentifiers.isValid(identifiers, QUEUE_SAS_PERMISSIONS)) {
            return SignedIdentifiers.invalidXmlDocument().toXmlResponse(Response.Status.BAD_REQUEST.getStatusCode());
        }
        String key = nsKey(request.accountName(), queueName);
        synchronized (queueRecordLock) {
            Optional<StoredObject> queue = store.get(key);
            if (queue.isEmpty()) {
                return queueNotFound();
            }
            byte[] acl = identifiers.isEmpty()
                    ? new byte[0]
                    : SignedIdentifiers.toXml(identifiers).getBytes(StandardCharsets.UTF_8);
            store.put(key, new StoredObject("", acl, queue.get().metadata(), Instant.now(), ""));
        }
        return Response.status(Response.Status.NO_CONTENT).build();
    }

    private static Response queueNotFound() {
        return new AzureErrorResponse("QueueNotFound", "The specified queue does not exist.")
                .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
    }

    private Response getQueue(AzureRequest request, String queueName) {
        if (store.get(nsKey(request.accountName(), queueName)).isEmpty()) {
            return new AzureErrorResponse("QueueNotFound", "The specified queue does not exist.")
                    .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
        }
        return Response.ok().build();
    }

    private Response putMessage(AzureRequest request, String queueName) {
        if (store.get(nsKey(request.accountName(), queueName)).isEmpty()) {
            return new AzureErrorResponse("QueueNotFound", "The specified queue does not exist.")
                    .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
        }
        try {
            QueueModels.QueueMessageRequest msgReq = xmlMapper.readValue(request.bodyStream(), QueueModels.QueueMessageRequest.class);
            Instant now = Instant.now();
            long visibilityTimeoutSecs;
            Long ttlSecs;
            try {
                visibilityTimeoutSecs = parseLongQuery(request, "visibilitytimeout", 0);
                ttlSecs = parseMessageTtl(request);
            } catch (NumberFormatException e) {
                return new AzureErrorResponse("InvalidQueryParameterValue",
                        "visibilitytimeout and messagettl must be valid integers.")
                        .toXmlResponse(Response.Status.BAD_REQUEST.getStatusCode());
            }
            if (visibilityTimeoutSecs < 0 || visibilityTimeoutSecs > MAX_VISIBILITY_TIMEOUT_SECONDS) {
                return new AzureErrorResponse("OutOfRangeQueryParameterValue",
                        "The visibilitytimeout parameter must be between 0 and 604800.")
                        .toXmlResponse(Response.Status.BAD_REQUEST.getStatusCode());
            }

            if (ttlSecs != null && ttlSecs <= visibilityTimeoutSecs) {
                return new AzureErrorResponse("OutOfRangeQueryParameterValue",
                        "The messagettl parameter must be greater than visibilitytimeout.")
                        .toXmlResponse(Response.Status.BAD_REQUEST.getStatusCode());
            }

            String messageId = UUID.randomUUID().toString();
            String insertionTime = RFC1123_DATE_TIME.format(now);
            Instant expirationInstant = ttlSecs == null ? null : now.plusSeconds(ttlSecs);
            String expirationTime = expirationInstant == null ? NEVER_EXPIRES : RFC1123_DATE_TIME.format(expirationInstant);
            String popReceipt = UUID.randomUUID().toString();
            Instant visibleAt = now.plusSeconds(visibilityTimeoutSecs);

            Map<String, String> metadata = new HashMap<>();
            metadata.put("InsertionTime", insertionTime);
            metadata.put("_insertionEpoch", String.valueOf(now.getEpochSecond()));
            if (expirationInstant != null) {
                metadata.put("_expirationEpoch", String.valueOf(expirationInstant.getEpochSecond()));
            }
            metadata.put("MessageText", msgReq.MessageText());
            metadata.put("PopReceipt", popReceipt);
            metadata.put("DequeueCount", "0");
            metadata.put("_visibleAt", String.valueOf(visibleAt.toEpochMilli()));

            // Keys sort in send order; the counter orders messages sent in the same millisecond.
            String key = String.format("%013d-%012d-%s", System.currentTimeMillis(), SEND_SEQUENCE.incrementAndGet(), messageId);
            store.put(objKey(request.accountName(), queueName, key),
                    new StoredObject(key, msgReq.MessageText().getBytes(), metadata, Instant.now(), messageId));

            QueueModels.QueueMessageItem item = new QueueModels.QueueMessageItem(
                    messageId, insertionTime, expirationTime, popReceipt,
                    RFC1123_DATE_TIME.format(visibleAt), 0, msgReq.MessageText()
            );

            return Response.status(Response.Status.CREATED)
                    .type(MediaType.APPLICATION_XML)
                    .entity(XmlUtils.toXml(new QueueModels.QueueMessageResponse(List.of(item))))
                    .build();
        } catch (IOException e) {
            return Response.serverError().build();
        }
    }

    private Response getMessages(AzureRequest request, String queueName, boolean peekOnly) {
        if (store.get(nsKey(request.accountName(), queueName)).isEmpty()) {
            return new AzureErrorResponse("QueueNotFound", "The specified queue does not exist.")
                    .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
        }
        int numOfMessages;
        try {
            numOfMessages = Integer.parseInt(request.queryParams().getOrDefault("numofmessages", "1"));
            if (numOfMessages < 1 || numOfMessages > 32) {
                return new AzureErrorResponse("OutOfRangeQueryParameterValue",
                        "The numofmessages parameter must be between 1 and 32.")
                        .toXmlResponse(Response.Status.BAD_REQUEST.getStatusCode());
            }
        } catch (NumberFormatException e) {
            return new AzureErrorResponse("InvalidQueryParameterValue",
                    "numofmessages must be a valid integer.")
                    .toXmlResponse(Response.Status.BAD_REQUEST.getStatusCode());
        }

        long visibilityTimeoutSecs;
        try {
            visibilityTimeoutSecs = Long.parseLong(request.queryParams().getOrDefault("visibilitytimeout", String.valueOf(DEFAULT_VISIBILITY_TIMEOUT_SECONDS)));
            if (visibilityTimeoutSecs < 1 || visibilityTimeoutSecs > MAX_VISIBILITY_TIMEOUT_SECONDS) {
                return new AzureErrorResponse("OutOfRangeQueryParameterValue",
                        "The visibilitytimeout parameter must be between 1 and 604800.")
                        .toXmlResponse(Response.Status.BAD_REQUEST.getStatusCode());
            }
        } catch (NumberFormatException e) {
            visibilityTimeoutSecs = 30;
        }

        List<QueueModels.QueueMessageItem> messages = peekOnly
                ? peekMessages(request.accountName(), queueName, numOfMessages)
                : receiveMessages(request.accountName(), queueName, numOfMessages, visibilityTimeoutSecs);

        return Response.ok(XmlUtils.toXml(new QueueModels.QueueMessageResponse(messages))).type(MediaType.APPLICATION_XML).build();
    }

    /** Peek Messages changes nothing, so it needs no lock, and its items carry no pop receipt or next-visible time. */
    private List<QueueModels.QueueMessageItem> peekMessages(String accountName, String queueName, int numOfMessages) {
        return visibleMessages(accountName, queueName, Instant.now(), numOfMessages).stream()
                .map(so -> messageItem(so, null, null))
                .collect(Collectors.toList());
    }

    /**
     * Picks the visible messages and hides them in one step under the queue's message lock. Azure hides a
     * retrieved message from "other clients" for the visibility timeout, so two concurrent receives return
     * disjoint messages.
     */
    private List<QueueModels.QueueMessageItem> receiveMessages(
            String accountName, String queueName, int numOfMessages, long visibilityTimeoutSecs) {
        List<QueueModels.QueueMessageItem> received = new ArrayList<>();
        synchronized (messageLock(accountName, queueName)) {
            Instant now = Instant.now();
            Instant hiddenUntil = now.plusSeconds(visibilityTimeoutSecs);
            for (StoredObject so : visibleMessages(accountName, queueName, now, numOfMessages)) {
                Map<String, String> meta = new HashMap<>(so.metadata());
                meta.put("_visibleAt", String.valueOf(hiddenUntil.toEpochMilli()));
                int dequeueCount = Integer.parseInt(meta.getOrDefault("DequeueCount", "0")) + 1;
                meta.put("DequeueCount", String.valueOf(dequeueCount));
                meta.put("PopReceipt", UUID.randomUUID().toString());
                StoredObject hidden = new StoredObject(so.key(), so.data(), meta, so.lastModified(), so.etag());
                store.put(objKey(accountName, queueName, so.key()), hidden);
                received.add(messageItem(hidden, meta.get("PopReceipt"), RFC1123_DATE_TIME.format(hiddenUntil)));
            }
        }
        return received;
    }

    private List<StoredObject> visibleMessages(String accountName, String queueName, Instant now, int numOfMessages) {
        String keyPrefix = objKey(accountName, queueName, "");
        return store.scan(k -> k.startsWith(keyPrefix)).stream()
                .filter(so -> isVisible(so, now))
                .sorted(Comparator.comparing(StoredObject::key))
                .limit(numOfMessages)
                .collect(Collectors.toList());
    }

    private static QueueModels.QueueMessageItem messageItem(StoredObject so, String popReceipt, String timeNextVisible) {
        Map<String, String> meta = so.metadata();
        return new QueueModels.QueueMessageItem(
                so.etag(),
                meta.get("InsertionTime"),
                expirationTime(meta),
                popReceipt,
                timeNextVisible,
                Integer.parseInt(meta.getOrDefault("DequeueCount", "0")),
                new String(so.data())
        );
    }

    private boolean isVisible(StoredObject so, Instant now) {
        if (isExpired(so, now)) {
            return false;
        }
        String visibleAt = so.metadata().get("_visibleAt");
        if (visibleAt == null) return true;
        try {
            long stored = Long.parseLong(visibleAt);
            Instant deadline = stored < LEGACY_EPOCH_SECONDS_CEILING
                    ? Instant.ofEpochSecond(stored)
                    : Instant.ofEpochMilli(stored);
            return deadline.isBefore(now);
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private Response deleteMessage(AzureRequest request, String queueName, String messageId, String popReceipt) {
        if (store.get(nsKey(request.accountName(), queueName)).isEmpty()) {
            return new AzureErrorResponse("QueueNotFound", "The specified queue does not exist.")
                    .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
        }
        if (popReceipt == null || popReceipt.isBlank()) {
            return new AzureErrorResponse("InvalidQueryParameterValue",
                    "The popreceipt query parameter is required.")
                    .toXmlResponse(Response.Status.BAD_REQUEST.getStatusCode());
        }
        String keyPrefix = objKey(request.accountName(), queueName, "");
        synchronized (messageLock(request.accountName(), queueName)) {
            Optional<StoredObject> message = store.scan(k -> k.startsWith(keyPrefix)).stream()
                    .filter(so -> messageId.equals(so.etag()))
                    .findFirst();
            if (message.isEmpty() || isExpired(message.get(), Instant.now())) {
                return new AzureErrorResponse("MessageNotFound", "The specified message does not exist.")
                        .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
            }
            if (!popReceipt.equals(message.get().metadata().get("PopReceipt"))) {
                return new AzureErrorResponse("PopReceiptMismatch", "The specified pop receipt did not match.")
                        .toXmlResponse(Response.Status.BAD_REQUEST.getStatusCode());
            }
            store.delete(objKey(request.accountName(), queueName, message.get().key()));
        }
        return Response.status(Response.Status.NO_CONTENT).build();
    }

    private Response updateMessage(AzureRequest request, String queueName, String messageId, String popReceipt) {
        if (store.get(nsKey(request.accountName(), queueName)).isEmpty()) {
            return new AzureErrorResponse("QueueNotFound", "The specified queue does not exist.")
                    .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
        }
        if (popReceipt == null || popReceipt.isBlank()) {
            return new AzureErrorResponse("InvalidQueryParameterValue",
                    "The popreceipt query parameter is required.")
                    .toXmlResponse(Response.Status.BAD_REQUEST.getStatusCode());
        }

        long visibilityTimeoutSecs;
        try {
            visibilityTimeoutSecs = Long.parseLong(request.queryParams().getOrDefault("visibilitytimeout", String.valueOf(DEFAULT_VISIBILITY_TIMEOUT_SECONDS)));
            if (visibilityTimeoutSecs < 0 || visibilityTimeoutSecs > MAX_VISIBILITY_TIMEOUT_SECONDS) {
                return new AzureErrorResponse("OutOfRangeQueryParameterValue",
                        "The visibilitytimeout parameter must be between 0 and 604800.")
                        .toXmlResponse(Response.Status.BAD_REQUEST.getStatusCode());
            }
        } catch (NumberFormatException e) {
            return new AzureErrorResponse("InvalidQueryParameterValue",
                    "visibilitytimeout must be a valid integer.")
                    .toXmlResponse(Response.Status.BAD_REQUEST.getStatusCode());
        }

        // Read before the message lock is taken, so a slow upload does not hold up the queue's other receives.
        Optional<String> newText;
        try {
            newText = readMessageText(request);
        } catch (IOException e) {
            return Response.serverError().build();
        }

        String keyPrefix = objKey(request.accountName(), queueName, "");
        synchronized (messageLock(request.accountName(), queueName)) {
            Optional<StoredObject> existing = store.scan(k -> k.startsWith(keyPrefix)).stream()
                    .filter(so -> messageId.equals(so.etag()))
                    .findFirst();
            if (existing.isEmpty() || isExpired(existing.get(), Instant.now())) {
                return new AzureErrorResponse("MessageNotFound", "The specified message does not exist.")
                        .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
            }
            if (!popReceipt.equals(existing.get().metadata().get("PopReceipt"))) {
                return new AzureErrorResponse("PopReceiptMismatch", "The specified pop receipt did not match.")
                        .toXmlResponse(Response.Status.BAD_REQUEST.getStatusCode());
            }

            String messageText = newText.orElse(existing.get().metadata().get("MessageText"));
            Instant visibleAt = Instant.now().plusSeconds(visibilityTimeoutSecs);
            String newPopReceipt = UUID.randomUUID().toString();
            Map<String, String> meta = new HashMap<>(existing.get().metadata());
            meta.put("MessageText", messageText);
            meta.put("PopReceipt", newPopReceipt);
            meta.put("_visibleAt", String.valueOf(visibleAt.toEpochMilli()));

            store.put(objKey(request.accountName(), queueName, existing.get().key()),
                    new StoredObject(existing.get().key(), messageText.getBytes(), meta,
                            Instant.now(), existing.get().etag()));

            return Response.status(Response.Status.NO_CONTENT)
                    .header("x-ms-popreceipt", newPopReceipt)
                    .header("x-ms-time-next-visible", RFC1123_DATE_TIME.format(visibleAt))
                    .build();
        }
    }

    /**
     * The message text of an Update Message body, or empty when there is no body. The body is optional
     * (Azure, x-ms-version 2011-08-18 and later): an update without one keeps the message text and only
     * moves the visibility timeout, which is how SDK clients extend a lease
     * (updateMessage(id, popReceipt, undefined, timeout)).
     */
    private Optional<String> readMessageText(AzureRequest request) throws IOException {
        // Peek for a non-whitespace byte instead of buffering the body: the parser still streams a present body.
        PushbackInputStream body = new PushbackInputStream(
                request.bodyStream() == null ? InputStream.nullInputStream() : request.bodyStream(), 1);
        int first = body.read();
        while (first != -1 && Character.isWhitespace(first)) {
            first = body.read();
        }
        if (first == -1) {
            return Optional.empty();
        }
        body.unread(first);
        return Optional.of(xmlMapper.readValue(body, QueueModels.QueueMessageRequest.class).MessageText());
    }

    private Response clearMessages(AzureRequest request, String queueName) {
        removeMessages(request.accountName(), queueName);
        return Response.status(Response.Status.NO_CONTENT).build();
    }

    /**
     * Counts hidden messages too, as Azure does: its count is "not lower than the actual number of
     * messages in the queue", whereas Peek Messages returns visible messages only. A client that
     * must tell an empty queue from one whose message is hidden relies on the difference.
     */
    private long approximateMessageCount(AzureRequest request, String queueName) {
        String keyPrefix = objKey(request.accountName(), queueName, "");
        Instant now = Instant.now();
        return store.scan(k -> k.startsWith(keyPrefix)).stream()
                .filter(so -> !isExpired(so, now))
                .count();
    }

    private static Map<String, String> readMetadataHeaders(AzureRequest request) {
        Map<String, String> metadata = new HashMap<>();
        request.headers().getRequestHeaders().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith("x-ms-meta-") && !values.isEmpty()) {
                metadata.put(name.substring("x-ms-meta-".length()), values.get(0));
            }
        });
        return metadata;
    }

    private static boolean includes(String include, String value) {
        if (include == null || include.isBlank()) {
            return false;
        }
        return Arrays.stream(include.split(","))
                .map(String::trim)
                .anyMatch(item -> item.equalsIgnoreCase(value));
    }

    private static long parseLongQuery(AzureRequest request, String name, long defaultValue) {
        String value = request.queryParams().get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(value);
    }

    private static Long parseMessageTtl(AzureRequest request) {
        String value = request.queryParams().get("messagettl");
        if (value == null || value.isBlank()) {
            return DEFAULT_MESSAGE_TTL_SECONDS;
        }
        long ttl = Long.parseLong(value);
        if (ttl == -1) {
            return null;
        }
        if (ttl < 1) {
            throw new NumberFormatException("messagettl must be positive or -1");
        }
        return ttl;
    }

    private static boolean isExpired(StoredObject so, Instant now) {
        String expirationEpoch = so.metadata().get("_expirationEpoch");
        if (expirationEpoch == null) {
            return false;
        }
        try {
            return !Instant.ofEpochSecond(Long.parseLong(expirationEpoch)).isAfter(now);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String expirationTime(Map<String, String> metadata) {
        String expirationEpoch = metadata.get("_expirationEpoch");
        if (expirationEpoch == null) {
            return NEVER_EXPIRES;
        }
        try {
            return RFC1123_DATE_TIME.format(Instant.ofEpochSecond(Long.parseLong(expirationEpoch)));
        } catch (NumberFormatException e) {
            return NEVER_EXPIRES;
        }
    }

    public void clear() {
        store.clear();
    }

    /** Creates the queue if it does not exist; an existing queue keeps its metadata and access policies. */
    public void ensureQueue(String accountName, String queueName) {
        String key = nsKey(accountName, queueName);
        synchronized (queueRecordLock) {
            if (store.get(key).isEmpty()) {
                store.put(key, NS_SENTINEL);
            }
        }
    }

    private static String nsKey(String accountName, String queueName) {
        return NS_PREFIX + accountName + "/" + queueName;
    }

    private static String objKey(String accountName, String queueName, String key) {
        return accountName + "/" + queueName + "/" + key;
    }

    private Object messageLock(String accountName, String queueName) {
        return messageLocks[Math.floorMod(objKey(accountName, queueName, "").hashCode(), messageLocks.length)];
    }
}
