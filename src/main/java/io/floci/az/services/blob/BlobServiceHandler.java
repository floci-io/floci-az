package io.floci.az.services.blob;

import io.floci.az.core.AzureErrorResponse;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AuthType;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.Resettable;
import io.floci.az.core.StoredObject;
import io.floci.az.core.auth.StorageSasAuthorization;
import io.floci.az.core.auth.StorageSasToken;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class BlobServiceHandler implements AzureServiceHandler, Resettable {

    private static final Logger LOGGER = Logger.getLogger(BlobServiceHandler.class);
    private static final DateTimeFormatter RFC1123_DATE_TIME = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneId.of("GMT"));

    private static final String NS_PREFIX  = "__ns__:";
    private static final String BLK_PREFIX = "__blk__:";
    private static final String USER_METADATA_PREFIX = "UserMeta:";
    private static final String CREATION_TIME_KEY = "CreationTime";
    private static final String DATALAKE_APPEND_PREFIX = "__abfs_append__:";
    private static final String DATALAKE_OWNER = DataLakePathOperations.DEFAULT_OWNER;
    private static final String DATALAKE_GROUP = DataLakePathOperations.DEFAULT_GROUP;
    private static final String DATALAKE_FILE_PERMISSIONS = DataLakePathOperations.DEFAULT_FILE_PERMISSIONS;
    private static final String DATALAKE_DIRECTORY_PERMISSIONS = DataLakePathOperations.DEFAULT_DIRECTORY_PERMISSIONS;
    private static final String DATALAKE_RESOURCE_TYPE_KEY = DataLakePathOperations.RESOURCE_TYPE;
    private static final String DATALAKE_OWNER_KEY = DataLakePathOperations.OWNER_KEY;
    private static final String DATALAKE_GROUP_KEY = DataLakePathOperations.GROUP_KEY;
    private static final String DATALAKE_PERMISSIONS_KEY = DataLakePathOperations.PERMISSIONS_KEY;
    private static final String DATALAKE_ACL_KEY = DataLakePathOperations.ACL_KEY;
    private static final String DATALAKE_PROPERTIES_KEY = DataLakePathOperations.PROPERTIES_KEY;
    private static final String DATALAKE_FILESYSTEM_PROPERTIES_KEY = "DataLakeFilesystemProperties";
    private static final String DATALAKE_DEFAULT_FILE_ACL = "user::rw-,group::r--,other::---";
    private static final String DATALAKE_DEFAULT_DIRECTORY_ACL = "user::rwx,group::r-x,other::---";
    private static final Map<String, String> BLOB_HTTP_PROPERTY_HEADERS = Map.of(
            "x-ms-blob-cache-control", HttpHeaders.CACHE_CONTROL,
            "x-ms-blob-content-disposition", "Content-Disposition",
            "x-ms-blob-content-encoding", HttpHeaders.CONTENT_ENCODING,
            "x-ms-blob-content-language", HttpHeaders.CONTENT_LANGUAGE,
            "x-ms-blob-content-md5", "Content-MD5");
    private static final StoredObject NS_SENTINEL =
            new StoredObject("", new byte[0], Map.of(), Instant.EPOCH, "");

    /**
     * Matches {@code <Latest>}, {@code <Committed>}, or {@code <Uncommitted>} elements
     * inside a PutBlockList XML body — e.g. {@code <Latest>BASE64ID</Latest>}.
     */
    private static final Pattern BLOCK_LIST_PATTERN =
            Pattern.compile("<(?:Latest|Committed|Uncommitted)>([^<]+)</(?:Latest|Committed|Uncommitted)>");

    private final StorageBackend<String, StoredObject> store;

    private final EmulatorConfig config;

    private final UserDelegationKeyService userDelegationKeyService;
    private final StorageSasAuthorization sasAuthorization;
    private final DataLakePathOperations dataLakePathOperations;

    private final BlobLeaseService leaseService;

    @Inject
    public BlobServiceHandler(StorageFactory storageFactory, EmulatorConfig config,
                              UserDelegationKeyService userDelegationKeyService,
                              StorageSasAuthorization sasAuthorization,
                              BlobLeaseService leaseService) {
        this.config = config;
        this.userDelegationKeyService = userDelegationKeyService;
        this.sasAuthorization = sasAuthorization;
        this.leaseService = leaseService;
        this.store = storageFactory.create("blob");
        this.dataLakePathOperations = new DataLakePathOperations(store);
    }

    @Override
    public String getServiceType() {
        return "blob";
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().blob().enabled();
    }


    @Override

    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .host(".blob.core.windows.net")
                .host(".dfs.core.windows.net")   // Data Lake Gen2 shares the blob handler
                .build();

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
        if (request.authContext() != null && !request.authContext().isValid()) {
            response = new AzureErrorResponse("AuthenticationFailed",
                    "Server failed to authenticate the request. Make sure the value of Authorization header "
                            + "is formed correctly including the signature.")
                    .toXmlResponse(Response.Status.FORBIDDEN.getStatusCode());
        } else if (path.isEmpty() || path.equals("/")) {
            if ("GET".equalsIgnoreCase(method) && "list".equals(query.get("comp"))) {
                response = listContainers(request);
            } else if ("POST".equalsIgnoreCase(method) && "service".equals(query.get("restype"))
                    && "userdelegationkey".equals(query.get("comp"))) {
                response = getUserDelegationKey(request);
            } else if ("service".equals(query.get("restype")) && "properties".equals(query.get("comp"))) {
                if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
                    response = getBlobServiceProperties();
                } else if ("PUT".equalsIgnoreCase(method)) {
                    response = Response.accepted().build();
                } else {
                    response = notImplemented();
                }
            } else {
                response = notImplemented();
            }
        } else {
            String[] parts = path.split("/", 2);
            String containerName = parts[0];
            String blobName = parts.length > 1 ? parts[1] : "";

            String comp = query.get("comp");
            String action = query.get("action");
            boolean dataLakeRequest = isDataLakeRequest(request);

            if (blobName.isEmpty()) {
                if (dataLakeRequest && "filesystem".equals(query.get("resource"))
                        && (comp != null || action != null)) {
                    // ADLS filesystem operations do not use Blob-style `comp` or path `action`
                    // dispatch. Fail closed so unsupported query shapes cannot be mistaken for
                    // List Paths or another filesystem operation.
                    response = dataLakeNotImplemented();
                } else if (dataLakeRequest && "filesystem".equals(query.get("resource"))) {
                    if ("GET".equalsIgnoreCase(method)) {
                        response = listDataLakePaths(request, containerName);
                    } else if ("HEAD".equalsIgnoreCase(method)) {
                        response = getDataLakeFilesystemProperties(request, containerName);
                    } else if ("PUT".equalsIgnoreCase(method)
                            && "PATCH".equalsIgnoreCase(request.headers().getHeaderString("X-Http-Method-Override"))) {
                        response = setDataLakeFilesystemProperties(request, containerName);
                    } else if ("PUT".equalsIgnoreCase(method)) {
                        response = createDataLakeFilesystem(request, containerName);
                    } else if ("DELETE".equalsIgnoreCase(method)) {
                        response = deleteDataLakeFilesystem(request, containerName);
                    } else {
                        response = dataLakeNotImplemented();
                    }
                } else if (dataLakeRequest && "HEAD".equalsIgnoreCase(method)
                        && (action == null || "getStatus".equals(action))) {
                    response = getDataLakeRootPathStatus(request, containerName);
                } else if (dataLakeRequest && "HEAD".equalsIgnoreCase(method)
                        && "getAccessControl".equals(action)) {
                    response = getDataLakeAccessControl(request, containerName, null);
                } else if (dataLakeRequest && "HEAD".equalsIgnoreCase(method)
                        && "checkAccess".equals(action)) {
                    response = checkDataLakeAccess(request, containerName, null);
                } else if (dataLakeRequest && "PUT".equalsIgnoreCase(method)
                        && "setAccessControl".equals(action)) {
                    response = setDataLakeAccessControl(request, containerName, null);
                } else if (dataLakeRequest && "PUT".equalsIgnoreCase(method)
                        && "setProperties".equals(action)) {
                    response = setDataLakePathProperties(request, containerName, null);
                } else if ("GET".equalsIgnoreCase(method) && "list".equals(comp)) {
                    response = listBlobs(request, containerName);
                } else if (comp != null) {
                    // Container ops are multiplexed onto the same URL by `comp` (metadata, acl,
                    // lease, ...). None are implemented. This branch must stay ABOVE the
                    // restype=container ones: they ignore `comp`, so SetContainerMetadata would
                    // otherwise land in createContainer and answer 409.
                    response = notImplemented();
                } else if ("PUT".equalsIgnoreCase(method) && "container".equals(query.get("restype"))) {
                    response = createContainer(request, containerName);
                } else if ("DELETE".equalsIgnoreCase(method) && "container".equals(query.get("restype"))) {
                    response = deleteContainer(request, containerName);
                } else if (("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) && "container".equals(query.get("restype"))) {
                    response = getContainer(request, containerName, "HEAD".equalsIgnoreCase(method));
                } else {
                    response = dataLakeRequest ? dataLakeNotImplemented() : notImplemented();
                }
            } else {
                String renameSource = request.headers().getHeaderString("x-ms-rename-source");

                if (dataLakeRequest && "PUT".equalsIgnoreCase(method) && renameSource != null) {
                    response = renameDataLakePath(request, containerName, blobName, renameSource);
                } else if (dataLakeRequest && "HEAD".equalsIgnoreCase(method)
                        && (action == null || "getStatus".equals(action))) {
                    response = getDataLakePathStatus(request, containerName, blobName);
                } else if (dataLakeRequest && "HEAD".equalsIgnoreCase(method)
                        && "getAccessControl".equals(action)) {
                    response = getDataLakeAccessControl(request, containerName, blobName);
                } else if (dataLakeRequest && "HEAD".equalsIgnoreCase(method)
                        && "checkAccess".equals(action)) {
                    response = checkDataLakeAccess(request, containerName, blobName);
                } else if (dataLakeRequest && "PUT".equalsIgnoreCase(method) && "append".equals(action)) {
                    response = appendDataLakePath(request, containerName, blobName);
                } else if (dataLakeRequest && "PUT".equalsIgnoreCase(method) && "flush".equals(action)) {
                    response = flushDataLakePath(request, containerName, blobName);
                } else if (dataLakeRequest && "PUT".equalsIgnoreCase(method) && "setProperties".equals(action)) {
                    response = setDataLakePathProperties(request, containerName, blobName);
                } else if (dataLakeRequest && "PUT".equalsIgnoreCase(method) && "setAccessControl".equals(action)) {
                    response = setDataLakeAccessControl(request, containerName, blobName);
                } else if (dataLakeRequest && "POST".equalsIgnoreCase(method)
                        && request.headers().getHeaderString("x-ms-lease-action") != null) {
                    response = leaseDataLakePath(request, containerName, blobName);
                } else if (dataLakeRequest && "PUT".equalsIgnoreCase(method)
                        && action == null && ("file".equals(query.get("resource"))
                        || "directory".equals(query.get("resource")))) {
                    response = createDataLakePath(request, containerName, blobName);
                } else if (dataLakeRequest && action != null) {
                    // Never allow an ADLS Path Update action to fall through into PutBlob:
                    // Hadoop setProperties/setAccessControl requests have empty bodies and a
                    // generic blob write would silently truncate the file.
                    response = dataLakeNotImplemented();
                } else if (dataLakeRequest && "PUT".equalsIgnoreCase(method)) {
                    // Every Hadoop ABFS 3.3.4 DFS PUT shape is explicitly handled above
                    // (create, rename, append/flush, properties, ACL). An unknown DFS PUT
                    // must fail closed rather than being mistaken for a Blob Put operation.
                    response = dataLakeNotImplemented();
                } else if ("PUT".equalsIgnoreCase(method) && "lease".equals(comp)) {
                    response = leaseBlob(request, containerName, blobName);
                } else if ("PUT".equalsIgnoreCase(method) && "metadata".equals(comp)) {
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
                } else if ("PUT".equalsIgnoreCase(method) && isPutBlob(request, comp)) {
                    response = putBlob(request, containerName, blobName);
                } else if (dataLakeRequest && "GET".equalsIgnoreCase(method)) {
                    response = readDataLakePath(request, containerName, blobName);
                } else if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
                    response = getBlob(request, containerName, blobName, "HEAD".equalsIgnoreCase(method));
                } else if ("DELETE".equalsIgnoreCase(method)) {
                    response = dataLakeRequest
                            ? deleteDataLakePath(request, containerName, blobName)
                            : deleteBlob(request, containerName, blobName);
                } else {
                    response = dataLakeRequest ? dataLakeNotImplemented() : notImplemented();
                }
            }
        }

        if (isDataLakeRequest(request) && response.getStatus() >= 400) {
            response = normalizeDataLakeErrorResponse(response);
        }

        return Response.fromResponse(response)
                .header("x-ms-request-id", UUID.randomUUID().toString())
                .header("x-ms-version", request.headers().getHeaderString("x-ms-version"))
                .header("Date", RFC1123_DATE_TIME.format(Instant.now()))
                .build();
    }

    private Response getUserDelegationKey(AzureRequest request) {
        if (request.authContext() == null || request.authContext().type() != AuthType.BEARER) {
            return new AzureErrorResponse("AuthenticationFailed",
                    "Server failed to authenticate the request. Make sure the value of Authorization header "
                            + "is formed correctly including the signature.")
                    .toXmlResponse(Response.Status.FORBIDDEN.getStatusCode());
        }
        return userDelegationKeyService.create(request);
    }

    private Response notImplemented() {
        return new AzureErrorResponse("NotImplemented", "The requested operation is not implemented.")
                .toXmlResponse(501);
    }

    private Response dataLakeNotImplemented() {
        return new AzureErrorResponse("NotImplemented", "The requested Data Lake operation is not implemented.")
                .toDataLakeJsonResponse(501);
    }

    private static Response normalizeDataLakeErrorResponse(Response response) {
        if (response.getMediaType() != null
                && response.getMediaType().isCompatible(MediaType.APPLICATION_JSON_TYPE)) {
            return response;
        }
        String code = response.getHeaderString("x-ms-error-code");
        if (code == null || code.isBlank()) {
            code = response.getStatus() == 404 ? "PathNotFound" : "OperationFailed";
        }
        return Response.fromResponse(response)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .header("x-ms-error-code", code)
                .entity(Map.of("error", Map.of(
                        "code", code,
                        "message", "The requested Data Lake operation failed.")))
                .build();
    }

    /** PUT /{container}/{blob}?comp=lease — Lease Blob (acquire/renew/change/release/break). */
    private Response leaseBlob(AzureRequest request, String containerName, String blobName) {
        // The existence check must share the lease monitor, or an acquire can
        // install a lease for a blob a concurrent delete just removed.
        return leaseService.exclusively(() -> {
            Optional<StoredObject> object = store.get(objKey(request.accountName(), containerName, blobName));
            if (object.isEmpty()) {
                return new AzureErrorResponse("BlobNotFound", "The specified blob does not exist.")
                        .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
            }
            return leaseService.handleLeaseOp(request, objKey(request.accountName(), containerName, blobName),
                    object.get().etag(), RFC1123_DATE_TIME.format(object.get().lastModified()));
        });
    }

    /**
     * True only for a genuine PutBlob.
     *
     * <p>Azure multiplexes many operations onto {@code PUT /{container}/{blob}}: the {@code comp}
     * values this handler does not implement (lease, snapshot, properties, tier, tags, page,
     * appendblock, ...), plus CopyBlob and the Data Lake rename, which carry no {@code comp} at all
     * and are discriminated by a header. Routing any of those to {@code putBlob} replaces the blob
     * with the request body — usually empty — and answers 201, so the caller sees success while the
     * content is destroyed. Only an unqualified PUT is a PutBlob.
     */
    private boolean isPutBlob(AzureRequest request, String comp) {
        return comp == null
                && request.headers().getHeaderString("x-ms-copy-source") == null
                && request.headers().getHeaderString("x-ms-rename-source") == null;
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
            .build();
        return Response.ok(xml, "application/xml").build();
    }

    private Response getContainer(AzureRequest request, String containerName, boolean headOnly) {
        Response authFailure = authorizeRead(request, containerName, null);
        if (authFailure != null) {
            return authFailure;
        }
        if (store.get(nsKey(request.accountName(), containerName)).isEmpty()) {
            return new AzureErrorResponse("ContainerNotFound", "The specified container does not exist.")
                    .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
        }
        return Response.ok()
                .header("Last-Modified", RFC1123_DATE_TIME.format(Instant.now()))
                .header("ETag", UUID.randomUUID().toString())
                // Get Container Properties always reports the lease state, and strict SDK clients
                // read it unconditionally. Leases are not modelled, so a container is always available.
                .header("x-ms-lease-state", "available")
                .header("x-ms-lease-status", "unlocked")
                .header("x-ms-has-immutability-policy", "false")
                .header("x-ms-has-legal-hold", "false")
                .build();
    }

    private Response createContainer(AzureRequest request, String containerName) {
        Response authFailure = authorizeCreate(request, containerName, null);
        if (authFailure != null) {
            return authFailure;
        }
        String key = nsKey(request.accountName(), containerName);
        // Check-and-create must share the lease monitor, or a create that
        // observed absence can re-put the sentinel after a concurrent deletion
        // sweep (resurrecting the container after DELETE answered 202), and
        // two concurrent creates can both answer 201.
        return leaseService.exclusively(() -> {
            if (store.get(key).isPresent()) {
                return new AzureErrorResponse("ContainerAlreadyExists", "The specified container already exists.")
                        .toXmlResponse(Response.Status.CONFLICT.getStatusCode());
            }
            store.put(key, NS_SENTINEL);
            return Response.status(Response.Status.CREATED)
                    .header("Last-Modified", RFC1123_DATE_TIME.format(Instant.now()))
                    .header("ETag", UUID.randomUUID().toString())
                    .build();
        });
    }

    private Response deleteContainer(AzureRequest request, String containerName) {
        Response authFailure = authorizeDelete(request, containerName, null);
        if (authFailure != null) {
            return authFailure;
        }
        // The sweep runs under the lease monitor so no lease op or guarded
        // write can interleave and resurrect blob or lease state mid-deletion.
        return leaseService.exclusively(() -> {
            store.delete(nsKey(request.accountName(), containerName));
            String objPrefix = request.accountName() + "/" + containerName + "/";
            String blkPrefix = BLK_PREFIX + objPrefix;
            String appendPrefix = DATALAKE_APPEND_PREFIX + objPrefix;
            store.keys().stream()
                    .filter(k -> k.startsWith(objPrefix) || k.startsWith(blkPrefix) || k.startsWith(appendPrefix))
                    .toList()
                    .forEach(store::delete);
            leaseService.onContainerDeleted(objPrefix);
            return Response.status(Response.Status.ACCEPTED).build();
        });
    }

    private Response listContainers(AzureRequest request) {
        Response authFailure = authorizeList(request, null);
        if (authFailure != null) {
            return authFailure;
        }
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
            return leaseService.exclusively(() -> {
                // Create-vs-write is classified from blob existence, so the
                // classification must read the same snapshot the mutation
                // uses: outside the monitor, a create-only SAS that observed
                // absence could overwrite a concurrently created blob.
                Optional<StoredObject> existing = store.get(objKey(request.accountName(), containerName, blobName));
                Response authFailure = existing.isPresent()
                        ? authorizeWrite(request, containerName, blobName)
                        : authorizeCreate(request, containerName, blobName);
                if (authFailure != null) {
                    return authFailure;
                }
                if (store.get(nsKey(request.accountName(), containerName)).isEmpty()) {
                    return new AzureErrorResponse("ContainerNotFound", "The specified container does not exist.")
                            .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
                }

                Response conditionFailure = validateBlobConditions(request, existing);
                if (conditionFailure != null) {
                    return conditionFailure;
                }
                Response leaseFailure = leaseService.validateWrite(request,
                        objKey(request.accountName(), containerName, blobName));
                if (leaseFailure != null) {
                    return leaseFailure;
                }

                Map<String, String> metadata = new HashMap<>();
                String blobType = request.headers().getHeaderString("x-ms-blob-type");
                metadata.put("BlobType", blobType != null ? blobType : "BlockBlob");
                addBlobHttpProperties(request, metadata);
                String dataLakeResourceType = request.queryParams().get("resource");
                if ("file".equals(dataLakeResourceType) || "directory".equals(dataLakeResourceType)) {
                    metadata.put("DataLakeResourceType", dataLakeResourceType);
                }
                metadata.put("Name", blobName);
                metadata.put(CREATION_TIME_KEY, createdOn(existing).toString());
                metadata.putAll(readUserMetadata(request));

                String etag = UUID.randomUUID().toString();
                store.put(objKey(request.accountName(), containerName, blobName),
                        new StoredObject(blobName, data, metadata, Instant.now(), etag));
                if ("file".equals(dataLakeResourceType)) {
                    clearDataLakeAppendChunks(request.accountName(), containerName, blobName);
                }

                return Response.status(Response.Status.CREATED)
                        .header("Last-Modified", RFC1123_DATE_TIME.format(Instant.now()))
                        .header("ETag", etag)
                        .header("x-ms-request-server-encrypted", "true")
                        .header("Content-Length", 0)
                        .build();
            });
        } catch (IOException e) {
            return Response.serverError().build();
        }
    }


    // ── ADLS Gen2 filesystem/path compatibility for Hadoop ABFS 3.3.4 ───────

    private Response createDataLakeFilesystem(AzureRequest request, String filesystem) {
        Response authFailure = authorizeCreate(request, filesystem, null);
        if (authFailure != null) {
            return authFailure;
        }
        return leaseService.exclusively(() -> {
            String key = nsKey(request.accountName(), filesystem);
            if (store.get(key).isPresent()) {
                return new AzureErrorResponse("FilesystemAlreadyExists",
                        "The specified filesystem already exists.")
                        .toDataLakeJsonResponse(Response.Status.CONFLICT.getStatusCode());
            }
            Instant now = Instant.now();
            Map<String, String> metadata = defaultDataLakeMetadata("", true, null);
            metadata.put(DATALAKE_FILESYSTEM_PROPERTIES_KEY,
                    headerOrEmpty(request, "x-ms-properties"));
            metadata.put(CREATION_TIME_KEY, now.toString());
            String etag = UUID.randomUUID().toString();
            store.put(key, new StoredObject("", new byte[0], metadata, now, etag));
            return Response.status(Response.Status.CREATED)
                    .header("Last-Modified", RFC1123_DATE_TIME.format(now))
                    .header("ETag", etag)
                    .build();
        });
    }

    private Response getDataLakeRootPathStatus(AzureRequest request, String filesystem) {
        Response authFailure = authorizeRead(request, filesystem, null);
        if (authFailure != null) {
            return authFailure;
        }
        Optional<StoredObject> sentinel = store.get(nsKey(request.accountName(), filesystem));
        if (sentinel.isEmpty()) {
            return new AzureErrorResponse("FilesystemNotFound", "The specified filesystem does not exist.")
                    .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
        }
        StoredObject object = sentinel.get();
        Map<String, String> metadata = object.metadata();
        Response.ResponseBuilder builder = Response.ok()
                .header("Last-Modified", RFC1123_DATE_TIME.format(effectiveLastModified(object)))
                .header("ETag", effectiveEtag(object, nsKey(request.accountName(), filesystem)))
                .header(HttpHeaders.CONTENT_LENGTH, 0)
                .header("x-ms-resource-type", "directory")
                .header("x-ms-owner", metadata.getOrDefault(DATALAKE_OWNER_KEY, DATALAKE_OWNER))
                .header("x-ms-group", metadata.getOrDefault(DATALAKE_GROUP_KEY, DATALAKE_GROUP))
                .header("x-ms-permissions", metadata.getOrDefault(
                        DATALAKE_PERMISSIONS_KEY, DATALAKE_DIRECTORY_PERMISSIONS))
                .header("x-ms-request-server-encrypted", "true");
        if (request.queryParams().get("action") == null) {
            builder.header("x-ms-properties", metadata.getOrDefault(DATALAKE_PROPERTIES_KEY, ""));
        }
        return builder.build();
    }

    private Response getDataLakeFilesystemProperties(AzureRequest request, String filesystem) {
        Response authFailure = authorizeRead(request, filesystem, null);
        if (authFailure != null) {
            return authFailure;
        }
        Optional<StoredObject> sentinel = store.get(nsKey(request.accountName(), filesystem));
        if (sentinel.isEmpty()) {
            return new AzureErrorResponse("FilesystemNotFound", "The specified filesystem does not exist.")
                    .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
        }
        StoredObject so = sentinel.get();
        Instant modified = effectiveLastModified(so);
        return Response.ok()
                .header("Last-Modified", RFC1123_DATE_TIME.format(modified))
                .header("ETag", effectiveEtag(so, nsKey(request.accountName(), filesystem)))
                .header("x-ms-properties", so.metadata().getOrDefault(DATALAKE_FILESYSTEM_PROPERTIES_KEY, ""))
                .build();
    }

    private Response setDataLakeFilesystemProperties(AzureRequest request, String filesystem) {
        Response authFailure = authorizeWrite(request, filesystem, null);
        if (authFailure != null) {
            return authFailure;
        }
        return leaseService.exclusively(() -> {
            String key = nsKey(request.accountName(), filesystem);
            Optional<StoredObject> sentinel = store.get(key);
            if (sentinel.isEmpty()) {
                return new AzureErrorResponse("FilesystemNotFound", "The specified filesystem does not exist.")
                        .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
            }
            StoredObject current = sentinel.get();
            Map<String, String> metadata = new HashMap<>(current.metadata());
            metadata.put(DATALAKE_FILESYSTEM_PROPERTIES_KEY, headerOrEmpty(request, "x-ms-properties"));
            ensureDataLakeDefaults(metadata, true);
            Instant now = Instant.now();
            String etag = UUID.randomUUID().toString();
            store.put(key, new StoredObject(current.key(), current.data(), metadata, now, etag));
            return Response.ok()
                    .header("Last-Modified", RFC1123_DATE_TIME.format(now))
                    .header("ETag", etag)
                    .build();
        });
    }

    private Response deleteDataLakeFilesystem(AzureRequest request, String filesystem) {
        Response authFailure = authorizeDelete(request, filesystem, null);
        if (authFailure != null) {
            return authFailure;
        }
        return leaseService.exclusively(() -> {
            String nsKey = nsKey(request.accountName(), filesystem);
            if (store.get(nsKey).isEmpty()) {
                return new AzureErrorResponse("FilesystemNotFound", "The specified filesystem does not exist.")
                        .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
            }
            store.delete(nsKey);
            String objectPrefix = request.accountName() + "/" + filesystem + "/";
            String blockPrefix = BLK_PREFIX + objectPrefix;
            String appendPrefix = DATALAKE_APPEND_PREFIX + objectPrefix;
            store.keys().stream()
                    .filter(key -> key.startsWith(objectPrefix)
                            || key.startsWith(blockPrefix)
                            || key.startsWith(appendPrefix))
                    .toList()
                    .forEach(store::delete);
            leaseService.onContainerDeleted(objectPrefix);
            return Response.status(Response.Status.ACCEPTED).build();
        });
    }

    /** Hadoop createPath(), including its default conditional-overwrite flow. */
    private Response createDataLakePath(AzureRequest request, String filesystem, String path) {
        String contentLengthHeader = request.headers().getHeaderString(HttpHeaders.CONTENT_LENGTH);
        if (contentLengthHeader != null) {
            try {
                if (Long.parseLong(contentLengthHeader) != 0L) {
                    return new AzureErrorResponse("ContentLengthMustBeZero",
                            "The Content-Length request header must be zero.")
                            .toDataLakeJsonResponse(Response.Status.BAD_REQUEST.getStatusCode());
                }
            } catch (NumberFormatException e) {
                return new AzureErrorResponse("InvalidHeaderValue",
                        "The value for one of the HTTP headers is not in the correct format.")
                        .toDataLakeJsonResponse(Response.Status.BAD_REQUEST.getStatusCode());
            }
        }

        boolean directory = "directory".equals(request.queryParams().get("resource"));
        String key = objKey(request.accountName(), filesystem, path);
        return leaseService.exclusively(() -> {
            if (store.get(nsKey(request.accountName(), filesystem)).isEmpty()) {
                return new AzureErrorResponse("FilesystemNotFound", "The specified filesystem does not exist.")
                        .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
            }

            DataLakePathState state = resolveDataLakePath(request.accountName(), filesystem, path);
            boolean exists = state != null;
            Response authFailure = exists
                    ? authorizeWrite(request, filesystem, path)
                    : authorizeCreate(request, filesystem, path);
            if (authFailure != null) {
                return authFailure;
            }

            String ifNoneMatch = request.headers().getHeaderString(HttpHeaders.IF_NONE_MATCH);
            if (exists && ifNoneMatch != null && "*".equals(ifNoneMatch.trim())) {
                Response error = new AzureErrorResponse("PathAlreadyExists",
                        "The specified path already exists.")
                        .toDataLakeJsonResponse(Response.Status.CONFLICT.getStatusCode());
                return Response.fromResponse(error)
                        .header("x-ms-existing-resource-type", state.directory() ? "directory" : "file")
                        .build();
            }

            String ifMatch = request.headers().getHeaderString(HttpHeaders.IF_MATCH);
            if (ifMatch != null && (state == null || !etagMatches(ifMatch, state.object().etag()))) {
                return new AzureErrorResponse("ConditionNotMet",
                        "The condition specified using HTTP conditional header(s) is not met.")
                        .toDataLakeJsonResponse(Response.Status.PRECONDITION_FAILED.getStatusCode());
            }

            if (state != null && state.directory() != directory) {
                return new AzureErrorResponse("PathConflict",
                        "The path conflicts with an existing resource of a different type.")
                        .toDataLakeJsonResponse(Response.Status.CONFLICT.getStatusCode());
            }

            Response leaseFailure = state != null && !state.implicit()
                    ? leaseService.validateDataLakeWrite(request, key)
                    : null;
            if (leaseFailure != null) {
                return leaseFailure;
            }

            Optional<StoredObject> existing = store.get(key);
            Map<String, String> metadata = defaultDataLakeMetadata(path, directory, request);
            metadata.put(CREATION_TIME_KEY, createdOn(existing).toString());
            if (existing.isPresent()) {
                // Conditional overwrite replaces file content but keeps path identity and user
                // properties. Hadoop supplies x-ms-permissions/x-ms-umask on create; when it does
                // not, preserve the existing POSIX mode/ACL rather than resetting them to defaults.
                Map<String, String> currentMetadata = existing.get().metadata();
                copyIfPresent(currentMetadata, metadata, DATALAKE_PROPERTIES_KEY);
                copyIfPresent(currentMetadata, metadata, DATALAKE_OWNER_KEY);
                copyIfPresent(currentMetadata, metadata, DATALAKE_GROUP_KEY);
                String requestedPermissions = request.headers().getHeaderString("x-ms-permissions");
                if (requestedPermissions == null || requestedPermissions.isBlank()) {
                    copyIfPresent(currentMetadata, metadata, DATALAKE_PERMISSIONS_KEY);
                    copyIfPresent(currentMetadata, metadata, DATALAKE_ACL_KEY);
                } else {
                    metadata.put(DATALAKE_ACL_KEY, applyPermissionsToAcl(
                            currentMetadata.get(DATALAKE_ACL_KEY), metadata.get(DATALAKE_PERMISSIONS_KEY)));
                }
            }

            String requestedBlobType = request.queryParams().get("blobType");
            metadata.put("BlobType", "AppendBlob".equalsIgnoreCase(requestedBlobType)
                    ? "AppendBlob" : "BlockBlob");
            String etag = UUID.randomUUID().toString();
            Instant now = Instant.now();
            store.put(key, new StoredObject(path, new byte[0], metadata, now, etag));
            if (!directory) {
                clearDataLakeAppendChunks(request.accountName(), filesystem, path);
            }
            return Response.status(Response.Status.CREATED)
                    .header("Last-Modified", RFC1123_DATE_TIME.format(now))
                    .header("ETag", etag)
                    .header("x-ms-request-server-encrypted", "true")
                    .header(HttpHeaders.CONTENT_LENGTH, 0)
                    .build();
        });
    }

    private Response setDataLakePathProperties(AzureRequest request, String filesystem, String path) {
        Response authFailure = authorizeWrite(request, filesystem, path);
        if (authFailure != null) {
            return authFailure;
        }
        return leaseService.exclusively(() -> {
            DataLakePathState state = resolveDataLakePath(request.accountName(), filesystem, path);
            if (state == null) {
                return new AzureErrorResponse("PathNotFound", "The specified path does not exist.")
                        .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
            }
            boolean root = path == null || path.isBlank() || "/".equals(path);
            // Validate against the visible ETag first. Implicit directories expose a
            // deterministic synthetic ETag; materializing a marker before this check
            // would replace it and make Hadoop getAclStatus -> setAcl If-Match fail.
            Response conditionFailure = validateDataLakeConditions(request, Optional.of(state.object()));
            if (conditionFailure != null) {
                return conditionFailure;
            }
            StoredObject current = root
                    ? state.object()
                    : materializeIfImplicitDirectory(request.accountName(), filesystem, path, state);
            if (!root) {
                Response leaseFailure = leaseService.validateDataLakeWrite(request,
                        objKey(request.accountName(), filesystem, path));
                if (leaseFailure != null) {
                    return leaseFailure;
                }
            }
            Map<String, String> metadata = new HashMap<>(current.metadata());
            metadata.put(DATALAKE_PROPERTIES_KEY, headerOrEmpty(request, "x-ms-properties"));
            ensureDataLakeDefaults(metadata, state.directory());
            String etag = UUID.randomUUID().toString();
            Instant now = Instant.now();
            String storageKey = root ? nsKey(request.accountName(), filesystem)
                    : objKey(request.accountName(), filesystem, path);
            String objectName = root ? current.key() : path;
            store.put(storageKey,
                    new StoredObject(objectName, current.data(), metadata, now, etag));
            return Response.ok()
                    .header("Last-Modified", RFC1123_DATE_TIME.format(now))
                    .header("ETag", etag)
                    .build();
        });
    }

    private Response getDataLakeAccessControl(AzureRequest request, String filesystem, String path) {
        Response authFailure = authorizeRead(request, filesystem, path);
        if (authFailure != null) {
            return authFailure;
        }
        if (store.get(nsKey(request.accountName(), filesystem)).isEmpty()) {
            return new AzureErrorResponse("FilesystemNotFound", "The specified filesystem does not exist.")
                    .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
        }
        DataLakePathState state = resolveDataLakePath(request.accountName(), filesystem, path);
        if (state == null) {
            return new AzureErrorResponse("PathNotFound", "The specified path does not exist.")
                    .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
        }
        StoredObject object = state.object();
        Map<String, String> metadata = object.metadata();
        String permissions = metadata.getOrDefault(DATALAKE_PERMISSIONS_KEY,
                state.directory() ? DATALAKE_DIRECTORY_PERMISSIONS : DATALAKE_FILE_PERMISSIONS);
        String acl = metadata.getOrDefault(DATALAKE_ACL_KEY,
                state.directory() ? DATALAKE_DEFAULT_DIRECTORY_ACL : DATALAKE_DEFAULT_FILE_ACL);
        return Response.ok()
                .header("Last-Modified", RFC1123_DATE_TIME.format(effectiveLastModified(object)))
                .header("ETag", effectiveEtag(object, state.key()))
                .header("x-ms-owner", metadata.getOrDefault(DATALAKE_OWNER_KEY, DATALAKE_OWNER))
                .header("x-ms-group", metadata.getOrDefault(DATALAKE_GROUP_KEY, DATALAKE_GROUP))
                .header("x-ms-permissions", permissions)
                .header("x-ms-acl", acl)
                .build();
    }

    private Response setDataLakeAccessControl(AzureRequest request, String filesystem, String path) {
        Response authFailure = authorizeWrite(request, filesystem, path);
        if (authFailure != null) {
            return authFailure;
        }
        return leaseService.exclusively(() -> {
            if (store.get(nsKey(request.accountName(), filesystem)).isEmpty()) {
                return new AzureErrorResponse("FilesystemNotFound", "The specified filesystem does not exist.")
                        .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
            }
            DataLakePathState state = resolveDataLakePath(request.accountName(), filesystem, path);
            if (state == null) {
                return new AzureErrorResponse("PathNotFound", "The specified path does not exist.")
                        .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
            }

            boolean root = path == null || path.isBlank() || "/".equals(path);
            // Validate against the visible ETag first. Implicit directories expose a
            // deterministic synthetic ETag; materializing a marker before this check
            // would replace it and make Hadoop getAclStatus -> setAcl If-Match fail.
            Response conditionFailure = validateDataLakeConditions(request, Optional.of(state.object()));
            if (conditionFailure != null) {
                return conditionFailure;
            }
            StoredObject current = root
                    ? state.object()
                    : materializeIfImplicitDirectory(request.accountName(), filesystem, path, state);
            if (!root) {
                Response leaseFailure = leaseService.validateDataLakeWrite(request,
                        objKey(request.accountName(), filesystem, path));
                if (leaseFailure != null) {
                    return leaseFailure;
                }
            }

            Map<String, String> metadata = new HashMap<>(current.metadata());
            ensureDataLakeDefaults(metadata, state.directory());
            putIfHeaderPresent(request, metadata, "x-ms-owner", DATALAKE_OWNER_KEY);
            putIfHeaderPresent(request, metadata, "x-ms-group", DATALAKE_GROUP_KEY);

            String permissionHeader = request.headers().getHeaderString("x-ms-permissions");
            if (permissionHeader != null && !permissionHeader.isBlank()) {
                String permissions = normalizeDataLakePermissions(permissionHeader, null, state.directory());
                String updatedAcl = applyPermissionsToAcl(metadata.get(DATALAKE_ACL_KEY), permissions);
                metadata.put(DATALAKE_ACL_KEY, updatedAcl);
                // Hadoop derives FileStatus.hasAcl from the trailing '+' in x-ms-permissions.
                // chmod/setPermission must not make an existing extended ACL disappear; the
                // group mode bits update mask:: while named ACL entries stay intact.
                metadata.put(DATALAKE_PERMISSIONS_KEY,
                        isExtendedAcl(updatedAcl) && !permissions.endsWith("+")
                                ? permissions + "+" : permissions);
            }

            String aclHeader = request.headers().getHeaderString("x-ms-acl");
            if (aclHeader != null) {
                metadata.put(DATALAKE_ACL_KEY, aclHeader);
                if (permissionHeader == null || permissionHeader.isBlank()) {
                    String derived = permissionsFromAcl(aclHeader, state.directory());
                    if (isExtendedAcl(aclHeader) && !derived.endsWith("+")) {
                        derived += "+";
                    }
                    metadata.put(DATALAKE_PERMISSIONS_KEY, derived);
                }
            }

            Instant now = Instant.now();
            String etag = UUID.randomUUID().toString();
            String storageKey = root ? nsKey(request.accountName(), filesystem)
                    : objKey(request.accountName(), filesystem, path);
            store.put(storageKey, new StoredObject(current.key(), current.data(), metadata, now, etag));
            return Response.ok()
                    .header("Last-Modified", RFC1123_DATE_TIME.format(now))
                    .header("ETag", etag)
                    .build();
        });
    }

    private Response checkDataLakeAccess(AzureRequest request, String filesystem, String path) {
        Response authFailure = authorizeRead(request, filesystem, path);
        if (authFailure != null) {
            return authFailure;
        }
        if (store.get(nsKey(request.accountName(), filesystem)).isEmpty()) {
            return new AzureErrorResponse("FilesystemNotFound", "The specified filesystem does not exist.")
                    .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
        }
        String fsAction = request.queryParams().get("fsAction");
        if (fsAction == null || fsAction.isBlank() || !fsAction.matches("[rwx-]{1,3}")) {
            return new AzureErrorResponse("InvalidQueryParameterValue",
                    "Value for one of the query parameters specified in the request URI is invalid.")
                    .toDataLakeJsonResponse(Response.Status.BAD_REQUEST.getStatusCode());
        }
        if (resolveDataLakePath(request.accountName(), filesystem, path) == null) {
            return new AzureErrorResponse("PathNotFound", "The specified path does not exist.")
                    .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
        }
        // Authentication/authorization is already handled by the emulator. Hadoop only needs
        // the HNS access-check wire contract here; a full POSIX authorization engine is outside
        // the storage emulator scope.
        return Response.ok().build();
    }

    private Response leaseDataLakePath(AzureRequest request, String filesystem, String path) {
        Response authFailure = authorizeWrite(request, filesystem, path);
        if (authFailure != null) {
            return authFailure;
        }
        return leaseService.exclusively(() -> {
            DataLakePathState state = resolveDataLakePath(request.accountName(), filesystem, path);
            if (state == null) {
                return new AzureErrorResponse("PathNotFound", "The specified path does not exist.")
                        .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
            }
            StoredObject object = materializeIfImplicitDirectory(request.accountName(), filesystem, path, state);
            return leaseService.handleDataLakeLeaseOp(request,
                    objKey(request.accountName(), filesystem, path),
                    object.etag(), RFC1123_DATE_TIME.format(object.lastModified()));
        });
    }

    private DataLakePathState resolveDataLakePath(String account, String filesystem, String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            String key = nsKey(account, filesystem);
            return store.get(key).map(object -> new DataLakePathState(key, object, true, false, true)).orElse(null);
        }
        String normalized = normalizeDataLakePath(path);
        if (normalized == null) {
            return null;
        }
        String key = objKey(account, filesystem, normalized);
        Optional<StoredObject> exact = store.get(key);
        if (exact.isPresent()) {
            boolean directory = "directory".equals(exact.get().metadata().get(DATALAKE_RESOURCE_TYPE_KEY));
            return new DataLakePathState(key, exact.get(), directory, false, false);
        }
        String prefix = key + "/";
        List<StoredObject> descendants = store.scan(candidate -> candidate.startsWith(prefix)).stream()
                .filter(object -> !isDataLakeInternalStoredObject(object))
                .toList();
        if (descendants.isEmpty()) {
            return null;
        }
        StoredObject latest = descendants.stream()
                .max(Comparator.comparing(StoredObject::lastModified))
                .orElse(NS_SENTINEL);
        Map<String, String> metadata = defaultDataLakeMetadata(normalized, true, null);
        String etag = "implicit-" + Integer.toHexString(key.hashCode());
        StoredObject synthetic = new StoredObject(normalized, new byte[0], metadata,
                effectiveLastModified(latest), etag);
        return new DataLakePathState(key, synthetic, true, true, false);
    }

    private StoredObject materializeIfImplicitDirectory(
            String account, String filesystem, String path, DataLakePathState state) {
        if (!state.implicit()) {
            return state.object();
        }
        String normalized = normalizeDataLakePath(path);
        Map<String, String> metadata = new HashMap<>(state.object().metadata());
        metadata.put("Name", normalized);
        metadata.put(DATALAKE_RESOURCE_TYPE_KEY, "directory");
        metadata.put(CREATION_TIME_KEY, state.object().lastModified().toString());
        ensureDataLakeDefaults(metadata, true);
        String etag = UUID.randomUUID().toString();
        StoredObject marker = new StoredObject(normalized, new byte[0], metadata,
                Instant.now(), etag);
        store.put(objKey(account, filesystem, normalized), marker);
        return marker;
    }

    private static Map<String, String> defaultDataLakeMetadata(
            String path, boolean directory, AzureRequest request) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("Name", path == null ? "" : path);
        metadata.put(DATALAKE_RESOURCE_TYPE_KEY, directory ? "directory" : "file");
        metadata.put(DATALAKE_OWNER_KEY, DATALAKE_OWNER);
        metadata.put(DATALAKE_GROUP_KEY, DATALAKE_GROUP);
        String requestedPermissions = request == null ? null
                : request.headers().getHeaderString("x-ms-permissions");
        String umask = request == null ? null : request.headers().getHeaderString("x-ms-umask");
        String permissions = normalizeDataLakePermissions(requestedPermissions, umask, directory);
        metadata.put(DATALAKE_PERMISSIONS_KEY, permissions);
        metadata.put(DATALAKE_ACL_KEY, aclFromPermissions(permissions));
        return metadata;
    }

    private static void ensureDataLakeDefaults(Map<String, String> metadata, boolean directory) {
        metadata.putIfAbsent(DATALAKE_RESOURCE_TYPE_KEY, directory ? "directory" : "file");
        metadata.putIfAbsent(DATALAKE_OWNER_KEY, DATALAKE_OWNER);
        metadata.putIfAbsent(DATALAKE_GROUP_KEY, DATALAKE_GROUP);
        String permissions = metadata.getOrDefault(DATALAKE_PERMISSIONS_KEY,
                directory ? DATALAKE_DIRECTORY_PERMISSIONS : DATALAKE_FILE_PERMISSIONS);
        metadata.put(DATALAKE_PERMISSIONS_KEY, permissions);
        metadata.putIfAbsent(DATALAKE_ACL_KEY, aclFromPermissions(permissions));
    }

    private static String normalizeDataLakePermissions(String permissions, String umask, boolean directory) {
        if (permissions == null || permissions.isBlank()) {
            return directory ? DATALAKE_DIRECTORY_PERMISSIONS : DATALAKE_FILE_PERMISSIONS;
        }
        String value = permissions.trim();
        if (value.matches("[0-7]{3,4}")) {
            int mode = Integer.parseInt(value, 8);
            if (umask != null && umask.trim().matches("[0-7]{3,4}")) {
                mode &= ~Integer.parseInt(umask.trim(), 8);
            }
            return symbolicPermissions(mode);
        }
        return value;
    }

    private static String symbolicPermissions(int mode) {
        StringBuilder result = new StringBuilder(9);
        int[] bits = {0400, 0200, 0100, 0040, 0020, 0010, 0004, 0002, 0001};
        char[] chars = {'r','w','x','r','w','x','r','w','x'};
        for (int i = 0; i < bits.length; i++) {
            result.append((mode & bits[i]) != 0 ? chars[i] : '-');
        }
        if ((mode & 01000) != 0) {
            result.setCharAt(8, result.charAt(8) == 'x' ? 't' : 'T');
        }
        return result.toString();
    }

    private static String aclFromPermissions(String permissionString) {
        String permissions = permissionString == null ? "---------" : permissionString.replace("+", "");
        if (permissions.length() < 9) {
            permissions = "---------";
        }
        return "user::" + permissions.substring(0, 3)
                + ",group::" + permissions.substring(3, 6)
                + ",other::" + permissions.substring(6, 9);
    }

    private static String permissionsFromAcl(String acl, boolean directory) {
        if (acl == null || acl.isBlank()) {
            return directory ? DATALAKE_DIRECTORY_PERMISSIONS : DATALAKE_FILE_PERMISSIONS;
        }
        String user = null, group = null, mask = null, other = null;
        for (String entry : acl.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.startsWith("user::")) user = trimmed.substring("user::".length());
            if (trimmed.startsWith("group::")) group = trimmed.substring("group::".length());
            if (trimmed.startsWith("mask::")) mask = trimmed.substring("mask::".length());
            if (trimmed.startsWith("other::")) other = trimmed.substring("other::".length());
        }
        if (user == null || group == null || other == null) {
            return directory ? DATALAKE_DIRECTORY_PERMISSIONS : DATALAKE_FILE_PERMISSIONS;
        }
        return user + (mask != null ? mask : group) + other;
    }

    /**
     * chmod/setPermission changes the POSIX mode without deleting named ACL
     * entries. For an extended ACL the mode's group bits represent the ACL
     * mask; for a basic ACL they represent group:: directly.
     */
    private static String applyPermissionsToAcl(String existingAcl, String permissionString) {
        String permissions = permissionString == null ? "---------" : permissionString.replace("+", "");
        if (permissions.length() < 9 || existingAcl == null || existingAcl.isBlank()) {
            return aclFromPermissions(permissionString);
        }
        boolean extended = isExtendedAcl(existingAcl);
        List<String> entries = new ArrayList<>();
        boolean userSeen = false, groupSeen = false, maskSeen = false, otherSeen = false;
        for (String entry : existingAcl.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.startsWith("user::")) {
                entries.add("user::" + permissions.substring(0, 3));
                userSeen = true;
            } else if (trimmed.startsWith("group::") && !extended) {
                entries.add("group::" + permissions.substring(3, 6));
                groupSeen = true;
            } else if (trimmed.startsWith("mask::") && extended) {
                entries.add("mask::" + permissions.substring(3, 6));
                maskSeen = true;
            } else if (trimmed.startsWith("other::")) {
                entries.add("other::" + permissions.substring(6, 9));
                otherSeen = true;
            } else {
                entries.add(trimmed);
                if (trimmed.startsWith("group::")) groupSeen = true;
            }
        }
        if (!userSeen) entries.add("user::" + permissions.substring(0, 3));
        if (!groupSeen) entries.add("group::" + permissions.substring(3, 6));
        if (extended && !maskSeen) entries.add("mask::" + permissions.substring(3, 6));
        if (!otherSeen) entries.add("other::" + permissions.substring(6, 9));
        return String.join(",", entries);
    }

    private static boolean isExtendedAcl(String acl) {
        if (acl == null || acl.isBlank()) {
            return false;
        }
        for (String entry : acl.split(",")) {
            String value = entry.trim();
            if (value.startsWith("default:") || value.startsWith("mask::")) {
                return true;
            }
            if ((value.startsWith("user:") && !value.startsWith("user::"))
                    || (value.startsWith("group:") && !value.startsWith("group::"))) {
                return true;
            }
        }
        return false;
    }

    private static Response validateDataLakeConditions(AzureRequest request, Optional<StoredObject> object) {
        String ifMatch = request.headers().getHeaderString(HttpHeaders.IF_MATCH);
        if (ifMatch != null && object.map(StoredObject::etag)
                .filter(etag -> etagMatches(ifMatch, etag)).isEmpty()) {
            return new AzureErrorResponse("ConditionNotMet",
                    "The condition specified using HTTP conditional header(s) is not met.")
                    .toDataLakeJsonResponse(Response.Status.PRECONDITION_FAILED.getStatusCode());
        }
        String ifNoneMatch = request.headers().getHeaderString(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null && object.map(StoredObject::etag)
                .filter(etag -> etagMatches(ifNoneMatch, etag)).isPresent()) {
            return new AzureErrorResponse("ConditionNotMet",
                    "The condition specified using HTTP conditional header(s) is not met.")
                    .toDataLakeJsonResponse(Response.Status.PRECONDITION_FAILED.getStatusCode());
        }
        return null;
    }

    private static void putIfHeaderPresent(
            AzureRequest request, Map<String, String> metadata, String header, String metadataKey) {
        String value = request.headers().getHeaderString(header);
        if (value != null && !value.isBlank()) {
            metadata.put(metadataKey, value);
        }
    }

    private static void copyIfPresent(Map<String, String> source, Map<String, String> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private static String headerOrEmpty(AzureRequest request, String name) {
        String value = request.headers().getHeaderString(name);
        return value == null ? "" : value;
    }

    private static Instant effectiveLastModified(StoredObject object) {
        return object.lastModified() == null || object.lastModified().equals(Instant.EPOCH)
                ? Instant.now() : object.lastModified();
    }

    private static String effectiveEtag(StoredObject object, String key) {
        return object.etag() == null || object.etag().isBlank()
                ? "implicit-" + Integer.toHexString(key.hashCode()) : object.etag();
    }

    private record DataLakePathState(
            String key, StoredObject object, boolean directory, boolean implicit, boolean root) {}

    /**
     * ADLS Gen2 Path Rename (Path - Create with x-ms-rename-source).
     *
     * <p>Hadoop ABFS 3.3.4 sends a PUT to the destination path with a URL-encoded
     * {@code x-ms-rename-source: /{filesystem}/{sourcePath}} header and
     * {@code If-None-Match: *}. Spark's FileOutputCommitter uses directory renames
     * extensively, and its source task directory may be implicit (only descendant
     * objects exist). The move therefore operates on the complete storage-key prefix
     * and applies all puts/deletes in one storage batch.
     */
    private Response renameDataLakePath(
            AzureRequest request,
            String destinationFilesystem,
            String destinationPath,
            String renameSourceHeader
    ) {
        String contentLengthHeader = request.headers().getHeaderString(HttpHeaders.CONTENT_LENGTH);
        if (contentLengthHeader != null) {
            try {
                if (Long.parseLong(contentLengthHeader) != 0L) {
                    return new AzureErrorResponse("ContentLengthMustBeZero",
                            "The Content-Length request header must be zero.")
                            .toDataLakeJsonResponse(Response.Status.BAD_REQUEST.getStatusCode());
                }
            } catch (NumberFormatException e) {
                return new AzureErrorResponse("InvalidHeaderValue",
                        "The value for one of the HTTP headers is not in the correct format.")
                        .toDataLakeJsonResponse(Response.Status.BAD_REQUEST.getStatusCode());
            }
        }

        RenameSource source = parseDataLakeRenameSource(renameSourceHeader);
        if (source == null) {
            return new AzureErrorResponse("InvalidSourceUri", "The source URI is invalid.")
                    .toDataLakeJsonResponse(Response.Status.BAD_REQUEST.getStatusCode());
        }

        String renameMode = request.queryParams().get("mode");
        if (renameMode != null && !"posix".equals(renameMode) && !"legacy".equals(renameMode)) {
            return new AzureErrorResponse("InvalidQueryParameterValue",
                    "Value for one of the query parameters specified in the request URI is invalid.")
                    .toDataLakeJsonResponse(Response.Status.BAD_REQUEST.getStatusCode());
        }

        String normalizedDestination = normalizeDataLakePath(destinationPath);
        if (normalizedDestination == null) {
            return new AzureErrorResponse("InvalidDestinationPath",
                    "The specified path, or an element of the path, exists and its resource type is invalid for this operation.")
                    .toDataLakeJsonResponse(Response.Status.CONFLICT.getStatusCode());
        }

        if (source.filesystem().equals(destinationFilesystem)
                && (source.path().equals(normalizedDestination)
                || normalizedDestination.startsWith(source.path() + "/"))) {
            return new AzureErrorResponse("InvalidRenameSourcePath",
                    "The source directory cannot be the same as the destination directory, nor can the destination be a subdirectory of the source directory.")
                    .toDataLakeJsonResponse(Response.Status.CONFLICT.getStatusCode());
        }

        return leaseService.exclusively(() -> renameDataLakePathLocked(
                request, source, destinationFilesystem, normalizedDestination));
    }

    private Response renameDataLakePathLocked(
            AzureRequest request,
            RenameSource source,
            String destinationFilesystem,
            String destinationPath
    ) {
        if (store.get(nsKey(request.accountName(), destinationFilesystem)).isEmpty()
                || store.get(nsKey(request.accountName(), source.filesystem())).isEmpty()) {
            return new AzureErrorResponse("FilesystemNotFound", "The specified filesystem does not exist.")
                    .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
        }

        String destinationParent = parentDataLakePath(destinationPath);
        if (destinationParent != null) {
            DataLakePathOperations.DeletePlan parent = dataLakePathOperations.planDelete(
                    request.accountName(), destinationFilesystem, destinationParent);
            if (!parent.exists()) {
                return new AzureErrorResponse("RenameDestinationParentPathNotFound",
                        "The parent directory of the destination path does not exist.")
                        .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
            }
            if (!parent.directory()) {
                return new AzureErrorResponse("InvalidDestinationPath",
                        "The specified path, or an element of the path, exists and its resource type is invalid for this operation.")
                        .toDataLakeJsonResponse(Response.Status.CONFLICT.getStatusCode());
            }
        }

        DataLakePathOperations.RenamePlan plan = dataLakePathOperations.planRename(
                request.accountName(),
                source.filesystem(),
                source.path(),
                destinationFilesystem,
                destinationPath);

        if (!plan.exists()) {
            return new AzureErrorResponse("SourcePathNotFound",
                    "The source path for a rename operation does not exist.")
                    .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
        }

        Response sourceConditionFailure = validateDataLakeRenameSourceConditions(request, plan);
        if (sourceConditionFailure != null) {
            return sourceConditionFailure;
        }

        DataLakePathOperations.DeletePlan destination = dataLakePathOperations.planDelete(
                request.accountName(), destinationFilesystem, destinationPath);

        Response conditionFailure = validateDataLakeRenameDestinationConditions(request, destination);
        if (conditionFailure != null) {
            return conditionFailure;
        }

        if (destination.exists()) {
            if (plan.directory() != destination.directory()) {
                return new AzureErrorResponse("InvalidSourceOrDestinationResourceType",
                        "The source and destination resource type must be identical.")
                        .toDataLakeJsonResponse(Response.Status.CONFLICT.getStatusCode());
            }
            if (destination.nonEmptyDirectory()) {
                return new AzureErrorResponse("DirectoryNotEmpty",
                        "The destination directory is not empty.")
                        .toDataLakeJsonResponse(Response.Status.CONFLICT.getStatusCode());
            }
        }

        Map<String, StoredObject> puts = new LinkedHashMap<>();
        Set<String> deletes = new LinkedHashSet<>();
        Instant renamedAt = Instant.now();
        String destinationObjectPrefix = request.accountName() + "/" + destinationFilesystem + "/";

        for (String sourceKey : plan.sourceKeys()) {
            StoredObject sourceObject = store.get(sourceKey).orElse(null);
            if (sourceObject == null) {
                return new AzureErrorResponse("SourcePathNotFound",
                        "The source path for a rename operation does not exist.")
                        .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
            }

            String destinationKey = plan.destinationKeyFor(sourceKey);
            String movedPath = destinationKey.substring(destinationObjectPrefix.length());
            Map<String, String> metadata = new HashMap<>(sourceObject.metadata());
            metadata.put("Name", movedPath);
            String etag = UUID.randomUUID().toString();

            puts.put(destinationKey,
                    new StoredObject(movedPath, sourceObject.data(), metadata, renamedAt, etag));
            deletes.add(sourceKey);
        }

        // A non-conditional rename overwrites an existing file or empty directory.
        if (destination.exists()) {
            if (destination.exactKey() != null && destination.exactObject() != null) {
                deletes.add(destination.exactKey());
            }
            deletes.addAll(destination.descendantKeys());
            String destinationAppendBase = DATALAKE_APPEND_PREFIX + request.accountName() + "/"
                    + destinationFilesystem + "/" + destinationPath;
            store.keys().stream()
                    .filter(key -> key.startsWith(destinationAppendBase + ":pos:")
                            || key.startsWith(destinationAppendBase + "/"))
                    .forEach(deletes::add);
        }

        // Preserve any staged ADLS append data if a file/directory is renamed while
        // uncommitted chunks exist. Spark normally flushes before rename, but moving the
        // staging namespace keeps the emulator's path state internally coherent.
        String sourceAppendBase = DATALAKE_APPEND_PREFIX + request.accountName() + "/"
                + source.filesystem() + "/" + source.path();
        String destinationAppendBase = DATALAKE_APPEND_PREFIX + request.accountName() + "/"
                + destinationFilesystem + "/" + destinationPath;
        for (String appendKey : store.keys().stream()
                .filter(key -> key.startsWith(sourceAppendBase + ":pos:") || key.startsWith(sourceAppendBase + "/"))
                .sorted()
                .toList()) {
            StoredObject chunk = store.get(appendKey).orElse(null);
            if (chunk == null) {
                continue;
            }
            String movedAppendKey = destinationAppendBase + appendKey.substring(sourceAppendBase.length());
            puts.put(movedAppendKey, chunk);
            deletes.add(appendKey);
        }

        store.applyBatch(puts, deletes);

        StoredObject destinationRoot = puts.get(plan.destinationKey());
        String etag = destinationRoot != null
                ? destinationRoot.etag()
                : "implicit-" + Integer.toHexString(plan.destinationKey().hashCode());

        return Response.status(Response.Status.CREATED)
                .header("Last-Modified", RFC1123_DATE_TIME.format(renamedAt))
                .header("ETag", etag)
                .header("x-ms-request-server-encrypted", "true")
                .header(HttpHeaders.CONTENT_LENGTH, 0)
                .build();
    }

    private Response validateDataLakeRenameSourceConditions(
            AzureRequest request,
            DataLakePathOperations.RenamePlan source
    ) {
        StoredObject exact = source.exactObject();
        String ifNoneMatch = request.headers().getHeaderString("x-ms-source-if-none-match");
        if (ifNoneMatch != null) {
            if ("*".equals(ifNoneMatch.trim()) && source.exists()) {
                return new AzureErrorResponse("SourceConditionNotMet",
                        "The source condition specified using HTTP conditional header(s) is not met.")
                        .toDataLakeJsonResponse(Response.Status.PRECONDITION_FAILED.getStatusCode());
            }
            if (exact != null && etagMatches(ifNoneMatch, exact.etag())) {
                return new AzureErrorResponse("SourceConditionNotMet",
                        "The source condition specified using HTTP conditional header(s) is not met.")
                        .toDataLakeJsonResponse(Response.Status.PRECONDITION_FAILED.getStatusCode());
            }
        }

        String ifMatch = request.headers().getHeaderString("x-ms-source-if-match");
        if (ifMatch != null && (exact == null || !etagMatches(ifMatch, exact.etag()))) {
            return new AzureErrorResponse("SourceConditionNotMet",
                    "The source condition specified using HTTP conditional header(s) is not met.")
                    .toDataLakeJsonResponse(Response.Status.PRECONDITION_FAILED.getStatusCode());
        }
        return null;
    }

    private Response validateDataLakeRenameDestinationConditions(
            AzureRequest request,
            DataLakePathOperations.DeletePlan destination
    ) {
        String ifNoneMatch = request.headers().getHeaderString(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null) {
            if ("*".equals(ifNoneMatch.trim()) && destination.exists()) {
                return new AzureErrorResponse("ConditionNotMet",
                        "The condition specified using HTTP conditional header(s) is not met.")
                        .toDataLakeJsonResponse(Response.Status.PRECONDITION_FAILED.getStatusCode());
            }
            if (destination.exactObject() != null
                    && etagMatches(ifNoneMatch, destination.exactObject().etag())) {
                return new AzureErrorResponse("ConditionNotMet",
                        "The condition specified using HTTP conditional header(s) is not met.")
                        .toDataLakeJsonResponse(Response.Status.PRECONDITION_FAILED.getStatusCode());
            }
        }

        String ifMatch = request.headers().getHeaderString(HttpHeaders.IF_MATCH);
        if (ifMatch != null && (destination.exactObject() == null
                || !etagMatches(ifMatch, destination.exactObject().etag()))) {
            return new AzureErrorResponse("ConditionNotMet",
                    "The condition specified using HTTP conditional header(s) is not met.")
                    .toDataLakeJsonResponse(Response.Status.PRECONDITION_FAILED.getStatusCode());
        }
        return null;
    }

    private static RenameSource parseDataLakeRenameSource(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            int queryStart = header.indexOf('?');
            String encodedPath = queryStart >= 0 ? header.substring(0, queryStart) : header;
            String decoded = URLDecoder.decode(encodedPath, StandardCharsets.UTF_8);
            while (decoded.startsWith("/")) {
                decoded = decoded.substring(1);
            }
            String[] parts = decoded.split("/", 2);
            if (parts.length != 2 || parts[0].isBlank()) {
                return null;
            }
            String normalizedPath = normalizeDataLakePath(parts[1]);
            return normalizedPath == null ? null : new RenameSource(parts[0], normalizedPath);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalizeDataLakePath(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/") && !normalized.isEmpty()) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private static String parentDataLakePath(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? null : path.substring(0, slash);
    }

    private record RenameSource(String filesystem, String path) {}

    /**
     * ADLS Gen2 Get Path Properties / Get Status.
     *
     * Hadoop ABFS uses HEAD ?action=getStatus for FileSystem.exists()/getFileStatus().
     * A directory may be implicit (no marker object) as long as descendants exist, so this
     * cannot be implemented as a plain Blob HEAD lookup.
     */
    private Response getDataLakePathStatus(AzureRequest request, String filesystem, String path) {
        Response authFailure = authorizeRead(request, filesystem, path);
        if (authFailure != null) {
            return authFailure;
        }
        if (store.get(nsKey(request.accountName(), filesystem)).isEmpty()) {
            return new AzureErrorResponse("FilesystemNotFound", "The specified filesystem does not exist.")
                    .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
        }

        String key = objKey(request.accountName(), filesystem, path);
        Optional<StoredObject> exact = store.get(key);
        List<StoredObject> descendants = List.of();
        boolean directory;
        StoredObject source;

        if (exact.isPresent()) {
            source = exact.get();
            directory = "directory".equals(source.metadata().get("DataLakeResourceType"));
        } else {
            String prefix = key + "/";
            descendants = store.scan(candidate -> candidate.startsWith(prefix));
            descendants = descendants.stream()
                    .filter(object -> !isDataLakeInternalStoredObject(object))
                    .toList();
            if (descendants.isEmpty()) {
                return new AzureErrorResponse("PathNotFound", "The specified path does not exist.")
                        .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
            }
            directory = true;
            source = descendants.stream()
                    .max(Comparator.comparing(StoredObject::lastModified))
                    .orElse(NS_SENTINEL);
        }

        long contentLength = directory ? 0 : source.data().length;
        String etag = exact.map(StoredObject::etag)
                .orElseGet(() -> "implicit-" + Integer.toHexString(key.hashCode()));
        Instant lastModified = source.lastModified().equals(Instant.EPOCH) ? Instant.now() : source.lastModified();

        boolean implicitDirectory = directory && exact.isEmpty();
        Map<String, String> metadata = implicitDirectory ? Map.of() : source.metadata();
        Response.ResponseBuilder builder = Response.ok()
                .header("Last-Modified", RFC1123_DATE_TIME.format(lastModified))
                .header("ETag", etag)
                .header(HttpHeaders.CONTENT_LENGTH, contentLength)
                .header("x-ms-resource-type", directory ? "directory" : "file")
                .header("x-ms-owner", metadata.getOrDefault(DATALAKE_OWNER_KEY, DATALAKE_OWNER))
                .header("x-ms-group", metadata.getOrDefault(DATALAKE_GROUP_KEY, DATALAKE_GROUP))
                .header("x-ms-permissions", metadata.getOrDefault(DATALAKE_PERMISSIONS_KEY, directory
                        ? DATALAKE_DIRECTORY_PERMISSIONS
                        : DATALAKE_FILE_PERMISSIONS))
                .header("x-ms-request-server-encrypted", "true");
        if (request.queryParams().get("action") == null) {
            builder.header("x-ms-properties", metadata.getOrDefault(DATALAKE_PROPERTIES_KEY, ""));
        }
        leaseService.addLeaseHeaders(builder, key);

        return builder.build();
    }

    /**
     * ADLS Gen2 Append Data. Hadoop 3.3.x sends this as HTTP PUT with
     * X-Http-Method-Override: PATCH and action=append.
     *
     * Chunks are staged under an internal key so asynchronous/out-of-order ABFS uploads do not
     * become visible until Flush commits a contiguous byte range.
     */
    private Response appendDataLakePath(AzureRequest request, String filesystem, String path) {
        Response authFailure = authorizeWrite(request, filesystem, path);
        if (authFailure != null) {
            return authFailure;
        }
        if (store.get(nsKey(request.accountName(), filesystem)).isEmpty()) {
            return new AzureErrorResponse("FilesystemNotFound", "The specified filesystem does not exist.")
                    .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
        }

        Long position = parseNonNegativeLong(request.queryParams().get("position"));
        if (position == null) {
            return new AzureErrorResponse("InvalidQueryParameterValue",
                    "Value for one of the query parameters specified in the request URI is invalid.")
                    .toDataLakeJsonResponse(Response.Status.BAD_REQUEST.getStatusCode());
        }

        try {
            byte[] data = request.bodyStream().readAllBytes();
            return leaseService.exclusively(() -> {
                String key = objKey(request.accountName(), filesystem, path);
                Optional<StoredObject> existing = store.get(key);
                if (existing.isEmpty()) {
                    return new AzureErrorResponse("PathNotFound", "The specified path does not exist.")
                            .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
                }
                if ("directory".equals(existing.get().metadata().get("DataLakeResourceType"))) {
                    return new AzureErrorResponse("InvalidSourceOrDestinationResourceType",
                            "The source and destination resource type must be identical.")
                            .toDataLakeJsonResponse(Response.Status.CONFLICT.getStatusCode());
                }

                Response conditionFailure = validateBlobConditions(request, existing);
                if (conditionFailure != null) {
                    return conditionFailure;
                }
                Response leaseFailure = leaseService.validateDataLakeWrite(request, key);
                if (leaseFailure != null) {
                    return leaseFailure;
                }

                if ("AppendBlob".equalsIgnoreCase(existing.get().metadata().get("BlobType"))) {
                    if (position != existing.get().data().length) {
                        return new AzureErrorResponse("InvalidQueryParameterValue",
                                "The append position does not match the current length of the append blob.")
                                .toDataLakeJsonResponse(Response.Status.BAD_REQUEST.getStatusCode());
                    }
                    byte[] committed = Arrays.copyOf(existing.get().data(), existing.get().data().length + data.length);
                    System.arraycopy(data, 0, committed, existing.get().data().length, data.length);
                    Map<String, String> metadata = new HashMap<>(existing.get().metadata());
                    metadata.put(DATALAKE_RESOURCE_TYPE_KEY, "file");
                    metadata.put("Name", path);
                    String etag = UUID.randomUUID().toString();
                    Instant now = Instant.now();
                    store.put(key, new StoredObject(path, committed, metadata, now, etag));
                    boolean flush = Boolean.parseBoolean(request.queryParams().getOrDefault("flush", "false"));
                    return Response.status(flush ? Response.Status.OK : Response.Status.ACCEPTED)
                            .header("Last-Modified", RFC1123_DATE_TIME.format(now))
                            .header("ETag", etag)
                            .header("Content-Length", 0)
                            .build();
                }

                String stageKey = dataLakeAppendChunkKey(request.accountName(), filesystem, path, position);
                Map<String, String> metadata = Map.of(
                        "DataLakeAppendPosition", Long.toString(position),
                        "Name", path);
                store.put(stageKey, new StoredObject(path, data, metadata, Instant.now(), UUID.randomUUID().toString()));

                if (Boolean.parseBoolean(request.queryParams().getOrDefault("flush", "false"))) {
                    return flushDataLakePathLocked(request, filesystem, path, position + data.length);
                }

                return Response.status(Response.Status.ACCEPTED)
                        .header("Content-Length", 0)
                        .build();
            });
        } catch (IOException e) {
            return Response.serverError().build();
        }
    }

    /** ADLS Gen2 Flush Data (HTTP PUT + X-Http-Method-Override: PATCH, action=flush). */
    private Response flushDataLakePath(AzureRequest request, String filesystem, String path) {
        Response authFailure = authorizeWrite(request, filesystem, path);
        if (authFailure != null) {
            return authFailure;
        }
        if (store.get(nsKey(request.accountName(), filesystem)).isEmpty()) {
            return new AzureErrorResponse("FilesystemNotFound", "The specified filesystem does not exist.")
                    .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
        }

        Long position = parseNonNegativeLong(request.queryParams().get("position"));
        if (position == null) {
            return new AzureErrorResponse("InvalidQueryParameterValue",
                    "Value for one of the query parameters specified in the request URI is invalid.")
                    .toDataLakeJsonResponse(Response.Status.BAD_REQUEST.getStatusCode());
        }

        return leaseService.exclusively(() -> flushDataLakePathLocked(request, filesystem, path, position));
    }

    private Response flushDataLakePathLocked(AzureRequest request, String filesystem, String path, long position) {
        String key = objKey(request.accountName(), filesystem, path);
        Optional<StoredObject> existing = store.get(key);
        if (existing.isEmpty()) {
            return new AzureErrorResponse("PathNotFound", "The specified path does not exist.")
                    .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
        }
        StoredObject current = existing.get();
        if ("directory".equals(current.metadata().get("DataLakeResourceType"))) {
            return new AzureErrorResponse("InvalidFlushOperation",
                    "The specified resource cannot be flushed as a file.")
                    .toDataLakeJsonResponse(Response.Status.CONFLICT.getStatusCode());
        }

        Response conditionFailure = validateBlobConditions(request, existing);
        if (conditionFailure != null) {
            return conditionFailure;
        }
        Response leaseFailure = leaseService.validateDataLakeWrite(request, key);
        if (leaseFailure != null) {
            return leaseFailure;
        }

        if ("AppendBlob".equalsIgnoreCase(current.metadata().get("BlobType"))) {
            if (position != current.data().length) {
                return new AzureErrorResponse("InvalidFlushPosition",
                        "The position query parameter value must equal the current append blob length.")
                        .toDataLakeJsonResponse(Response.Status.BAD_REQUEST.getStatusCode());
            }
            return Response.ok()
                    .header("Last-Modified", RFC1123_DATE_TIME.format(current.lastModified()))
                    .header("ETag", current.etag())
                    .header("x-ms-request-server-encrypted", "true")
                    .header("Content-Length", 0)
                    .build();
        }

        String stagePrefix = dataLakeAppendChunkPrefix(request.accountName(), filesystem, path);
        List<StoredObject> chunks = store.scan(candidate -> candidate.startsWith(stagePrefix)).stream()
                .sorted(Comparator.comparingLong(BlobServiceHandler::dataLakeAppendPosition))
                .toList();

        long committedLength = current.data().length;
        long coveredThrough = Math.min(committedLength, position);
        for (StoredObject chunk : chunks) {
            long start = dataLakeAppendPosition(chunk);
            long end = start + chunk.data().length;
            if (start > coveredThrough && start < position) {
                return new AzureErrorResponse("InvalidFlushPosition",
                        "The uploaded data is not contiguous or the position query parameter value is not equal "
                                + "to the length of the file after appending the uploaded data.")
                        .toDataLakeJsonResponse(Response.Status.BAD_REQUEST.getStatusCode());
            }
            if (start <= coveredThrough) {
                coveredThrough = Math.max(coveredThrough, Math.min(end, position));
            }
        }
        if (coveredThrough < position) {
            return new AzureErrorResponse("InvalidFlushPosition",
                    "The uploaded data is not contiguous or the position query parameter value is not equal "
                            + "to the length of the file after appending the uploaded data.")
                    .toDataLakeJsonResponse(Response.Status.BAD_REQUEST.getStatusCode());
        }

        if (position > Integer.MAX_VALUE) {
            return new AzureErrorResponse("OutOfRangeQueryParameterValue",
                    "One of the query parameters specified in the request URI is outside the permissible range.")
                    .toDataLakeJsonResponse(Response.Status.BAD_REQUEST.getStatusCode());
        }

        byte[] committed = Arrays.copyOf(current.data(), Math.toIntExact(position));
        for (StoredObject chunk : chunks) {
            long start = dataLakeAppendPosition(chunk);
            if (start >= position) {
                continue;
            }
            int copyLength = Math.toIntExact(Math.min((long) chunk.data().length, position - start));
            System.arraycopy(chunk.data(), 0, committed, Math.toIntExact(start), copyLength);
        }

        Map<String, String> metadata = new HashMap<>(current.metadata());
        metadata.put("DataLakeResourceType", "file");
        metadata.put("Name", path);
        String etag = UUID.randomUUID().toString();
        store.put(key, new StoredObject(path, committed, metadata, Instant.now(), etag));

        boolean retainUncommitted = Boolean.parseBoolean(
                request.queryParams().getOrDefault("retainUncommittedData", "false"));
        if (!retainUncommitted) {
            store.keys().stream()
                    .filter(candidate -> candidate.startsWith(stagePrefix))
                    .toList()
                    .forEach(store::delete);
        } else {
            store.scan(candidate -> candidate.startsWith(stagePrefix)).stream()
                    .filter(chunk -> dataLakeAppendPosition(chunk) + chunk.data().length <= position)
                    .map(chunk -> dataLakeAppendChunkKey(request.accountName(), filesystem, path,
                            dataLakeAppendPosition(chunk)))
                    .toList()
                    .forEach(store::delete);
        }

        return Response.ok()
                .header("Last-Modified", RFC1123_DATE_TIME.format(Instant.now()))
                .header("ETag", etag)
                .header("x-ms-request-server-encrypted", "true")
                .header("Content-Length", 0)
                .build();
    }

    private static Long parseNonNegativeLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long dataLakeAppendPosition(StoredObject chunk) {
        return Long.parseLong(chunk.metadata().get("DataLakeAppendPosition"));
    }

    private static String dataLakeAppendChunkPrefix(String account, String filesystem, String path) {
        return DATALAKE_APPEND_PREFIX + account + "/" + filesystem + "/" + path + ":pos:";
    }

    private static String dataLakeAppendChunkKey(
            String account, String filesystem, String path, long position) {
        return dataLakeAppendChunkPrefix(account, filesystem, path) + String.format(Locale.ROOT, "%020d", position);
    }

    private void clearDataLakeAppendChunks(String account, String filesystem, String path) {
        String prefix = dataLakeAppendChunkPrefix(account, filesystem, path);
        store.keys().stream()
                .filter(candidate -> candidate.startsWith(prefix))
                .toList()
                .forEach(store::delete);
    }

    private void clearDataLakeAppendChunksUnderPath(String account, String filesystem, String path) {
        String base = DATALAKE_APPEND_PREFIX + account + "/" + filesystem + "/" + path;
        store.keys().stream()
                .filter(candidate -> candidate.startsWith(base + ":pos:") || candidate.startsWith(base + "/"))
                .toList()
                .forEach(store::delete);
    }

    private static boolean isDataLakeInternalStoredObject(StoredObject object) {
        return object.metadata().containsKey("DataLakeAppendPosition");
    }

    private Response getBlob(AzureRequest request, String containerName, String blobName, boolean headOnly) {
        Response authFailure = authorizeRead(request, containerName, blobName);
        if (authFailure != null) {
            return authFailure;
        }
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
                    return Response.fromResponse(new AzureErrorResponse("InvalidRange",
                            "The range specified is invalid for the current size of the resource.")
                            .toXmlResponse(416))
                            .header("Content-Range", "bytes */" + totalSize)
                            .build();
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
                .header(HttpHeaders.CONTENT_TYPE, usableContentType(so.metadata().get("Content-Type")))
                .header(HttpHeaders.CONTENT_LENGTH, contentLength)
                .header("x-ms-blob-content-length", totalSize)
                .header("Accept-Ranges", "bytes")
                // Get Blob always reports these. Strict SDK clients (e.g. the Azure SDK for C++)
                // read x-ms-creation-time and x-ms-server-encrypted unconditionally and throw when
                // they are absent.
                .header("x-ms-creation-time", RFC1123_DATE_TIME.format(creationTime(so)))
                .header("x-ms-server-encrypted", "true");
        for (String header : BLOB_HTTP_PROPERTY_HEADERS.values()) {
            String value = so.metadata().get(header);
            if (value != null && !(isRangeRequest && "Content-MD5".equals(header))) {
                rb.header(header, value);
            }
        }
        leaseService.addLeaseHeaders(rb, objKey(request.accountName(), containerName, blobName));
        if (isRangeRequest) {
            rb.header("Content-Range", String.format("bytes %d-%d/%d", rangeStart, rangeEnd, totalSize));
        }
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

    /** GET /{filesystem}/{path} through the DFS endpoint (ADLS Path - Read). */
    private Response readDataLakePath(AzureRequest request, String filesystem, String path) {
        Response authFailure = authorizeRead(request, filesystem, path);
        if (authFailure != null) {
            return authFailure;
        }
        if (store.get(nsKey(request.accountName(), filesystem)).isEmpty()) {
            return new AzureErrorResponse("FilesystemNotFound", "The specified filesystem does not exist.")
                    .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
        }
        if (store.get(objKey(request.accountName(), filesystem, path)).isEmpty()) {
            return new AzureErrorResponse("PathNotFound", "The specified path does not exist.")
                    .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
        }
        // Reuse Blob read/range/condition handling once DFS namespace semantics have been resolved.
        return getBlob(request, filesystem, path, false);
    }

    private Response deleteDataLakePath(AzureRequest request, String filesystem, String path) {
        Response authFailure = authorizeDelete(request, filesystem, path);
        if (authFailure != null) {
            return authFailure;
        }
        if (store.get(nsKey(request.accountName(), filesystem)).isEmpty()) {
            return new AzureErrorResponse("FilesystemNotFound", "The specified filesystem does not exist.")
                    .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
        }

        return leaseService.exclusively(() -> {
            DataLakePathOperations.DeletePlan plan =
                    dataLakePathOperations.planDelete(request.accountName(), filesystem, path);

            if (!plan.exists()) {
                return new AzureErrorResponse("PathNotFound", "The specified path does not exist.")
                        .toDataLakeJsonResponse(Response.Status.NOT_FOUND.getStatusCode());
            }

            boolean recursive = Boolean.parseBoolean(request.queryParams().getOrDefault("recursive", "false"));
            if (plan.nonEmptyDirectory() && !recursive) {
                return new AzureErrorResponse("DirectoryNotEmpty",
                        "The recursive query parameter value must be true to delete a non-empty directory.")
                        .toDataLakeJsonResponse(Response.Status.CONFLICT.getStatusCode());
            }

            if (plan.exactObject() != null) {
                Response conditionFailure = validateBlobConditions(request, Optional.of(plan.exactObject()));
                if (conditionFailure != null) {
                    return conditionFailure;
                }
                Response leaseFailure = leaseService.validateWrite(request, plan.exactKey());
                if (leaseFailure != null) {
                    return leaseFailure;
                }
            }

            for (String descendantKey : plan.descendantKeys()) {
                store.delete(descendantKey);
                leaseService.onBlobDeleted(descendantKey);
            }
            if (plan.exactObject() != null) {
                store.delete(plan.exactKey());
                leaseService.onBlobDeleted(plan.exactKey());
            }
            clearDataLakeAppendChunksUnderPath(request.accountName(), filesystem, path);

            return Response.status(Response.Status.ACCEPTED).build();
        });
    }

    private Response deleteBlob(AzureRequest request, String containerName, String blobName) {
        Response authFailure = authorizeDelete(request, containerName, blobName);
        if (authFailure != null) {
            return authFailure;
        }
        return leaseService.exclusively(() -> {
            Optional<StoredObject> object = store.get(objKey(request.accountName(), containerName, blobName));
            if (object.isEmpty()) {
                return new AzureErrorResponse("BlobNotFound", "The specified blob does not exist.")
                        .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
            }
            Response conditionFailure = validateBlobConditions(request, object);
            if (conditionFailure != null) {
                return conditionFailure;
            }
            Response leaseFailure = leaseService.validateWrite(request,
                    objKey(request.accountName(), containerName, blobName));
            if (leaseFailure != null) {
                return leaseFailure;
            }
            store.delete(objKey(request.accountName(), containerName, blobName));
            leaseService.onBlobDeleted(objKey(request.accountName(), containerName, blobName));
            return Response.status(Response.Status.ACCEPTED).build();
        });
    }

    private Response getBlobMetadata(AzureRequest request, String containerName, String blobName) {
        Response authFailure = authorizeRead(request, containerName, blobName);
        if (authFailure != null) {
            return authFailure;
        }
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
        Response authFailure = authorizeWrite(request, containerName, blobName);
        if (authFailure != null) {
            return authFailure;
        }
        return leaseService.exclusively(() -> {
            Optional<StoredObject> object = store.get(objKey(request.accountName(), containerName, blobName));
            if (object.isEmpty()) {
                return new AzureErrorResponse("BlobNotFound", "The specified blob does not exist.")
                        .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
            }

            Response conditionFailure = validateBlobConditions(request, object);
            if (conditionFailure != null) {
                return conditionFailure;
            }
            Response leaseFailure = leaseService.validateWrite(request,
                    objKey(request.accountName(), containerName, blobName));
            if (leaseFailure != null) {
                return leaseFailure;
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
        });
    }

    private Response listBlobs(AzureRequest request, String containerName) {
        Response authFailure = authorizeList(request, containerName);
        if (authFailure != null) {
            return authFailure;
        }
        String prefix = request.queryParams().getOrDefault("prefix", "");
        String delimiter = request.queryParams().getOrDefault("delimiter", "");
        String marker = request.queryParams().getOrDefault("marker", "");
        int maxResults = parseMaxResults(request.queryParams().get("maxresults"));
        String keyPrefix = objKey(request.accountName(), containerName, prefix);

        List<BlobModels.BlobPrefix> blobPrefixes = new ArrayList<>();
        List<BlobModels.BlobItem> blobs = new ArrayList<>();
        Set<String> seenPrefixes = new HashSet<>();
        store.scan(k -> k.startsWith(keyPrefix)).forEach(so -> {
            String name = so.metadata().getOrDefault("Name", so.key());
            if (!delimiter.isEmpty()) {
                String remaining = name.substring(prefix.length());
                int delimiterIndex = remaining.indexOf(delimiter);
                if (delimiterIndex >= 0) {
                    String blobPrefix = name.substring(0, prefix.length() + delimiterIndex + delimiter.length());
                    if (seenPrefixes.add(blobPrefix)) {
                        blobPrefixes.add(new BlobModels.BlobPrefix(blobPrefix));
                    }
                    return;
                }
            }
            blobs.add(new BlobModels.BlobItem(name, new BlobModels.BlobProperties(
                    RFC1123_DATE_TIME.format(so.lastModified()),
                    so.etag(),
                    (long) so.data().length,
                    usableContentType(so.metadata().get("Content-Type")),
                    so.metadata().getOrDefault("BlobType", "BlockBlob")
            ), includes(request.queryParams().get("include"), "metadata") ? userMetadata(so.metadata()) : null));
        });
        blobs.sort(Comparator.comparing(BlobModels.BlobItem::Name));

        int start = 0;
        if (!marker.isEmpty()) {
            while (start < blobs.size() && blobs.get(start).Name().compareTo(marker) < 0) {
                start++;
            }
        }
        int end = Math.min(start + maxResults, blobs.size());
        String nextMarker = end < blobs.size() ? blobs.get(end).Name() : "";
        BlobModels.BlobListResponse response = new BlobModels.BlobListResponse(
                "http://localhost:4577/" + request.accountName(),
                containerName, prefix, delimiter, marker, maxResults, new BlobModels.BlobItems(blobPrefixes, blobs.subList(start, end)), nextMarker
        );

        return Response.ok(XmlUtils.toXml(response)).type(MediaType.APPLICATION_XML).build();
    }

    private Response listDataLakePaths(AzureRequest request, String filesystem) {
        String directory = request.queryParams().get("directory");
        Response authFailure = authorizeList(request, filesystem, directory);
        if (authFailure != null) {
            return authFailure;
        }
        if (store.get(nsKey(request.accountName(), filesystem)).isEmpty()) {
            return new AzureErrorResponse("FilesystemNotFound", "The specified filesystem does not exist.")
                    .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
        }
        if (directory != null && !directory.isEmpty()
                && !dataLakePathOperations.pathExists(request.accountName(), filesystem, directory)) {
            return new AzureErrorResponse("PathNotFound", "The specified path does not exist.")
                    .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
        }

        boolean recursive = Boolean.parseBoolean(request.queryParams().getOrDefault("recursive", "false"));
        List<DataLakePathListResponse.PathEntry> entries =
                dataLakePathOperations.list(request.accountName(), filesystem, directory, recursive);
        DataLakeContinuation continuation = parseDataLakeContinuation(
                request.queryParams().get("continuation"));
        if (!continuation.valid()) {
            return new AzureErrorResponse("InvalidQueryParameterValue",
                    "Value for one of the query parameters specified in the request URI is invalid.")
                    .toDataLakeJsonResponse(Response.Status.BAD_REQUEST.getStatusCode());
        }
        int start = continuation.startFrom() == null
                ? continuation.offset()
                : findDataLakeStartIndex(entries, directory, continuation.startFrom());
        int maxResults = parseDataLakeMaxResults(request.queryParams().get("maxResults"));
        int end = Math.min(start + maxResults, entries.size());
        List<DataLakePathListResponse.PathEntry> page = start >= entries.size()
                ? List.of()
                : entries.subList(start, end);

        Response.ResponseBuilder builder = Response.ok(new DataLakePathListResponse(page))
                .type(MediaType.APPLICATION_JSON_TYPE);
        if (end < entries.size()) {
            builder.header("x-ms-continuation", Integer.toString(end));
        }
        return builder.build();
    }

    private static int parseMaxResults(String value) {
        if (value == null || value.isBlank()) {
            return 1000;
        }
        return Integer.parseInt(value);
    }

    private static int parseDataLakeMaxResults(String value) {
        if (value == null || value.isBlank()) {
            return 5000;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                return 5000;
            }
            return Math.min(parsed, 5000);
        } catch (NumberFormatException e) {
            return 5000;
        }
    }

    /**
     * Parse either Floci's server-generated numeric page offset or the HNS
     * startFrom continuation token generated by Hadoop ABFS 3.3.4. Hadoop's
     * XNS token is Base64("<crc64> 0 <entryName>"). We intentionally do not
     * validate Hadoop's CRC: Azure treats the token as opaque and the emulator
     * only needs the observable lexical startFrom behavior.
     */
    private static DataLakeContinuation parseDataLakeContinuation(String value) {
        if (value == null || value.isBlank()) {
            return new DataLakeContinuation(0, null, true);
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed < 0
                    ? new DataLakeContinuation(0, null, false)
                    : new DataLakeContinuation(parsed, null, true);
        } catch (NumberFormatException ignored) {
            // Hadoop HNS startFrom token is opaque Base64 rather than an integer.
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            int firstSpace = decoded.indexOf(' ');
            int secondSpace = firstSpace < 0 ? -1 : decoded.indexOf(' ', firstSpace + 1);
            if (firstSpace <= 0 || secondSpace <= firstSpace + 1 || secondSpace == decoded.length() - 1) {
                return new DataLakeContinuation(0, null, false);
            }
            String marker = decoded.substring(firstSpace + 1, secondSpace);
            if (!"0".equals(marker)) {
                return new DataLakeContinuation(0, null, false);
            }
            String startFrom = decoded.substring(secondSpace + 1);
            if (startFrom.isBlank() || startFrom.startsWith("/")) {
                return new DataLakeContinuation(0, null, false);
            }
            return new DataLakeContinuation(0, startFrom, true);
        } catch (IllegalArgumentException e) {
            return new DataLakeContinuation(0, null, false);
        }
    }

    private static int findDataLakeStartIndex(
            List<DataLakePathListResponse.PathEntry> entries,
            String directory,
            String startFrom) {
        String normalizedDirectory = normalizeDataLakePath(directory);
        for (int index = 0; index < entries.size(); index++) {
            String name = entries.get(index).name();
            String relative = name;
            if (normalizedDirectory != null && !normalizedDirectory.isBlank()) {
                String prefix = normalizedDirectory + "/";
                if (name.startsWith(prefix)) {
                    relative = name.substring(prefix.length());
                }
            }
            if (relative.compareTo(startFrom) >= 0) {
                return index;
            }
        }
        return entries.size();
    }

    private record DataLakeContinuation(int offset, String startFrom, boolean valid) {
    }

    // ── Block Blob ────────────────────────────────────────────────────────────

    /**
     * PUT /{container}/{blob}?comp=block&blockid={BASE64}
     * <p>Stages one block. Data is stored under a {@code __blk__:} key and only
     * becomes part of the blob after a successful {@link #putBlockList}.
     */
    private Response putBlock(AzureRequest request, String containerName, String blobName) {
        try {
            Response authFailure = authorizeWrite(request, containerName, blobName);
            if (authFailure != null) {
                return authFailure;
            }
            String blockId = request.queryParams().get("blockid");
            if (blockId == null || blockId.isBlank()) {
                return new AzureErrorResponse("InvalidQueryParameterValue",
                        "Value for one of the query parameters specified in the request URI is invalid.")
                        .toXmlResponse(400);
            }
            byte[] data = request.bodyStream().readAllBytes();
            return leaseService.exclusively(() -> {
                if (store.get(nsKey(request.accountName(), containerName)).isEmpty()) {
                    return new AzureErrorResponse("ContainerNotFound", "The specified container does not exist.")
                            .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
                }
                Response leaseFailure = leaseService.validateWrite(request,
                        objKey(request.accountName(), containerName, blobName));
                if (leaseFailure != null) {
                    return leaseFailure;
                }
                store.put(blockStagingKey(request.accountName(), containerName, blobName, blockId),
                        new StoredObject(blockId, data, Map.of("BlockId", blockId), Instant.now(),
                                UUID.randomUUID().toString()));
                return Response.status(Response.Status.CREATED)
                        .header("x-ms-request-server-encrypted", "true")
                        .header("Content-Length", 0)
                        .build();
            });
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
            Response authFailure = authorizeWrite(request, containerName, blobName);
            if (authFailure != null) {
                return authFailure;
            }
            List<String> blockIds = parseBlockList(request.bodyStream().readAllBytes());

            return leaseService.exclusively(() -> {
                if (store.get(nsKey(request.accountName(), containerName)).isEmpty()) {
                    return new AzureErrorResponse("ContainerNotFound", "The specified container does not exist.")
                            .toXmlResponse(Response.Status.NOT_FOUND.getStatusCode());
                }

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

                Response leaseFailure = leaseService.validateWrite(request,
                        objKey(request.accountName(), containerName, blobName));
                if (leaseFailure != null) {
                    return leaseFailure;
                }

                // Build blob metadata
                Map<String, String> metadata = new HashMap<>();
                String blobType = request.headers().getHeaderString("x-ms-blob-type");
                metadata.put("BlobType", blobType != null ? blobType : "BlockBlob");
                addBlobHttpProperties(request, metadata);
                metadata.put("Name", blobName);
                metadata.put(CREATION_TIME_KEY, createdOn(
                        store.get(objKey(request.accountName(), containerName, blobName))).toString());
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
            });
        } catch (IOException e) {
            LOGGER.errorf(e, "putBlockList I/O error: container=%s blob=%s", containerName, blobName);
            return Response.serverError().build();
        }
    }

    private static void addBlobHttpProperties(AzureRequest request, Map<String, String> metadata) {
        String contentType = request.headers().getHeaderString("x-ms-blob-content-type");
        if (contentType == null) {
            contentType = request.headers().getHeaderString(HttpHeaders.CONTENT_TYPE);
        }
        metadata.put("Content-Type", usableContentType(contentType));
        BLOB_HTTP_PROPERTY_HEADERS.forEach((requestHeader, responseHeader) -> {
            String value = request.headers().getHeaderString(requestHeader);
            if (value != null) {
                metadata.put(responseHeader, value);
            }
        });
    }

    /**
     * Returns a content type safe to send back, substituting {@code application/octet-stream} - the
     * default {@code Get Blob Properties} documents for a blob with no content type specified - for one
     * that is missing, empty, or not a valid media type.
     */
    private static String usableContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            MediaType.valueOf(contentType);
        } catch (IllegalArgumentException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        return contentType;
    }

    /**
     * GET /{container}/{blob}?comp=blocklist[&blocklisttype=committed|uncommitted|all]
     * <p>Returns committed blocks (from blob metadata) and/or uncommitted
     * (staged) blocks, depending on {@code blocklisttype}.
     */
    private Response getBlockList(AzureRequest request, String containerName, String blobName) {
        Response authFailure = authorizeRead(request, containerName, blobName);
        if (authFailure != null) {
            return authFailure;
        }
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

    private Response authorizeRead(AzureRequest request, String containerName, String blobName) {
        return storageSas(request)
                .flatMap(token -> sasAuthorization.authorizeRead(
                        request, containerName, blobName, token))
                .orElse(null);
    }

    private Response authorizeList(AzureRequest request, String containerName) {
        return storageSas(request)
                .flatMap(token -> sasAuthorization.authorizeList(request, containerName, token))
                .orElse(null);
    }

    private Response authorizeList(AzureRequest request, String containerName, String path) {
        return storageSas(request)
                .flatMap(token -> sasAuthorization.authorizeList(request, containerName, path, token))
                .orElse(null);
    }

    private Response authorizeCreate(AzureRequest request, String containerName, String blobName) {
        return storageSas(request)
                .flatMap(token -> sasAuthorization.authorizeCreate(
                        request, containerName, blobName, token))
                .orElse(null);
    }

    private Response authorizeWrite(AzureRequest request, String containerName, String blobName) {
        return storageSas(request)
                .flatMap(token -> sasAuthorization.authorizeWrite(
                        request, containerName, blobName, token))
                .orElse(null);
    }

    private Response authorizeDelete(AzureRequest request, String containerName, String blobName) {
        return storageSas(request)
                .flatMap(token -> sasAuthorization.authorizeDelete(
                        request, containerName, blobName, token))
                .orElse(null);
    }

    private Response authorizeAppend(AzureRequest request, String containerName, String blobName) {
        return storageSas(request)
                .flatMap(token -> sasAuthorization.authorizeAppend(request, containerName, blobName, token))
                .orElse(null);
    }

    private static Optional<StorageSasToken> storageSas(AzureRequest request) {
        if (request.authContext() == null || request.authContext().type() != AuthType.SAS) {
            return Optional.empty();
        }
        return request.authContext().storageSas();
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

    private static boolean isDataLakeRequest(AzureRequest request) {
        String host = request.host();
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalizedHost = host.trim().toLowerCase(Locale.ROOT);
        int portSeparator = normalizedHost.indexOf(':');
        if (portSeparator >= 0) {
            normalizedHost = normalizedHost.substring(0, portSeparator);
        }
        return normalizedHost.endsWith(".dfs.core.windows.net");
    }

    public void clear() {
        leaseService.exclusively(() -> {
            store.clear();
            leaseService.clear();
        });
    }

    public void ensureContainer(String accountName, String containerName) {
        leaseService.exclusively(() -> store.put(nsKey(accountName, containerName), NS_SENTINEL));
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

    /**
     * Creation time to record when writing a blob: an overwrite preserves the original
     * creation time, a first write stamps now. Azure reports creation time independently
     * of last-modified, so it must not be re-derived on later writes or metadata updates.
     */
    private static Instant createdOn(Optional<StoredObject> existing) {
        return existing.map(BlobServiceHandler::creationTime).orElseGet(Instant::now);
    }

    /**
     * Creation time of a stored blob. Falls back to last-modified for blobs persisted
     * before {@code CreationTime} was recorded (reloaded from a persistent backend).
     */
    private static Instant creationTime(StoredObject so) {
        String stored = so.metadata().get(CREATION_TIME_KEY);
        return stored == null ? so.lastModified() : Instant.parse(stored);
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
