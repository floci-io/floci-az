package io.floci.az.core.cloud;

/**
 * Where a request lands: its cloud and, when the request names them, the tenant, subscription and resource
 * group. Resolved once per request by {@link ScopeResolver} and carried on the request itself rather than
 * in a request-scoped bean, because handlers run on a blocking worker where the request scope does not follow.
 *
 * @param cloud          the cloud the request addresses; never null
 * @param tenantId       the tenant that owns the addressed scope, or null when no signal names one
 * @param subscriptionId the subscription in an ARM path, or null
 * @param resourceGroup  the resource group in an ARM path, or null
 * @param tokenTenantId  the {@code tid} of the caller's token, or null when it is absent or not read
 * @param source         which signal decided {@code cloud}
 */
public record ScopeContext(CloudProfile cloud, String tenantId, String subscriptionId, String resourceGroup,
                           String tokenTenantId, ScopeSource source) {
}
