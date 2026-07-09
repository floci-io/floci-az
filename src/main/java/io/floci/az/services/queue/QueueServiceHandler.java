package io.floci.az.services.queue;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.floci.az.core.AzureErrorResponse;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.StoredObject;
import io.floci.az.core.XmlBuilder;
import io.floci.az.core.XmlUtils;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class QueueServiceHandler implements AzureServiceHandler, Resettable {

    private static final Logger LOGGER = Logger.getLogger(QueueServiceHandler.class);
    private static final DateTimeFormatter RFC1123_DATE_TIME = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneId.of("GMT"));

    private static final String NS_PREFIX = "__ns__:";
    private static final StoredObject NS_SENTINEL =
            new StoredObject("", new byte[0], Map.of(), Instant.EPOCH, "");
    private static final long DEFAULT_MESSAGE_TTL_SECONDS = 604800;
    private static final long DEFAULT_VISIBILITY_TIMEOUT_SECONDS = 30;
    private static final String NEVER_EXPIRES = "Fri, 31 Dec 9999 23:59:59 GMT";

    private final StorageBackend<String, StoredObject> store;
    private final XmlMapper xmlMapper = new XmlMapper();

    @Inject
    public QueueServiceHandler(StorageFactory storageFactory) {
        this.store = storageFactory.create("queue");
    }

    @Override
    public String getServiceType() {
        return "queue";
    }

    @Override
    public boolean canHandle(AzureRequest request) {
        return "queue".equals(request.serviceType());
    }

    @Override
    public Response handle(AzureRequest request) {
        String path = request.resourcePath();
        String method = request.method();
        Map<String, String> query = request.queryParams();

        LOGGER.infof("QueueService handling: %s %s", method, path);

        Response response;
        if (path.isEmpty() || path.equals("/")) {
            if ("GET".equalsIgnoreCase(method) && "list".equals(query.get("comp"))) {
                response = listQueues(request);
            } else if ("service".equals(query.get("restype")) && "properties".equals(query.get("comp"))) {
                if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
                    response = getQueueServiceProperties();
                } else {
                    response = Response.ok().build();
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
                if ("PUT".equalsIgnoreCase(method) && "metadata".equals(query.get("comp"))) {
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

        return Response.fromResponse(response)
                .header("x-ms-request-id", UUID.randomUUID().toString())
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
            .toString();
        return Response.ok(xml, "application/xml").build();
    }

    private Response createQueue(AzureRequest request, String queueName) {
        String key = nsKey(request.accountName(), queueName);
        if (store.get(key).isPresent()) {
            return Response.status(Response.Status.NO_CONTENT).build();
        }
        store.put(key,
                new StoredObject("", new byte[0], readMetadataHeaders(request), Instant.now(), ""));
        return Response.status(Response.Status.CREATED).build();
    }

    private Response deleteQueue(AzureRequest request, String queueName) {
        store.delete(nsKey(request.accountName(), queueName));
        String msgPrefix = request.accountName() + "/" + queueName + "/";
        store.keys().stream()
                .filter(k -> k.startsWith(msgPrefix))
                .toList()
                .forEach(store::delete);
        return Response.status(Response.Status.NO_CONTENT).build();
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
                .header("x-ms-approximate-messages-count", approximateVisibleMessageCount(request, queueName));
        queue.get().metadata().forEach((name, value) -> rb.header("x-ms-meta-" + name, value));
        return rb.build();
    }

    private Response setQueueMetadata(AzureRequest request, String queueName) {
        String key = nsKey(request.accountName(), queueName);
        Optional<StoredObject> queue = store.get(key);
        if (queue.isEmpty()) {
            return new AzureErrorResponse("QueueNotFound", "The specified queue does not exist.")
                    .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
        }

        store.put(key, new StoredObject("", new byte[0], readMetadataHeaders(request), Instant.now(), ""));
        return Response.status(Response.Status.NO_CONTENT).build();
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
            if (visibilityTimeoutSecs < 0) {
                return new AzureErrorResponse("OutOfRangeQueryParameterValue",
                        "The visibilitytimeout parameter must be greater than or equal to 0.")
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
            metadata.put("_visibleAt", String.valueOf(visibleAt.getEpochSecond()));

            String key = System.currentTimeMillis() + "-" + messageId;
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
            if (visibilityTimeoutSecs < 1) {
                return new AzureErrorResponse("OutOfRangeQueryParameterValue",
                        "The visibilitytimeout parameter must be greater than 0.")
                        .toXmlResponse(Response.Status.BAD_REQUEST.getStatusCode());
            }
        } catch (NumberFormatException e) {
            visibilityTimeoutSecs = 30;
        }

        String keyPrefix = objKey(request.accountName(), queueName, "");
        Instant now = Instant.now();

        List<StoredObject> visible = store.scan(k -> k.startsWith(keyPrefix)).stream()
                .filter(so -> isVisible(so, now))
                .limit(numOfMessages)
                .collect(Collectors.toList());

        if (!peekOnly) {
            Instant hiddenUntil = now.plusSeconds(visibilityTimeoutSecs);
            for (StoredObject so : visible) {
                Map<String, String> meta = new HashMap<>(so.metadata());
                meta.put("_visibleAt", String.valueOf(hiddenUntil.getEpochSecond()));
                int dequeueCount = Integer.parseInt(meta.getOrDefault("DequeueCount", "0")) + 1;
                meta.put("DequeueCount", String.valueOf(dequeueCount));
                meta.put("PopReceipt", UUID.randomUUID().toString());
                store.put(objKey(request.accountName(), queueName, so.key()),
                        new StoredObject(so.key(), so.data(), meta, so.lastModified(), so.etag()));
            }
        }

        final Instant capturedNow = now;
        final long capturedVt = visibilityTimeoutSecs;
        List<QueueModels.QueueMessageItem> messages = visible.stream()
                .map(so -> {
                    StoredObject current = peekOnly
                            ? so
                            : store.get(objKey(request.accountName(), queueName, so.key())).orElse(so);
                    Map<String, String> meta = current.metadata();
                    int dequeueCount = peekOnly
                            ? Integer.parseInt(meta.getOrDefault("DequeueCount", "0"))
                            : Integer.parseInt(meta.getOrDefault("DequeueCount", "0"));
                    return new QueueModels.QueueMessageItem(
                            so.etag(),
                            meta.get("InsertionTime"),
                            expirationTime(meta),
                            peekOnly ? null : meta.get("PopReceipt"),
                            peekOnly ? null : RFC1123_DATE_TIME.format(capturedNow.plusSeconds(capturedVt)),
                            dequeueCount,
                            new String(so.data())
                    );
                })
                .collect(Collectors.toList());

        return Response.ok(XmlUtils.toXml(new QueueModels.QueueMessageResponse(messages))).type(MediaType.APPLICATION_XML).build();
    }

    private boolean isVisible(StoredObject so, Instant now) {
        if (isExpired(so, now)) {
            return false;
        }
        String visibleAt = so.metadata().get("_visibleAt");
        if (visibleAt == null) return true;
        try {
            return Instant.ofEpochSecond(Long.parseLong(visibleAt)).isBefore(now);
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
            if (visibilityTimeoutSecs < 0) {
                return new AzureErrorResponse("OutOfRangeQueryParameterValue",
                        "The visibilitytimeout parameter must be greater than or equal to 0.")
                        .toXmlResponse(Response.Status.BAD_REQUEST.getStatusCode());
            }
        } catch (NumberFormatException e) {
            return new AzureErrorResponse("InvalidQueryParameterValue",
                    "visibilitytimeout must be a valid integer.")
                    .toXmlResponse(Response.Status.BAD_REQUEST.getStatusCode());
        }

        String keyPrefix = objKey(request.accountName(), queueName, "");
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

        try {
            QueueModels.QueueMessageRequest msgReq = xmlMapper.readValue(request.bodyStream(), QueueModels.QueueMessageRequest.class);
            Instant visibleAt = Instant.now().plusSeconds(visibilityTimeoutSecs);
            String newPopReceipt = UUID.randomUUID().toString();
            Map<String, String> meta = new HashMap<>(existing.get().metadata());
            meta.put("MessageText", msgReq.MessageText());
            meta.put("PopReceipt", newPopReceipt);
            meta.put("_visibleAt", String.valueOf(visibleAt.getEpochSecond()));

            store.put(objKey(request.accountName(), queueName, existing.get().key()),
                    new StoredObject(existing.get().key(), msgReq.MessageText().getBytes(), meta,
                            Instant.now(), existing.get().etag()));

            return Response.status(Response.Status.NO_CONTENT)
                    .header("x-ms-popreceipt", newPopReceipt)
                    .header("x-ms-time-next-visible", RFC1123_DATE_TIME.format(visibleAt))
                    .build();
        } catch (IOException e) {
            return Response.serverError().build();
        }
    }

    private Response clearMessages(AzureRequest request, String queueName) {
        String keyPrefix = objKey(request.accountName(), queueName, "");
        store.keys().stream()
                .filter(k -> k.startsWith(keyPrefix))
                .toList()
                .forEach(store::delete);
        return Response.status(Response.Status.NO_CONTENT).build();
    }

    private long approximateVisibleMessageCount(AzureRequest request, String queueName) {
        String keyPrefix = objKey(request.accountName(), queueName, "");
        Instant now = Instant.now();
        return store.scan(k -> k.startsWith(keyPrefix)).stream()
                .filter(so -> isVisible(so, now))
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

    public void clearAll() {
        store.clear();
    }

    public void ensureQueue(String accountName, String queueName) {
        store.put(nsKey(accountName, queueName), NS_SENTINEL);
    }

    private static String nsKey(String accountName, String queueName) {
        return NS_PREFIX + accountName + "/" + queueName;
    }

    private static String objKey(String accountName, String queueName, String key) {
        return accountName + "/" + queueName + "/" + key;
    }
}
