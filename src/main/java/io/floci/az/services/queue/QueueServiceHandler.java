package io.floci.az.services.queue;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.floci.az.core.AzureErrorResponse;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.StoredObject;
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
public class QueueServiceHandler implements AzureServiceHandler {

    private static final Logger LOGGER = Logger.getLogger(QueueServiceHandler.class);
    private static final DateTimeFormatter RFC1123_DATE_TIME = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneId.of("GMT"));

    private static final String NS_PREFIX = "__ns__:";
    private static final StoredObject NS_SENTINEL =
            new StoredObject("", new byte[0], Map.of(), Instant.EPOCH, "");

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
            } else {
                response = new AzureErrorResponse("NotImplemented", "The requested operation is not implemented.")
                        .toXmlResponse(501);
            }
        } else {
            String[] parts = path.split("/");
            String queueName = parts[0];
            String subPath = parts.length > 1 ? parts[1] : "";

            if (subPath.isEmpty()) {
                if ("PUT".equalsIgnoreCase(method)) {
                    response = createQueue(request, queueName);
                } else if ("DELETE".equalsIgnoreCase(method)) {
                    response = deleteQueue(request, queueName);
                } else {
                    response = new AzureErrorResponse("NotImplemented", "The requested operation is not implemented.")
                            .toXmlResponse(501);
                }
            } else if ("messages".equals(subPath)) {
                if ("POST".equalsIgnoreCase(method)) {
                    response = putMessage(request, queueName);
                } else if ("GET".equalsIgnoreCase(method)) {
                    response = getMessages(request, queueName, "true".equals(query.get("peekonly")));
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

    private Response createQueue(AzureRequest request, String queueName) {
        store.put(nsKey(request.accountName(), queueName), NS_SENTINEL);
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

        List<QueueModels.QueueItem> queues = store.keys().stream()
                .filter(k -> k.startsWith(nsFilter))
                .map(k -> k.substring(NS_PREFIX.length() + request.accountName().length() + 1))
                .map(name -> new QueueModels.QueueItem(name, Collections.emptyMap()))
                .collect(Collectors.toList());

        QueueModels.QueueListResponse response = new QueueModels.QueueListResponse(
                "http://localhost:4577/" + request.accountName(),
                prefix, "", 1000, queues, ""
        );

        return Response.ok(XmlUtils.toXml(response)).type(MediaType.APPLICATION_XML).build();
    }

    private Response putMessage(AzureRequest request, String queueName) {
        try {
            QueueModels.QueueMessageRequest msgReq = xmlMapper.readValue(request.bodyStream(), QueueModels.QueueMessageRequest.class);
            String messageId = UUID.randomUUID().toString();
            String insertionTime = RFC1123_DATE_TIME.format(Instant.now());
            String expirationTime = RFC1123_DATE_TIME.format(Instant.now().plusSeconds(604800));
            String popReceipt = UUID.randomUUID().toString();

            Map<String, String> metadata = new HashMap<>();
            metadata.put("InsertionTime", insertionTime);
            metadata.put("MessageText", msgReq.MessageText());

            String key = System.currentTimeMillis() + "-" + messageId;
            store.put(objKey(request.accountName(), queueName, key),
                    new StoredObject(key, msgReq.MessageText().getBytes(), metadata, Instant.now(), messageId));

            QueueModels.QueueMessageItem item = new QueueModels.QueueMessageItem(
                    messageId, insertionTime, expirationTime, popReceipt, insertionTime, 0, msgReq.MessageText()
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
            visibilityTimeoutSecs = Long.parseLong(request.queryParams().getOrDefault("visibilitytimeout", "30"));
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
                store.put(objKey(request.accountName(), queueName, so.key()),
                        new StoredObject(so.key(), so.data(), meta, so.lastModified(), so.etag()));
            }
        }

        final Instant capturedNow = now;
        final long capturedVt = visibilityTimeoutSecs;
        List<QueueModels.QueueMessageItem> messages = visible.stream()
                .map(so -> {
                    Map<String, String> meta = so.metadata();
                    int dequeueCount = peekOnly
                            ? Integer.parseInt(meta.getOrDefault("DequeueCount", "0"))
                            : Integer.parseInt(meta.getOrDefault("DequeueCount", "0")) + 1;
                    return new QueueModels.QueueMessageItem(
                            so.etag(),
                            meta.get("InsertionTime"),
                            RFC1123_DATE_TIME.format(capturedNow.plusSeconds(604800)),
                            UUID.randomUUID().toString(),
                            RFC1123_DATE_TIME.format(capturedNow.plusSeconds(capturedVt)),
                            dequeueCount,
                            new String(so.data())
                    );
                })
                .collect(Collectors.toList());

        return Response.ok(XmlUtils.toXml(new QueueModels.QueueMessageResponse(messages))).type(MediaType.APPLICATION_XML).build();
    }

    private boolean isVisible(StoredObject so, Instant now) {
        String visibleAt = so.metadata().get("_visibleAt");
        if (visibleAt == null) return true;
        return Instant.ofEpochSecond(Long.parseLong(visibleAt)).isBefore(now);
    }

    private Response deleteMessage(AzureRequest request, String queueName, String messageId, String popReceipt) {
        String keyPrefix = objKey(request.accountName(), queueName, "");
        store.scan(k -> k.startsWith(keyPrefix)).stream()
                .filter(so -> messageId.equals(so.etag()))
                .findFirst()
                .ifPresent(so -> store.delete(objKey(request.accountName(), queueName, so.key())));
        return Response.status(Response.Status.NO_CONTENT).build();
    }

    private Response clearMessages(AzureRequest request, String queueName) {
        String keyPrefix = objKey(request.accountName(), queueName, "");
        store.keys().stream()
                .filter(k -> k.startsWith(keyPrefix))
                .toList()
                .forEach(store::delete);
        return Response.status(Response.Status.NO_CONTENT).build();
    }

    public void clearAll() {
        store.clear();
    }

    private static String nsKey(String accountName, String queueName) {
        return NS_PREFIX + accountName + "/" + queueName;
    }

    private static String objKey(String accountName, String queueName, String key) {
        return accountName + "/" + queueName + "/" + key;
    }
}
