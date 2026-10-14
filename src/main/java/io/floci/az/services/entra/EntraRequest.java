package io.floci.az.services.entra;

import io.floci.az.core.AzureRequest;

/**
 * One request to the token service, with what every endpoint needs already resolved.
 *
 * @param request       the routed request
 * @param path          the request path without leading or trailing slashes, tenant segment first
 * @param tenantSegment the tenant segment as sent: a tenant id or common, organizations, consumers
 * @param tenant        the tenant the request acts in, as {@link TenantResolver} resolved the segment
 * @param baseUrl       the request base URL, no trailing slash
 */
public record EntraRequest(AzureRequest request, String path, String tenantSegment, TenantRef tenant,
                           String baseUrl) {

    /** The tenant the request acts in (an alias resolves to the default tenant). */
    public String tenantId() {
        return tenant.tenantId();
    }

    /** The tenant whose directory serves the request's user and application lookups. */
    public String directoryTenantId() {
        return tenant.directoryTenantId();
    }

    /** {@code {base}/{tenant segment}}: the root the tenant's endpoint URLs hang off. */
    public String tenantBase() {
        return baseUrl + "/" + tenantSegment;
    }
}
