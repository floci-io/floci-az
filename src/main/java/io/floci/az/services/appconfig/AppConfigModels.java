package io.floci.az.services.appconfig;

import java.util.List;
import java.util.Map;

/**
 * Shared constants and lightweight carriers for the App Configuration handler.
 *
 * <p>Key-value and snapshot items are intentionally modelled as {@code Map<String,Object>} rather
 * than fixed records — the {@code $select} projection returns an arbitrary subset of fields, which
 * maps express more naturally than records.</p>
 */
public final class AppConfigModels {

    private AppConfigModels() {
    }

    // Azure App Configuration media types (data plane).
    public static final String MT_KV          = "application/vnd.microsoft.appconfig.kv+json;charset=utf-8";
    public static final String MT_KVSET       = "application/vnd.microsoft.appconfig.kvset+json;charset=utf-8";
    public static final String MT_KEYSET      = "application/vnd.microsoft.appconfig.keyset+json;charset=utf-8";
    public static final String MT_LABELSET    = "application/vnd.microsoft.appconfig.labelset+json;charset=utf-8";
    public static final String MT_SNAPSHOT    = "application/vnd.microsoft.appconfig.snapshot+json;charset=utf-8";
    public static final String MT_SNAPSHOTSET = "application/vnd.microsoft.appconfig.snapshotset+json;charset=utf-8";
    public static final String MT_PROBLEM     = "application/problem+json;charset=utf-8";
    public static final String MT_JSON        = "application/json;charset=utf-8";

    // Snapshot lifecycle states.
    public static final String STATUS_PROVISIONING = "provisioning";
    public static final String STATUS_READY        = "ready";
    public static final String STATUS_ARCHIVED     = "archived";
    public static final String STATUS_FAILED       = "failed";

    /** A single page of list results plus the optional relative continuation link. */
    public record Page(List<Map<String, Object>> items, String nextLink) {
    }

    /** Response body for {@code GET /operations?snapshot=...}. */
    public record OperationDetails(String id, String status, Object error) {
    }
}
