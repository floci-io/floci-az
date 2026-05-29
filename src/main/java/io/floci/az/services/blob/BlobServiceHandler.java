package io.floci.az.services.blob;

import io.floci.az.core.AzureErrorResponse;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.StoredObject;
import io.floci.az.core.XmlBuilder;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class BlobServiceHandler implements AzureServiceHandler {

    private static final Logger LOGGER = Logger.getLogger(BlobServiceHandler.class);
    private static final DateTimeFormatter RFC1123_DATE_TIME = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneId.of("GMT"));

    private static final String NS_PREFIX  = "__ns__:";
    private static final String BLK_PREFIX = "__blk__:";
    private static final String USER_METADATA_PREFIX = "UserMeta:";
    private static final StoredObject NS_SENTINEL =
            new StoredObject("", new byte[0], Map.of(), Instant.EPOCH, "");

    /**
     * Matches {@code <Latest>}, {@code <Committed>}, or {@code <Uncommitted>} elements
     * inside a PutBlockList XML body — e.g. {@code <Latest>BASE64ID</Latest>}.
     */
    private static final Pattern BLOCK_LIST_PATTERN =
            Pattern.compile("<(?:Latest|Committed|Uncommitted)>([^<]+)</(?:Latest|Committed|Uncommitted)>");

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
            } else if ("service".equals(query.get("restype")) && "properties".equals(query.get("comp"))) {
                if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
                    response = getBlobServiceProperties();
                } else {
                    response = Response.ok().build();
                }
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
                String comp = query.get("comp");
                if ("PUT".equalsIgnoreCase(method) && "metadata".equals(comp)) {
                    response = setBlobMetadata(request, containerName, blobName);
                } else if (("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method))
                        && "metadata".equals(comp)) {
                    response = getBlobMetadata(request, containerName, blobName);
                } else if ("PUT".equalsIgnoreCase(method) && "block".equals(comp)) {
                    response = putBlock(request, containerName, blobName);
                } else if ("PUT".equalsIgnoreCase(method) && "blocklist".equals(comp)) {
                    response = putBlockList(request, containerName, blobName);
                } else if (("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method))
                        && "blocklist".equals(comp)) {
                    response = getBlockList(request, containerName, blobName);
                } else if ("PUT".equalsIgnoreCase(method)) {
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

    private Response getBlobServiceProperties() {
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
                .start("StaticWebsite").elem("Enabled", "false").end("StaticWebsite")
            .end("StorageServiceProperties")
            .toString();
        return Response.ok(xml, "application/xml").build();
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
        String blkPrefix = BLK_PREFIX + objPrefix;
        store.keys().stream()
                .filter(k -> k.startsWith(objPrefix) || k.startsWith(blkPrefix))
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
            if (store.get(nsKey(request.accountName(), containerName)).isEmpty()) {
                return new AzureErrorResponse("ContainerNotFound", "The specified container does not exist.")
                        .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
            }

            Optional<StoredObject> existing = store.get(objKey(request.accountName(), containerName, blobName));
            Response conditionFailure = validateBlobConditions(request, existing);
            if (conditionFailure != null) {
                return conditionFailure;
            }

            byte[] data = request.bodyStream().readAllBytes();
            Map<String, String> metadata = new HashMap<>();
            String blobType = request.headers().getHeaderString("x-ms-blob-type");
            metadata.put("BlobType", blobType != null ? blobType : "BlockBlob");
            String ct = request.headers().getHeaderString(HttpHeaders.CONTENT_TYPE);
            metadata.put("Content-Type", ct != null ? ct : "application/octet-stream");
            metadata.put("Name", blobName);
            metadata.putAll(readUserMetadata(request));

            String etag = UUID.randomUUID().toString();
            store.put(objKey(request.accountName(), containerName, blobName),
                    new StoredObject(blobName, data, metadata, Instant.now(), etag));

            return Response.status(Response.Status.CREATED)
                    .header("Last-Modified", RFC1123_DATE_TIME.format(Instant.now()))
                    .header("ETag", etag)
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

        Response conditionFailure = validateBlobConditions(request, object);
        if (conditionFailure != null) {
            return conditionFailure;
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
        addUserMetadataHeaders(rb, so.metadata());

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
        Optional<StoredObject> object = store.get(objKey(request.accountName(), containerName, blobName));
        if (object.isEmpty()) {
            return new AzureErrorResponse("BlobNotFound", "The specified blob does not exist.")
                    .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
        }
        Response conditionFailure = validateBlobConditions(request, object);
        if (conditionFailure != null) {
            return conditionFailure;
        }
        store.delete(objKey(request.accountName(), containerName, blobName));
        return Response.status(Response.Status.ACCEPTED).build();
    }

    private Response getBlobMetadata(AzureRequest request, String containerName, String blobName) {
        Optional<StoredObject> object = store.get(objKey(request.accountName(), containerName, blobName));
        if (object.isEmpty()) {
            return new AzureErrorResponse("BlobNotFound", "The specified blob does not exist.")
                    .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
        }

        Response conditionFailure = validateBlobConditions(request, object);
        if (conditionFailure != null) {
            return conditionFailure;
        }

        StoredObject so = object.get();
        Response.ResponseBuilder rb = Response.ok()
                .header("Last-Modified", RFC1123_DATE_TIME.format(so.lastModified()))
                .header("ETag", so.etag());
        addUserMetadataHeaders(rb, so.metadata());
        return rb.build();
    }

    private Response setBlobMetadata(AzureRequest request, String containerName, String blobName) {
        Optional<StoredObject> object = store.get(objKey(request.accountName(), containerName, blobName));
        if (object.isEmpty()) {
            return new AzureErrorResponse("BlobNotFound", "The specified blob does not exist.")
                    .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
        }

        Response conditionFailure = validateBlobConditions(request, object);
        if (conditionFailure != null) {
            return conditionFailure;
        }

        StoredObject so = object.get();
        Map<String, String> metadata = new HashMap<>();
        so.metadata().forEach((key, value) -> {
            if (!key.startsWith(USER_METADATA_PREFIX)) {
                metadata.put(key, value);
            }
        });
        metadata.putAll(readUserMetadata(request));

        String etag = UUID.randomUUID().toString();
        store.put(objKey(request.accountName(), containerName, blobName),
                new StoredObject(so.key(), so.data(), metadata, Instant.now(), etag));

        return Response.ok()
                .header("Last-Modified", RFC1123_DATE_TIME.format(Instant.now()))
                .header("ETag", etag)
                .build();
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
                    ), includes(request.queryParams().get("include"), "metadata") ? userMetadata(so.metadata()) : null);
                })
                .collect(Collectors.toList());

        BlobModels.BlobListResponse response = new BlobModels.BlobListResponse(
                "http://localhost:4577/" + request.accountName(),
                containerName, prefix, "", 1000, blobs, ""
        );

        return Response.ok(XmlUtils.toXml(response)).type(MediaType.APPLICATION_XML).build();
    }

    // ── Block Blob ────────────────────────────────────────────────────────────

    /**
     * PUT /{container}/{blob}?comp=block&blockid={BASE64}
     * <p>Stages one block. Data is stored under a {@code __blk__:} key and only
     * becomes part of the blob after a successful {@link #putBlockList}.
     */
    private Response putBlock(AzureRequest request, String containerName, String blobName) {
        try {
            if (store.get(nsKey(request.accountName(), containerName)).isEmpty()) {
                return new AzureErrorResponse("ContainerNotFound", "The specified container does not exist.")
                        .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
            }
            String blockId = request.queryParams().get("blockid");
            if (blockId == null || blockId.isBlank()) {
                return new AzureErrorResponse("InvalidQueryParameterValue",
                        "Value for one of the query parameters specified in the request URI is invalid.")
                        .toXmlResponse(400);
            }
            byte[] data = request.bodyStream().readAllBytes();
            store.put(blockStagingKey(request.accountName(), containerName, blobName, blockId),
                    new StoredObject(blockId, data, Map.of("BlockId", blockId), Instant.now(),
                            UUID.randomUUID().toString()));
            return Response.status(Response.Status.CREATED)
                    .header("x-ms-request-server-encrypted", "true")
                    .header("Content-Length", 0)
                    .build();
        } catch (IOException e) {
            LOGGER.errorf(e, "putBlock I/O error: container=%s blob=%s", containerName, blobName);
            return Response.serverError().build();
        }
    }

    /**
     * PUT /{container}/{blob}?comp=blocklist
     * <p>Commits an ordered list of previously-staged blocks into a blob.
     * After a successful commit, all staged blocks for this blob are deleted.
     */
    private Response putBlockList(AzureRequest request, String containerName, String blobName) {
        try {
            if (store.get(nsKey(request.accountName(), containerName)).isEmpty()) {
                return new AzureErrorResponse("ContainerNotFound", "The specified container does not exist.")
                        .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
            }

            List<String> blockIds = parseBlockList(request.bodyStream().readAllBytes());

            // Resolve every block ID → staged data
            List<byte[]> chunks = new ArrayList<>(blockIds.size());
            List<String> committedMeta = new ArrayList<>(blockIds.size()); // "base64id:size"

            for (String blockId : blockIds) {
                Optional<StoredObject> staged = store.get(
                        blockStagingKey(request.accountName(), containerName, blobName, blockId));
                if (staged.isEmpty()) {
                    return new AzureErrorResponse("InvalidBlockList",
                            "The specified block list is invalid.")
                            .toXmlResponse(400);
                }
                byte[] blockData = staged.get().data();
                chunks.add(blockData);
                committedMeta.add(blockId + ":" + blockData.length);
            }

            // Concatenate all block data into the final blob body
            int totalSize = chunks.stream().mapToInt(c -> c.length).sum();
            byte[] assembled = new byte[totalSize];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, assembled, offset, chunk.length);
                offset += chunk.length;
            }

            // Build blob metadata
            Map<String, String> metadata = new HashMap<>();
            String blobType = request.headers().getHeaderString("x-ms-blob-type");
            metadata.put("BlobType", blobType != null ? blobType : "BlockBlob");
            String ct = request.headers().getHeaderString(HttpHeaders.CONTENT_TYPE);
            metadata.put("Content-Type", ct != null ? ct : "application/octet-stream");
            metadata.put("Name", blobName);
            // Persist committed block list for future GetBlockList calls
            metadata.put("CommittedBlocks", String.join("|", committedMeta));
            metadata.putAll(readUserMetadata(request));

            String etag = UUID.randomUUID().toString();
            store.put(objKey(request.accountName(), containerName, blobName),
                    new StoredObject(blobName, assembled, metadata, Instant.now(), etag));

            // Clean up all staged blocks for this blob
            String stagePrefix = blockStagingPrefix(request.accountName(), containerName, blobName);
            store.keys().stream()
                    .filter(k -> k.startsWith(stagePrefix))
                    .toList()
                    .forEach(store::delete);

            return Response.status(Response.Status.CREATED)
                    .header("Last-Modified", RFC1123_DATE_TIME.format(Instant.now()))
                    .header("ETag", etag)
                    .header("x-ms-request-server-encrypted", "true")
                    .header("Content-Length", 0)
                    .build();
        } catch (IOException e) {
            LOGGER.errorf(e, "putBlockList I/O error: container=%s blob=%s", containerName, blobName);
            return Response.serverError().build();
        }
    }

    /**
     * GET /{container}/{blob}?comp=blocklist[&blocklisttype=committed|uncommitted|all]
     * <p>Returns committed blocks (from blob metadata) and/or uncommitted
     * (staged) blocks, depending on {@code blocklisttype}.
     */
    private Response getBlockList(AzureRequest request, String containerName, String blobName) {
        String listType = request.queryParams().getOrDefault("blocklisttype", "committed");

        List<BlobModels.BlockItem> committed   = new ArrayList<>();
        List<BlobModels.BlockItem> uncommitted = new ArrayList<>();

        if ("committed".equals(listType) || "all".equals(listType)) {
            store.get(objKey(request.accountName(), containerName, blobName))
                 .ifPresent(blob -> {
                     String meta = blob.metadata().getOrDefault("CommittedBlocks", "");
                     if (!meta.isBlank()) {
                         for (String entry : meta.split("\\|")) {
                             String[] parts = entry.split(":", 2);
                             if (parts.length == 2) {
                                 try {
                                     committed.add(new BlobModels.BlockItem(parts[0], Long.parseLong(parts[1])));
                                 } catch (NumberFormatException ignored) {
                                     // corrupt entry — skip
                                 }
                             }
                         }
                     }
                 });
        }

        if ("uncommitted".equals(listType) || "all".equals(listType)) {
            String stagePrefix = blockStagingPrefix(request.accountName(), containerName, blobName);
            store.scan(k -> k.startsWith(stagePrefix)).stream()
                 .map(so -> new BlobModels.BlockItem(so.key(), (long) so.data().length))
                 .forEach(uncommitted::add);
        }

        String body = buildBlockListXml(committed, uncommitted);
        return Response.ok(body).type(MediaType.APPLICATION_XML).build();
    }

    private static String buildBlockListXml(List<BlobModels.BlockItem> committed,
                                            List<BlobModels.BlockItem> uncommitted) {
        XmlBuilder xml = new XmlBuilder()
                .start("BlockList")
                .start("CommittedBlocks");
        appendBlockItems(xml, committed);
        xml.end("CommittedBlocks")
                .start("UncommittedBlocks");
        appendBlockItems(xml, uncommitted);
        return xml.end("UncommittedBlocks")
                .end("BlockList")
                .build();
    }

    private static void appendBlockItems(XmlBuilder xml, List<BlobModels.BlockItem> blocks) {
        for (BlobModels.BlockItem block : blocks) {
            xml.start("Block")
                    .elem("Name", block.Name())
                    .elem("Size", block.Size())
                    .end("Block");
        }
    }

    // ── Block key helpers ─────────────────────────────────────────────────────

    /**
     * Storage key for a single staged block.
     * Format: {@code __blk__:account/container/blobName:blockId}
     * <p>{@code :} is safe as separator — blockIds are Base64 ({@code [A-Za-z0-9+/=]}).
     */
    private static String blockStagingKey(String account, String container,
                                           String blobName, String blockId) {
        return BLK_PREFIX + objKey(account, container, blobName) + ":" + blockId;
    }

    /** Prefix that matches all staged blocks for a given blob. */
    private static String blockStagingPrefix(String account, String container, String blobName) {
        return BLK_PREFIX + objKey(account, container, blobName) + ":";
    }

    /**
     * Parses the block IDs from a PutBlockList XML body.
     * Matches {@code <Latest>}, {@code <Committed>}, and {@code <Uncommitted>} elements
     * in document order — Azure treats all three as "use this block".
     */
    private static List<String> parseBlockList(byte[] body) {
        String xml = new String(body, StandardCharsets.UTF_8);
        List<String> ids = new ArrayList<>();
        Matcher m = BLOCK_LIST_PATTERN.matcher(xml);
        while (m.find()) {
            ids.add(m.group(1).trim());
        }
        return ids;
    }

    public void clearAll() {
        store.clear();
    }

    public void ensureContainer(String accountName, String containerName) {
        store.put(nsKey(accountName, containerName), NS_SENTINEL);
    }

    private static String nsKey(String accountName, String containerName) {
        return NS_PREFIX + accountName + "/" + containerName;
    }

    private static String objKey(String accountName, String containerName, String blobName) {
        return accountName + "/" + containerName + "/" + blobName;
    }

    private static Map<String, String> readUserMetadata(AzureRequest request) {
        Map<String, String> metadata = new HashMap<>();
        request.headers().getRequestHeaders().forEach((name, values) -> {
            if (name.toLowerCase(Locale.ROOT).startsWith("x-ms-meta-") && !values.isEmpty()) {
                metadata.put(USER_METADATA_PREFIX + name.substring("x-ms-meta-".length()).toLowerCase(Locale.ROOT),
                        values.get(0));
            }
        });
        return metadata;
    }

    private static Map<String, String> userMetadata(Map<String, String> storedMetadata) {
        return storedMetadata.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(USER_METADATA_PREFIX))
                .collect(Collectors.toMap(
                        entry -> entry.getKey().substring(USER_METADATA_PREFIX.length()),
                        Map.Entry::getValue,
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
    }

    private static void addUserMetadataHeaders(Response.ResponseBuilder rb, Map<String, String> storedMetadata) {
        userMetadata(storedMetadata).forEach((key, value) -> rb.header("x-ms-meta-" + key, value));
    }

    private static boolean includes(String include, String value) {
        if (include == null || include.isBlank()) {
            return false;
        }
        return Arrays.stream(include.split(","))
                .map(String::trim)
                .anyMatch(value::equalsIgnoreCase);
    }

    private static Response validateBlobConditions(AzureRequest request, Optional<StoredObject> object) {
        String ifMatch = request.headers().getHeaderString(HttpHeaders.IF_MATCH);
        if (ifMatch != null && object.map(StoredObject::etag).filter(etag -> etagMatches(ifMatch, etag)).isEmpty()) {
            return new AzureErrorResponse("ConditionNotMet", "The condition specified using HTTP conditional header(s) is not met.")
                    .toXmlResponse(Response.Status.PRECONDITION_FAILED.getStatusCode());
        }

        String ifNoneMatch = request.headers().getHeaderString(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null && object.map(StoredObject::etag).filter(etag -> etagMatches(ifNoneMatch, etag)).isPresent()) {
            return new AzureErrorResponse("ConditionNotMet", "The condition specified using HTTP conditional header(s) is not met.")
                    .toXmlResponse(Response.Status.PRECONDITION_FAILED.getStatusCode());
        }
        return null;
    }

    private static boolean etagMatches(String condition, String etag) {
        if ("*".equals(condition.trim())) {
            return true;
        }
        return Arrays.stream(condition.split(","))
                .map(String::trim)
                .map(BlobServiceHandler::unquote)
                .anyMatch(candidate -> candidate.equals(unquote(etag)));
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
