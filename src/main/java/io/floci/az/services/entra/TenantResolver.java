package io.floci.az.services.entra;

/**
 * Resolves which tenant a request acts in. The one implementation today,
 * {@link ConfiguredTenantResolver}, knows only {@code entra.default-tenant-id}; a resolver backed by
 * a tenant registry can replace it without touching its callers.
 */
public interface TenantResolver {

    /**
     * @param segment the tenant path segment as sent: a tenant id, or {@code common},
     *                {@code organizations} or {@code consumers}; null when the request has none
     * @param hint    a tenant id the caller already acts in (for example a token's {@code tid}),
     *                consulted only when {@code segment} is null or blank; may be null
     */
    TenantRef resolve(String segment, String hint);

    /** The tenant requests act in when nothing names one. */
    TenantRef defaultTenant();
}
