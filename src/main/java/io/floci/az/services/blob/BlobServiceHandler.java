package io.floci.az.services.blob;

import io.floci.az.core.AzureErrorResponse;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.StoredObject;
import io.floci.az.core.XmlUtils;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class BlobServiceHandler implements AzureServiceHandler {

    private static final Logger LOGGER = Logger.getLogger(BlobServiceHandler.class);
    private static final DateTimeFormatter RFC1123_DATE_TIME = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneId.of("GMT"));

    private static final String NS_PREFIX = "__ns__:";
    private static final StoredObject NS_SENTINEL =
            new StoredObject("", new byte[0], Map.of(), Instant.EPOCH, "");

    private final StorageBackend<String, StoredObject> store;

    @Inject
    public BlobServiceHandler(StorageFactory storageFactory) {
        this.store = storageFactory.create("blob");
    }

    @Override
    public String getServiceType() {
        return "blob";
    }

    @Override
    public boolean canHandle(AzureRequest request) {
        return "blob".equals(request.serviceType());
    }

    @Override
    public Response handle(AzureRequest request) {
        String path = request.resourcePath();
        String method = request.method();
        Map<String, String> query = request.queryParams();

        LOGGER.infof("BlobService handling: %s %s", method, path);

        Response response;
        if (path.isEmpty() || path.equals("/")) {
            if ("GET".equalsIgnoreCase(method) && "list".equals(query.get("comp"))) {
                response = listContainers(request);
            } else {
                response = new AzureErrorResponse("NotImplemented", "The requested operation is not implemented.")
                        .toXmlResponse(501);
            }
        } else {
            String[] parts = path.split("/", 2);
            String containerName = parts[0];
            String blobName = parts.length > 1 ? parts[1] : "";

            if (blobName.isEmpty()) {
                if ("GET".equalsIgnoreCase(method) && "list".equals(query.get("comp"))) {
                    response = listBlobs(request, containerName);
                } else if ("PUT".equalsIgnoreCase(method) && "container".equals(query.get("restype"))) {
                    response = createContainer(request, containerName);
                } else if ("DELETE".equalsIgnoreCase(method) && "container".equals(query.get("restype"))) {
                    response = deleteContainer(request, containerName);
                } else if (("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) && "container".equals(query.get("restype"))) {
                    response = getContainer(request, containerName, "HEAD".equalsIgnoreCase(method));
                } else {
                    response = new AzureErrorResponse("NotImplemented", "The requested operation is not implemented.")
                            .toXmlResponse(501);
                }
            } else {
                if ("PUT".equalsIgnoreCase(method)) {
                    response = putBlob(request, containerName, blobName);
                } else if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
                    response = getBlob(request, containerName, blobName, "HEAD".equalsIgnoreCase(method));
                } else if ("DELETE".equalsIgnoreCase(method)) {
                    response = deleteBlob(request, containerName, blobName);
                } else {
                    response = new AzureErrorResponse("NotImplemented", "The requested operation is not implemented.")
                            .toXmlResponse(501);
                }
            }
        }

        return Response.fromResponse(response)
                .header("x-ms-request-id", UUID.randomUUID().toString())
                .header("x-ms-version", request.headers().getHeaderString("x-ms-version"))
                .header("Date", RFC1123_DATE_TIME.format(Instant.now()))
                .build();
    }

    private Response getContainer(AzureRequest request, String containerName, boolean headOnly) {
        if (store.get(nsKey(request.accountName(), containerName)).isEmpty()) {
            return new AzureErrorResponse("ContainerNotFound", "The specified container does not exist.")
                    .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
        }
        return Response.ok()
                .header("Last-Modified", RFC1123_DATE_TIME.format(Instant.now()))
                .header("ETag", UUID.randomUUID().toString())
                .header("x-ms-has-immutability-policy", "false")
                .header("x-ms-has-legal-hold", "false")
                .build();
    }

    private Response createContainer(AzureRequest request, String containerName) {
        String key = nsKey(request.accountName(), containerName);
        if (store.get(key).isPresent()) {
            return new AzureErrorResponse("ContainerAlreadyExists", "The specified container already exists.")
                    .toXmlResponse(Response.Status.CONFLICT.getStatusCode());
        }
        store.put(key, NS_SENTINEL);
        return Response.status(Response.Status.CREATED)
                .header("Last-Modified", RFC1123_DATE_TIME.format(Instant.now()))
                .header("ETag", UUID.randomUUID().toString())
                .build();
    }

    private Response deleteContainer(AzureRequest request, String containerName) {
        store.delete(nsKey(request.accountName(), containerName));
        String objPrefix = request.accountName() + "/" + containerName + "/";
        store.keys().stream()
                .filter(k -> k.startsWith(objPrefix))
                .toList()
                .forEach(store::delete);
        return Response.status(Response.Status.ACCEPTED).build();
    }

    private Response listContainers(AzureRequest request) {
        String prefix = request.queryParams().getOrDefault("prefix", "");
        String nsFilter = NS_PREFIX + request.accountName() + "/" + prefix;

        List<BlobModels.ContainerItem> containers = store.keys().stream()
                .filter(k -> k.startsWith(nsFilter))
                .map(k -> k.substring(NS_PREFIX.length() + request.accountName().length() + 1))
                .map(name -> new BlobModels.ContainerItem(name, new BlobModels.ContainerProperties(
                        RFC1123_DATE_TIME.format(Instant.now()),
                        UUID.randomUUID().toString()
                )))
                .collect(Collectors.toList());

        BlobModels.ContainerListResponse response = new BlobModels.ContainerListResponse(
                "http://localhost:4577/" + request.accountName(),
                prefix, "", 1000, containers, ""
        );

        return Response.ok(XmlUtils.toXml(response)).type(MediaType.APPLICATION_XML).build();
    }

    private Response putBlob(AzureRequest request, String containerName, String blobName) {
        try {
            byte[] data = request.bodyStream().readAllBytes();
            Map<String, String> metadata = new HashMap<>();
            String blobType = request.headers().getHeaderString("x-ms-blob-type");
            metadata.put("BlobType", blobType != null ? blobType : "BlockBlob");
            String ct = request.headers().getHeaderString(HttpHeaders.CONTENT_TYPE);
            metadata.put("Content-Type", ct != null ? ct : "application/octet-stream");
            metadata.put("Name", blobName);

            store.put(objKey(request.accountName(), containerName, blobName),
                    new StoredObject(blobName, data, metadata, Instant.now(), UUID.randomUUID().toString()));

            return Response.status(Response.Status.CREATED)
                    .header("Last-Modified", RFC1123_DATE_TIME.format(Instant.now()))
                    .header("ETag", UUID.randomUUID().toString())
                    .header("x-ms-request-server-encrypted", "true")
                    .header("Content-Length", 0)
                    .build();
        } catch (IOException e) {
            return Response.serverError().build();
        }
    }

    private Response getBlob(AzureRequest request, String containerName, String blobName, boolean headOnly) {
        Optional<StoredObject> object = store.get(objKey(request.accountName(), containerName, blobName));

        if (object.isEmpty()) {
            return new AzureErrorResponse("BlobNotFound", "The specified blob does not exist.")
                    .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
        }

        StoredObject so = object.get();
        long totalSize = so.data().length;
        String rangeHeader = request.headers().getHeaderString("x-ms-range");
        if (rangeHeader == null) rangeHeader = request.headers().getHeaderString("Range");

        long rangeStart = 0;
        long rangeEnd   = totalSize - 1;
        boolean isRangeRequest = false;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String[] parts = rangeHeader.substring(6).split("-", 2);
            try {
                rangeStart = Long.parseLong(parts[0]);
                rangeEnd   = parts.length > 1 && !parts[1].isEmpty()
                        ? Long.parseLong(parts[1]) : totalSize - 1;
                if (rangeStart < 0 || rangeStart >= totalSize) {
                    return new AzureErrorResponse("InvalidRange",
                            "The range specified is invalid for the current size of the resource.")
                            .toXmlResponse(416);
                }
                rangeEnd   = Math.min(rangeEnd, totalSize - 1);
                isRangeRequest = true;
            } catch (NumberFormatException e) {
                return new AzureErrorResponse("InvalidRange",
                        "The range specified is invalid.").toXmlResponse(416);
            }
        }

        long contentLength = rangeEnd - rangeStart + 1;
        Response.ResponseBuilder rb = (isRangeRequest ? Response.status(206) : Response.ok())
                .header("Last-Modified", RFC1123_DATE_TIME.format(so.lastModified()))
                .header("ETag", so.etag())
                .header("x-ms-blob-type", so.metadata().getOrDefault("BlobType", "BlockBlob"))
                .header(HttpHeaders.CONTENT_TYPE, so.metadata().getOrDefault("Content-Type", "application/octet-stream"))
                .header(HttpHeaders.CONTENT_LENGTH, contentLength)
                .header("Content-Range", String.format("bytes %d-%d/%d", rangeStart, rangeEnd, totalSize))
                .header("x-ms-blob-content-length", totalSize)
                .header("Accept-Ranges", "bytes");

        if (!headOnly) {
            if (isRangeRequest) {
                // cast is safe: rangeStart/rangeEnd validated < totalSize which is bounded by int (byte[] length)
                rb.entity(Arrays.copyOfRange(so.data(), Math.toIntExact(rangeStart), Math.toIntExact(rangeEnd) + 1));
            } else {
                rb.entity(so.data());
            }
        }

        return rb.build();
    }

    private Response deleteBlob(AzureRequest request, String containerName, String blobName) {
        store.delete(objKey(request.accountName(), containerName, blobName));
        return Response.status(Response.Status.ACCEPTED).build();
    }

    private Response listBlobs(AzureRequest request, String containerName) {
        String prefix = request.queryParams().getOrDefault("prefix", "");
        String keyPrefix = objKey(request.accountName(), containerName, prefix);

        List<BlobModels.BlobItem> blobs = store.scan(k -> k.startsWith(keyPrefix)).stream()
                .map(so -> {
                    String name = so.metadata().getOrDefault("Name", so.key());
                    return new BlobModels.BlobItem(name, new BlobModels.BlobProperties(
                            RFC1123_DATE_TIME.format(so.lastModified()),
                            so.etag(),
                            (long) so.data().length,
                            so.metadata().getOrDefault("Content-Type", "application/octet-stream"),
                            so.metadata().getOrDefault("BlobType", "BlockBlob")
                    ));
                })
                .collect(Collectors.toList());

        BlobModels.BlobListResponse response = new BlobModels.BlobListResponse(
                "http://localhost:4577/" + request.accountName(),
                containerName, prefix, "", 1000, blobs, ""
        );

        return Response.ok(XmlUtils.toXml(response)).type(MediaType.APPLICATION_XML).build();
    }

    public void clearAll() {
        store.clear();
    }

    private static String nsKey(String accountName, String containerName) {
        return NS_PREFIX + accountName + "/" + containerName;
    }

    private static String objKey(String accountName, String containerName, String blobName) {
        return accountName + "/" + containerName + "/" + blobName;
    }
}
