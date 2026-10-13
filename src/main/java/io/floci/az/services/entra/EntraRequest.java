package io.floci.az.services.entra;

import io.floci.az.core.AzureRequest;

/**
 * One request to the token service, with what every endpoint needs already resolved.
 *
 * @param request       the routed request
 * @param path          the request path without leading or trailing slashes, tenant segment first
 * @param tenantSegment the tenant segment as sent: a tenant id or common, organizations, consumers
 * @param tenantId      the tenant the request acts in (an alias resolves to the default tenant)
 * @param baseUrl       the request base URL, no trailing slash
 */
public record EntraRequest(AzureRequest request, String path, String tenantSegment, String tenantId,
                           String baseUrl) {

    /** {@code {base}/{tenant segment}}: the root the tenant's endpoint URLs hang off. */
    public String tenantBase() {
        return baseUrl + "/" + tenantSegment;
    }
}
