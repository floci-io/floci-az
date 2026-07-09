package io.floci.az.services.appconfig;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.appconfig.AppConfigModels.OperationDetails;
import io.floci.az.services.appconfig.AppConfigModels.Page;
import io.floci.az.core.arm.ArmJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static io.floci.az.services.appconfig.AppConfigModels.*;

@ApplicationScoped
public class AppConfigHandler implements AzureServiceHandler, Resettable {

    private static final Logger LOG = Logger.getLogger(AppConfigHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'")
            .withZone(ZoneId.of("UTC"));

    private static final String API_VERSION = "2024-09-01";

    // Null byte (\0) cannot appear in keys or labels per the Azure spec — safe separator
    private static final char SEP = '\0';

    // Storage key prefixes
    private static final String PREFIX_KV   = "/kv/";
    private static final String PREFIX_REV  = "/rev/";
    private static final String PREFIX_SNAP = "/snap/";

    // Transient sort token attached to list items, stripped before serialisation.
    private static final String TOKEN = "_token";

    private final StorageBackend<String, StoredObject> store;
    private final SyncTokens syncTokens;

    @Inject
    public AppConfigHandler(StorageFactory factory, SyncTokens syncTokens) {
        this.store = factory.create("appconfig");
        this.syncTokens = syncTokens;
    }

    @Override
    public String getServiceType() {
        return "appconfig";
    }

    @Override
    public boolean canHandle(AzureRequest req) {
        return "appconfig".equals(req.serviceType());
    }

    @Override
    public Response handle(AzureRequest req) {
        String path = req.resourcePath();
        String method = req.method().toUpperCase();

        LOG.debugf("AppConfig %s /%s", method, path);

        if (path.startsWith("kv/")) {
            String key = path.substring(3);
            return switch (method) {
                case "GET", "HEAD" -> getKv(req, key, "HEAD".equals(method));
                case "PUT"         -> putKv(req, key);
                case "DELETE"      -> deleteKv(req, key);
                default            -> notImplemented();
            };
        }

        if ("kv".equals(path)) {
            return switch (method) {
                case "GET", "HEAD" -> listKv(req, "HEAD".equals(method));
                default            -> notImplemented();
            };
        }

        if ("keys".equals(path)) {
            return switch (method) {
                case "GET", "HEAD" -> listKeys(req, "HEAD".equals(method));
                default            -> notImplemented();
            };
        }

        if ("labels".equals(path)) {
            return switch (method) {
                case "GET", "HEAD" -> listLabels(req, "HEAD".equals(method));
                default            -> notImplemented();
            };
        }

        if ("revisions".equals(path)) {
            return switch (method) {
                case "GET", "HEAD" -> listRevisions(req, "HEAD".equals(method));
                default            -> notImplemented();
            };
        }

        if (path.startsWith("locks/")) {
            String key = path.substring(6);
            return switch (method) {
                case "PUT"    -> setLock(req, key, true);
                case "DELETE" -> setLock(req, key, false);
                default       -> notImplemented();
            };
        }

        if (path.startsWith("snapshots/")) {
            String name = path.substring(10);
            return switch (method) {
                case "GET", "HEAD" -> getSnapshot(req, name, "HEAD".equals(method));
                case "PUT"         -> putSnapshot(req, name);
                case "PATCH"       -> patchSnapshot(req, name);
                default            -> notImplemented();
            };
        }

        if ("snapshots".equals(path)) {
            return switch (method) {
                case "GET", "HEAD" -> listSnapshots(req, "HEAD".equals(method));
                default            -> notImplemented();
            };
        }

        if ("operations".equals(path)) {
            if ("GET".equals(method) || "HEAD".equals(method)) {
                return getOperations(req, "HEAD".equals(method));
            }
            return notImplemented();
        }

        return notImplemented();
    }

    // -------------------------------------------------------------------------
    // /kv/{key}
    // -------------------------------------------------------------------------

    private Response getKv(AzureRequest req, String key, boolean headOnly) {
        String label = KvFilters.normalizeLabel(req.queryParams().get("label"));
        Instant asOf = KvFilters.parseAcceptDatetime(req.headers().getHeaderString("Accept-Datetime"));

        Optional<StoredObject> found = asOf != null
                ? resolveAsOf(req.accountName(), key, label, asOf)
                : store.get(kvKey(req.accountName(), key, label));
        if (found.isEmpty()) {
            return errorNotFound(req, key);
        }
        StoredObject obj = found.get();

        String ifNoneMatch = req.headers().getHeaderString("If-None-Match");
        if (ifNoneMatch != null && stripQuotes(ifNoneMatch).equals(obj.etag())) {
            return sync(Response.status(304).header("ETag", quoted(obj.etag())), req).build();
        }

        if (headOnly) {
            return sync(Response.ok()
                    .type(MT_KV)
                    .header("ETag", quoted(obj.etag()))
                    .header("Last-Modified", ISO_FMT.format(obj.lastModified())), req).build();
        }

        Map<String, Object> item = KvFilters.applySelect(
                buildKvItem(obj, key, labelJson(label)), parseSelect(req));
        return kvResponse(req, item, obj.etag());
    }

    private Response putKv(AzureRequest req, String key) {
        String label = KvFilters.normalizeLabel(req.queryParams().get("label"));
        String sk = kvKey(req.accountName(), key, label);

        String ifMatch = req.headers().getHeaderString("If-Match");
        if (ifMatch != null) {
            String clientEtag = stripQuotes(ifMatch);
            if (!"*".equals(clientEtag)) {
                Optional<StoredObject> existing = store.get(sk);
                if (existing.isEmpty() || !existing.get().etag().equals(clientEtag)) {
                    return sync(Response.status(412), req).build();
                }
            }
        }

        Optional<StoredObject> current = store.get(sk);
        if (current.isPresent() && "true".equals(current.get().metadata().get("locked"))) {
            return sync(Response.status(423), req).build();
        }

        Map<String, Object> body = parseBody(req);
        String value       = (String) body.get("value");
        String contentType = (String) body.get("content_type");
        @SuppressWarnings("unchecked")
        Map<String, String> tags = body.get("tags") instanceof Map<?, ?> m
                ? (Map<String, String>) m
                : new LinkedHashMap<>();

        String  etag = newEtag();
        Instant now  = Instant.now();

        Map<String, Object> dataMap = new LinkedHashMap<>();
        dataMap.put("value", value);
        dataMap.put("content_type", contentType);
        dataMap.put("tags", tags);
        byte[] data = toBytes(dataMap);

        Map<String, String> meta = new HashMap<>();
        meta.put("label", label);

        StoredObject obj = new StoredObject(sk, data, meta, now, etag);
        store.put(sk, obj);
        storeRevision(req.accountName(), key, label, obj);

        return kvResponse(req, buildKvItem(obj, key, labelJson(label)), etag);
    }

    private Response deleteKv(AzureRequest req, String key) {
        String label = KvFilters.normalizeLabel(req.queryParams().get("label"));
        String sk = kvKey(req.accountName(), key, label);

        String ifMatch = req.headers().getHeaderString("If-Match");
        if (ifMatch != null) {
            String clientEtag = stripQuotes(ifMatch);
            if (!"*".equals(clientEtag)) {
                Optional<StoredObject> existing = store.get(sk);
                if (existing.isEmpty() || !existing.get().etag().equals(clientEtag)) {
                    return sync(Response.status(412), req).build();
                }
            }
        }

        Optional<StoredObject> existing = store.get(sk);
        if (existing.isEmpty()) {
            return errorNotFound(req, key);
        }

        StoredObject obj = existing.get();
        store.delete(sk);

        return kvResponse(req, buildKvItem(obj, key, labelJson(label)), obj.etag());
    }

    // -------------------------------------------------------------------------
    // /kv (list)
    // -------------------------------------------------------------------------

    private Response listKv(AzureRequest req, boolean headOnly) {
        String snapshotName = req.queryParams().get("snapshot");
        if (snapshotName != null && !snapshotName.isEmpty()) {
            return listKvFromSnapshot(req, snapshotName, headOnly);
        }

        String keyFilter   = req.queryParams().getOrDefault("key", "*");
        String labelFilter = req.queryParams().get("label");
        List<String> tagFilters = req.queryParamsMulti().get("tags");
        Instant asOf = KvFilters.parseAcceptDatetime(req.headers().getHeaderString("Accept-Datetime"));

        List<StoredObject> source = asOf != null
                ? latestRevisionsAsOf(req.accountName(), asOf)
                : store.scan(sk -> sk.startsWith(req.accountName() + PREFIX_KV));

        String prefix = req.accountName() + PREFIX_KV;
        List<Map<String, Object>> items = source.stream()
                .map(obj -> {
                    String[] kl = splitKeyLabel(obj.key(), prefix, asOf != null);
                    String k = kl[0];
                    String l = kl[1];
                    if (!KvFilters.matchesKeyFilter(k, keyFilter)) return null;
                    if (!KvFilters.matchesLabelFilter(l, labelFilter)) return null;
                    Map<String, Object> item = buildKvItem(obj, k, labelJson(l));
                    if (!KvFilters.tagsMatch(item.get("tags"), tagFilters)) return null;
                    item.put(TOKEN, k + SEP + l);
                    return item;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));

        return pagedList(req, items, MT_KVSET, headOnly);
    }

    // -------------------------------------------------------------------------
    // /keys
    // -------------------------------------------------------------------------

    private Response listKeys(AzureRequest req, boolean headOnly) {
        String nameFilter = req.queryParams().get("name");
        String prefix     = req.accountName() + PREFIX_KV;

        List<Map<String, Object>> items = store.scan(sk -> sk.startsWith(prefix))
                .stream()
                .map(obj -> {
                    String rest = obj.key().substring(prefix.length());
                    int sep = rest.indexOf(SEP);
                    return sep >= 0 ? rest.substring(0, sep) : rest;
                })
                .filter(k -> KvFilters.matchesKeyFilter(k, nameFilter != null ? nameFilter : "*"))
                .distinct()
                .map(k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", k);
                    m.put(TOKEN, k);
                    return m;
                })
                .collect(Collectors.toCollection(ArrayList::new));

        return pagedList(req, items, MT_KEYSET, headOnly);
    }

    // -------------------------------------------------------------------------
    // /labels
    // -------------------------------------------------------------------------

    private Response listLabels(AzureRequest req, boolean headOnly) {
        String nameFilter = req.queryParams().get("name");
        String prefix     = req.accountName() + PREFIX_KV;

        List<Map<String, Object>> items = store.scan(sk -> sk.startsWith(prefix))
                .stream()
                .map(obj -> {
                    String rest = obj.key().substring(prefix.length());
                    int sep = rest.indexOf(SEP);
                    return sep >= 0 ? rest.substring(sep + 1) : "";
                })
                .filter(l -> KvFilters.matchesKeyFilter(l.isEmpty() ? "\0" : l, nameFilter != null ? nameFilter : "*"))
                .distinct()
                .map(l -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", l.isEmpty() ? null : l);
                    m.put(TOKEN, l);
                    return m;
                })
                .collect(Collectors.toCollection(ArrayList::new));

        return pagedList(req, items, MT_LABELSET, headOnly);
    }

    // -------------------------------------------------------------------------
    // /revisions
    // -------------------------------------------------------------------------

    private Response listRevisions(AzureRequest req, boolean headOnly) {
        String keyFilter   = req.queryParams().getOrDefault("key", "*");
        String labelFilter = req.queryParams().get("label");
        List<String> tagFilters = req.queryParamsMulti().get("tags");
        Instant asOf = KvFilters.parseAcceptDatetime(req.headers().getHeaderString("Accept-Datetime"));
        String prefix = req.accountName() + PREFIX_REV;

        List<Map<String, Object>> items = store.scan(sk -> sk.startsWith(prefix))
                .stream()
                .filter(o -> asOf == null || !o.lastModified().isAfter(asOf))
                .map(obj -> {
                    // storage key format: account/rev/{key}\0{label}/{instant}
                    String rest = obj.key().substring(prefix.length());
                    int slash = rest.lastIndexOf('/');
                    String keyLabel = slash >= 0 ? rest.substring(0, slash) : rest;
                    int sep = keyLabel.indexOf(SEP);
                    String k = sep >= 0 ? keyLabel.substring(0, sep)  : keyLabel;
                    String l = sep >= 0 ? keyLabel.substring(sep + 1) : "";
                    if (!KvFilters.matchesKeyFilter(k, keyFilter))     return null;
                    if (!KvFilters.matchesLabelFilter(l, labelFilter)) return null;
                    Map<String, Object> item = buildKvItem(obj, k, labelJson(l));
                    if (!KvFilters.tagsMatch(item.get("tags"), tagFilters)) return null;
                    // Descending by last_modified → invert millis so the token sorts ascending.
                    long inv = Long.MAX_VALUE - obj.lastModified().toEpochMilli();
                    item.put(TOKEN, String.format("%019d:%s", inv, obj.etag()));
                    return item;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));

        return pagedList(req, items, MT_KVSET, headOnly);
    }

    // -------------------------------------------------------------------------
    // /locks/{key}
    // -------------------------------------------------------------------------

    private Response setLock(AzureRequest req, String key, boolean lock) {
        String label = KvFilters.normalizeLabel(req.queryParams().get("label"));
        String sk = kvKey(req.accountName(), key, label);

        String ifMatch = req.headers().getHeaderString("If-Match");
        if (ifMatch != null) {
            String clientEtag = stripQuotes(ifMatch);
            if (!"*".equals(clientEtag)) {
                Optional<StoredObject> existing = store.get(sk);
                if (existing.isEmpty() || !existing.get().etag().equals(clientEtag)) {
                    return sync(Response.status(412), req).build();
                }
            }
        }

        Optional<StoredObject> existing = store.get(sk);
        if (existing.isEmpty()) {
            return errorNotFound(req, key);
        }

        StoredObject old = existing.get();
        Map<String, String> meta = new HashMap<>(old.metadata());
        meta.put("locked", lock ? "true" : "false");

        StoredObject updated = new StoredObject(sk, old.data(), meta, old.lastModified(), newEtag());
        store.put(sk, updated);

        return kvResponse(req, buildKvItem(updated, key, labelJson(label)), updated.etag());
    }

    // -------------------------------------------------------------------------
    // /kv?snapshot={name}
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Response listKvFromSnapshot(AzureRequest req, String name, boolean headOnly) {
        Optional<StoredObject> found = store.get(snapKey(req.accountName(), name));
        if (found.isEmpty()) {
            return errorNotFound(req, name);
        }

        Map<String, Object> snap = parseStoredData(found.get());
        List<Map<String, Object>> raw = (List<Map<String, Object>>) snap.getOrDefault("items", List.of());
        List<Map<String, Object>> items = raw.stream()
                .map(i -> {
                    Map<String, Object> item = new LinkedHashMap<>(i);
                    String l = item.get("label") == null ? "" : String.valueOf(item.get("label"));
                    item.put(TOKEN, item.get("key") + String.valueOf(SEP) + l);
                    return item;
                })
                .collect(Collectors.toCollection(ArrayList::new));

        return pagedList(req, items, MT_KVSET, headOnly);
    }

    // -------------------------------------------------------------------------
    // /snapshots
    // -------------------------------------------------------------------------

    private Response listSnapshots(AzureRequest req, boolean headOnly) {
        String nameFilter   = req.queryParams().get("name");
        String statusFilter = req.queryParams().get("status");
        String prefix = req.accountName() + PREFIX_SNAP;

        List<Map<String, Object>> items = store.scan(sk -> sk.startsWith(prefix))
                .stream()
                .map(this::parseStoredData)
                .filter(snap -> {
                    String n = (String) snap.get("name");
                    if (nameFilter != null && !KvFilters.matchesKeyFilter(n, nameFilter)) return false;
                    if (statusFilter != null) {
                        return statusFilterMatches(statusFilter, (String) snap.get("status"));
                    }
                    return true;
                })
                .map(snap -> {
                    Map<String, Object> view = snapshotPublicView(snap);
                    view.put(TOKEN, String.valueOf(snap.get("name")));
                    return view;
                })
                .collect(Collectors.toCollection(ArrayList::new));

        return pagedList(req, items, MT_SNAPSHOTSET, headOnly);
    }

    private boolean statusFilterMatches(String filter, String status) {
        for (String s : filter.split(",")) {
            if (s.trim().equalsIgnoreCase(status)) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // /snapshots/{name}  GET/HEAD
    // -------------------------------------------------------------------------

    private Response getSnapshot(AzureRequest req, String name, boolean headOnly) {
        Optional<StoredObject> found = store.get(snapKey(req.accountName(), name));
        if (found.isEmpty()) {
            return errorNotFound(req, name);
        }
        StoredObject obj = found.get();

        String ifNoneMatch = req.headers().getHeaderString("If-None-Match");
        if (ifNoneMatch != null && stripQuotes(ifNoneMatch).equals(obj.etag())) {
            return sync(Response.status(304).header("ETag", quoted(obj.etag())), req).build();
        }
        String ifMatch = req.headers().getHeaderString("If-Match");
        if (ifMatch != null) {
            String clientEtag = stripQuotes(ifMatch);
            if (!"*".equals(clientEtag) && !obj.etag().equals(clientEtag)) {
                return sync(Response.status(412), req).build();
            }
        }

        if (headOnly) {
            return sync(Response.ok().type(MT_SNAPSHOT).header("ETag", quoted(obj.etag())), req).build();
        }

        Map<String, Object> view = KvFilters.applySelect(snapshotPublicView(parseStoredData(obj)), parseSelect(req));
        return snapResponse(req, view, obj.etag());
    }

    // -------------------------------------------------------------------------
    // /snapshots/{name}  PUT  (create — async provisioning)
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Response putSnapshot(AzureRequest req, String name) {
        String sk = snapKey(req.accountName(), name);
        if (store.get(sk).isPresent()) {
            return sync(Response.status(409), req).build();
        }

        Map<String, Object> body = parseBody(req);
        List<Map<String, Object>> filters = (List<Map<String, Object>>) body.getOrDefault("filters", List.of());
        String composition = (String) body.getOrDefault("composition_type", "key");
        Long retentionPeriod = body.get("retention_period") instanceof Number n
                ? n.longValue() : 2592000L;
        Map<String, String> tags = body.get("tags") instanceof Map<?, ?> m
                ? (Map<String, String>) m : new LinkedHashMap<>();

        // Capture items now — the snapshot is frozen at creation time.
        List<Map<String, Object>> items = captureSnapshotItems(req.accountName(), filters, composition);

        String etag = newEtag();
        Instant now = Instant.now();
        Instant expires = retentionPeriod > 0 ? now.plusSeconds(retentionPeriod) : null;

        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("name", name);
        snap.put("status", STATUS_PROVISIONING);
        snap.put("filters", filters);
        snap.put("composition_type", composition);
        snap.put("created", ISO_FMT.format(now));
        snap.put("expires", expires != null ? ISO_FMT.format(expires) : null);
        snap.put("retention_period", retentionPeriod);
        snap.put("size", computeSnapshotSize(items));
        snap.put("items_count", items.size());
        snap.put("tags", tags);
        snap.put("etag", etag);
        snap.put("items", items);

        store.put(sk, new StoredObject(sk, toBytes(snap),
                Map.of("name", name, "status", STATUS_PROVISIONING), now, etag));

        try {
            return sync(Response.status(201)
                    .entity(MAPPER.writeValueAsString(snapshotPublicView(snap)))
                    .type(MT_SNAPSHOT)
                    .header("ETag", quoted(etag))
                    .header("Operation-Location", "/operations?snapshot="
                            + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&api-version=" + API_VERSION), req)
                    .build();
        } catch (JsonProcessingException e) {
            return Response.serverError().build();
        }
    }

    // -------------------------------------------------------------------------
    // /snapshots/{name}  PATCH  (archive / recover)
    // -------------------------------------------------------------------------

    private Response patchSnapshot(AzureRequest req, String name) {
        String sk = snapKey(req.accountName(), name);
        Optional<StoredObject> found = store.get(sk);
        if (found.isEmpty()) {
            return errorNotFound(req, name);
        }

        Map<String, Object> patch = parseBody(req);
        String newStatus = (String) patch.get("status");

        Map<String, Object> snap = parseStoredData(found.get());
        String currentStatus = (String) snap.get("status");

        if (STATUS_ARCHIVED.equals(newStatus) && !STATUS_READY.equals(currentStatus)) {
            return sync(Response.status(409), req).build();
        }
        if (STATUS_READY.equals(newStatus) && !STATUS_ARCHIVED.equals(currentStatus)) {
            return sync(Response.status(409), req).build();
        }

        snap.put("status", newStatus);
        if (STATUS_ARCHIVED.equals(newStatus)) {
            snap.put("expires", ISO_FMT.format(Instant.now().plusSeconds(604800L))); // 7 days
        } else {
            snap.put("expires", null);
        }

        String newEtag = newEtag();
        snap.put("etag", newEtag);
        store.put(sk, new StoredObject(sk, toBytes(snap),
                Map.of("name", name, "status", newStatus), Instant.now(), newEtag));

        return snapResponse(req, snapshotPublicView(snap), newEtag);
    }

    // -------------------------------------------------------------------------
    // /operations?snapshot={name}  — completes async snapshot provisioning
    // -------------------------------------------------------------------------

    private Response getOperations(AzureRequest req, boolean headOnly) {
        String name = req.queryParams().get("snapshot");
        if (name == null || name.isEmpty()) {
            return sync(Response.status(400), req).build();
        }

        if (headOnly) {
            return sync(Response.ok().type(MT_JSON), req).build();
        }

        // Provisioning is instantaneous in the emulator: the first poll flips the snapshot to ready.
        Optional<StoredObject> found = store.get(snapKey(req.accountName(), name));
        if (found.isPresent()) {
            Map<String, Object> snap = parseStoredData(found.get());
            if (STATUS_PROVISIONING.equals(snap.get("status"))) {
                snap.put("status", STATUS_READY);
                store.put(found.get().key(), new StoredObject(found.get().key(), toBytes(snap),
                        Map.of("name", name, "status", STATUS_READY),
                        found.get().lastModified(), found.get().etag()));
            }
        }

        OperationDetails details = new OperationDetails(name, "Succeeded", null);
        try {
            return sync(Response.ok(MAPPER.writeValueAsString(details), MT_JSON), req).build();
        } catch (JsonProcessingException e) {
            return Response.serverError().build();
        }
    }

    // -------------------------------------------------------------------------
    // Pagination
    // -------------------------------------------------------------------------

    private Response pagedList(AzureRequest req, List<Map<String, Object>> items, String contentType, boolean headOnly) {
        items.sort(Comparator.comparing(i -> (String) i.get(TOKEN)));

        String listEtag = newEtag();
        if (headOnly) {
            return sync(Response.ok().type(contentType).header("ETag", quoted(listEtag)), req).build();
        }

        String after = firstNonBlank(req.queryParams().get("After"), req.queryParams().get("after"));
        List<String> select = parseSelect(req);

        Page page = Paginator.paginate(items, after, Paginator.PAGE_SIZE,
                i -> (String) i.get(TOKEN),
                tok -> buildNextLink(req, tok));

        List<Map<String, Object>> out = page.items().stream()
                .map(i -> {
                    i.remove(TOKEN);
                    return KvFilters.applySelect(i, select);
                })
                .collect(Collectors.toCollection(ArrayList::new));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", out);
        if (page.nextLink() != null) {
            body.put("@nextLink", page.nextLink());
        }

        try {
            Response.ResponseBuilder rb = Response.ok(MAPPER.writeValueAsString(body), contentType)
                    .header("ETag", quoted(listEtag));
            if (page.nextLink() != null) {
                rb.header("Link", "<" + page.nextLink() + ">; rel=\"next\"");
            }
            return sync(rb, req).build();
        } catch (JsonProcessingException e) {
            return Response.serverError().build();
        }
    }

    /** Builds the relative {@code @nextLink}: {@code /{resource}?<echoed filters>&After=<token>}. */
    private String buildNextLink(AzureRequest req, String token) {
        List<String> parts = new ArrayList<>();
        parts.add("api-version=" + API_VERSION);
        req.queryParamsMulti().forEach((k, values) -> {
            if (k.equalsIgnoreCase("after") || k.equalsIgnoreCase("api-version")) {
                return;
            }
            for (String v : values) {
                parts.add(urlEncode(k) + "=" + urlEncode(v));
            }
        });
        // Azure emits the continuation as lowercase `after` in @nextLink / Link; the JS SDK parses it
        // case-sensitively. The handler reads both casings on the way back in.
        parts.add("after=" + token); // token is already URL-safe base64
        return "/" + req.resourcePath() + "?" + String.join("&", parts);
    }

    // -------------------------------------------------------------------------
    // Accept-Datetime (time-travel) helpers
    // -------------------------------------------------------------------------

    /** Newest revision of one key/label at or before {@code asOf}. */
    private Optional<StoredObject> resolveAsOf(String account, String key, String label, Instant asOf) {
        String prefix = account + PREFIX_REV + key + SEP + label + "/";
        return store.scan(sk -> sk.startsWith(prefix)).stream()
                .filter(o -> !o.lastModified().isAfter(asOf))
                .max(Comparator.comparing(StoredObject::lastModified));
    }

    /** Newest revision per key/label at or before {@code asOf}, across the whole account. */
    private List<StoredObject> latestRevisionsAsOf(String account, Instant asOf) {
        String prefix = account + PREFIX_REV;
        Map<String, StoredObject> newest = new LinkedHashMap<>();
        for (StoredObject obj : store.scan(sk -> sk.startsWith(prefix))) {
            if (obj.lastModified().isAfter(asOf)) {
                continue;
            }
            String rest = obj.key().substring(prefix.length());
            int slash = rest.lastIndexOf('/');
            String keyLabel = slash >= 0 ? rest.substring(0, slash) : rest;
            StoredObject prev = newest.get(keyLabel);
            if (prev == null || obj.lastModified().isAfter(prev.lastModified())) {
                newest.put(keyLabel, obj);
            }
        }
        return new ArrayList<>(newest.values());
    }

    /**
     * Splits a stored key/revision key into [key, label]. For revision keys the trailing
     * {@code /instant} segment is dropped first.
     */
    private String[] splitKeyLabel(String storageKey, String kvPrefix, boolean revision) {
        String rest;
        if (revision) {
            int revIdx = storageKey.indexOf(PREFIX_REV);
            rest = storageKey.substring(revIdx + PREFIX_REV.length());
            int slash = rest.lastIndexOf('/');
            if (slash >= 0) {
                rest = rest.substring(0, slash);
            }
        } else {
            rest = storageKey.substring(kvPrefix.length());
        }
        int sep = rest.indexOf(SEP);
        String k = sep >= 0 ? rest.substring(0, sep)  : rest;
        String l = sep >= 0 ? rest.substring(sep + 1) : "";
        return new String[]{k, l};
    }

    // -------------------------------------------------------------------------
    // Snapshot helpers
    // -------------------------------------------------------------------------

    private String snapKey(String account, String name) {
        return account + PREFIX_SNAP + name;
    }

    private List<Map<String, Object>> captureSnapshotItems(
            String account, List<Map<String, Object>> filters, String composition) {

        String prefix = account + PREFIX_KV;
        List<StoredObject> allKvs = store.scan(sk -> sk.startsWith(prefix))
                .stream()
                .sorted(Comparator.comparing(StoredObject::lastModified))
                .collect(Collectors.toCollection(ArrayList::new));

        if ("key_label".equalsIgnoreCase(composition)) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (StoredObject obj : allKvs) {
                String[] kl = splitKeyLabel(obj.key(), prefix, false);
                for (Map<String, Object> f : filters) {
                    String keyPat = (String) f.getOrDefault("key", "*");
                    String lblPat = (String) f.get("label");
                    if (KvFilters.matchesKeyFilter(kl[0], keyPat) && KvFilters.matchesLabelFilter(kl[1], lblPat)) {
                        result.add(buildKvItem(obj, kl[0], labelJson(kl[1])));
                        break;
                    }
                }
            }
            return result;
        }

        // "key" composition: for the same key, the last matching filter wins.
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> f : filters) {
            String keyPat = (String) f.getOrDefault("key", "*");
            String lblPat = (String) f.get("label");
            for (StoredObject obj : allKvs) {
                String[] kl = splitKeyLabel(obj.key(), prefix, false);
                if (KvFilters.matchesKeyFilter(kl[0], keyPat) && KvFilters.matchesLabelFilter(kl[1], lblPat)) {
                    byKey.put(kl[0], buildKvItem(obj, kl[0], labelJson(kl[1])));
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private long computeSnapshotSize(List<Map<String, Object>> items) {
        try {
            return MAPPER.writeValueAsBytes(items).length;
        } catch (JsonProcessingException e) {
            return 0L;
        }
    }

    /** Returns a snapshot view without the internal {@code items} field. */
    private Map<String, Object> snapshotPublicView(Map<String, Object> snap) {
        Map<String, Object> view = new LinkedHashMap<>(snap);
        view.remove("items");
        return view;
    }

    private Response snapResponse(AzureRequest req, Map<String, Object> snap, String etag) {
        try {
            return sync(Response.ok(MAPPER.writeValueAsString(snap), MT_SNAPSHOT)
                    .header("ETag", quoted(etag)), req).build();
        } catch (JsonProcessingException e) {
            return Response.serverError().build();
        }
    }

    // -------------------------------------------------------------------------
    // Revision history
    // -------------------------------------------------------------------------

    private void storeRevision(String account, String key, String label, StoredObject obj) {
        String revKey = account + PREFIX_REV + key + SEP + label + "/" + obj.lastModified().toEpochMilli();
        store.put(revKey, new StoredObject(revKey, obj.data(), obj.metadata(), obj.lastModified(), obj.etag()));
    }

    // -------------------------------------------------------------------------
    // Response builders
    // -------------------------------------------------------------------------

    private Map<String, Object> buildKvItem(StoredObject obj, String key, Object labelValue) {
        Map<String, Object> data = parseStoredData(obj);
        boolean locked = "true".equals(obj.metadata().get("locked"));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("label", labelValue);
        item.put("value", data.get("value"));
        item.put("content_type", data.get("content_type"));
        item.put("tags", data.getOrDefault("tags", new LinkedHashMap<>()));
        item.put("etag", obj.etag());
        item.put("last_modified", ISO_FMT.format(obj.lastModified()));
        item.put("locked", locked);
        return item;
    }

    private Response kvResponse(AzureRequest req, Map<String, Object> item, String etag) {
        try {
            return sync(Response.ok(MAPPER.writeValueAsString(item), MT_KV)
                    .header("ETag", quoted(etag)), req).build();
        } catch (JsonProcessingException e) {
            return Response.serverError().build();
        }
    }

    private Response errorNotFound(AzureRequest req, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://azconfig.io/errors/key-not-found");
        body.put("title", "The key does not exist.");
        body.put("name", key);
        body.put("detail", "There is no value with the specified key and label.");
        body.put("status", 404);
        try {
            return sync(Response.status(404)
                    .entity(MAPPER.writeValueAsString(body))
                    .type(MT_PROBLEM), req).build();
        } catch (JsonProcessingException e) {
            return Response.status(404).build();
        }
    }

    private Response notImplemented() {
        return Response.status(501).build();
    }

    /** Adds the per-account {@code Sync-Token} response header. */
    private Response.ResponseBuilder sync(Response.ResponseBuilder rb, AzureRequest req) {
        return rb.header("Sync-Token", syncTokens.next(req.accountName()));
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void clearAll() {
        store.clear();
        syncTokens.clear();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String kvKey(String account, String key, String normalizedLabel) {
        return account + PREFIX_KV + key + SEP + normalizedLabel;
    }

    /** Maps the normalized internal label back to JSON: "" → null, otherwise the string. */
    private Object labelJson(String normalizedLabel) {
        return normalizedLabel.isEmpty() ? null : normalizedLabel;
    }

    private List<String> parseSelect(AzureRequest req) {
        String raw = firstNonBlank(req.queryParams().get("$Select"), req.queryParams().get("$select"));
        return KvFilters.parseSelect(raw);
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return (b != null && !b.isBlank()) ? b : null;
    }

    private String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String stripQuotes(String etag) {
        if (etag == null) return "";
        return (etag.startsWith("\"") && etag.endsWith("\""))
                ? etag.substring(1, etag.length() - 1)
                : etag;
    }

    private String quoted(String etag) {
        return "\"" + etag + "\"";
    }

    private String newEtag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseStoredData(StoredObject obj) {
        try {
            return MAPPER.readValue(obj.data(), new TypeReference<>() {});
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> parseBody(AzureRequest req) {
        return ArmJson.parseBodyMutable(req);
    }

    private byte[] toBytes(Object obj) {
        try {
            return MAPPER.writeValueAsBytes(obj);
        } catch (JsonProcessingException e) {
            return new byte[0];
        }
    }
}
