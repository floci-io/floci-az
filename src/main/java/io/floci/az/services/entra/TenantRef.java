package io.floci.az.services.entra;

/**
 * The tenant a request acts in, as {@link TenantResolver} resolved it.
 *
 * @param tenantId          the tenant id tokens are issued in ({@code tid}, issuer path)
 * @param directoryTenantId the tenant whose directory serves the request's lookups
 * @param known             whether the resolver knows this tenant; an unknown tenant id is
 *                          served from the default tenant's directory
 */
public record TenantRef(String tenantId, String directoryTenantId, boolean known) {
}
