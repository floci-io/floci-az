package io.floci.az.core.directory;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A tenant in the directory. A tenant lives in exactly one cloud; a subscription's cloud is derived through
 * its tenant and is never stored on the subscription.
 *
 * @param id            the tenant id, as configured or first seen
 * @param cloud         the catalog name of the tenant's cloud, such as {@code AzureCloud}
 * @param displayName   what {@code GET /tenants} reports as {@code displayName}
 * @param defaultDomain what {@code GET /tenants} reports as {@code defaultDomain} and {@code domains}
 * @param origin        how the tenant entered the directory
 */
@RegisterForReflection
public record Tenant(String id, String cloud, String displayName, String defaultDomain, DirectoryOrigin origin) {
}
