package io.floci.az.core.cloud;

/** Which signal decided a request's {@link ScopeContext}, in the order {@link ScopeResolver} tries them. */
public enum ScopeSource {
    /** {@code subscriptions/{sub}} in the path: subscription, then its tenant, then that tenant's cloud. */
    ARM_PATH,
    /** An Entra path rooted at a tenant id ({@code {tenant}/oauth2/v2.0/token}): that tenant's cloud. */
    ENTRA_PATH,
    /** A real Azure host suffix ({@code .blob.core.usgovcloudapi.net}), longest match. */
    HOST_SUFFIX,
    /** A path-style or emulator-host data-plane name owned in exactly one cloud. */
    DATA_PLANE_NAME,
    /** A catalog ARM or login host ({@code management.chinacloudapi.cn}, {@code login.microsoftonline.us}). */
    CLOUD_HOST,
    /** No signal applied: the default cloud. */
    DEFAULT
}
