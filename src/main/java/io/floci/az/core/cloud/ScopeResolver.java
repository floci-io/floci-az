package io.floci.az.core.cloud;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.auth.TokenTenantHint;
import io.floci.az.core.directory.AzureDirectory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Resolves a request's {@link ScopeContext}. In Azure a tenant lives in exactly one cloud and a
 * subscription belongs to one tenant, so the cloud never appears in an id; it is derived from the first of
 * these signals that applies:
 *
 * <ol>
 *   <li>an ARM path {@code subscriptions/{sub}}: subscription, then tenant, then cloud</li>
 *   <li>an Entra path {@code {tenant}/...}: that tenant's cloud; {@code common}, {@code organizations} and
 *       {@code consumers} name no tenant and fall through</li>
 *   <li>a real Azure host suffix, longest match ({@link CloudCatalog#stripKnownDnsSuffix})</li>
 *   <li>a path-style or emulator-host data-plane name, by the cloud that owns it</li>
 *   <li>a catalog ARM or login host ({@code management.usgovcloudapi.net})</li>
 *   <li>otherwise the default cloud</li>
 * </ol>
 *
 * <p>Resolution only records; it never rejects a request. Deciding what a mismatch means (a token from
 * another tenant, a subscription nobody registered) is the job of the ARM request gate's rules.
 *
 * <p>{@link #tenantOfSubscription} and {@link #cloudOfTenant} ask the tenant and subscription directory
 * ({@link AzureDirectory}); a subscription it does not hold yet resolves to the tenant its first use registers it
 * under: the caller's under tenant scoping when the directory holds that tenant, the default tenant otherwise.
 * Signal 4 waits for the global-name registry.
 *
 * <p>The token's {@code tid} ({@link ScopeContext#tokenTenantId()}) is read only under
 * {@link EmulatorConfig#tenantScoping()}, unverified, as a hint of the caller's tenant ({@link TokenTenantHint});
 * otherwise it stays null and every caller is the default tenant, as floci-az has always answered.
 */
@ApplicationScoped
public class ScopeResolver {

    /** Entra authority aliases that name no tenant. */
    private static final Set<String> TENANT_ALIASES = Set.of("common", "organizations", "consumers");

    /** First segments of Entra paths that carry no tenant segment. */
    private static final Set<String> TENANTLESS_ENTRA_ROOTS = Set.of("oauth2", "discovery", ".well-known", "v2.0");

    private static final String USER_REALM_PREFIX = "common/userrealm/";

    /** (subscription, caller's token tenant or null) to the subscription's tenant. */
    private final BiFunction<String, String, String> tenantOfSubscription;
    private final Function<String, CloudProfile> cloudOfTenant;
    private final CloudProfile defaultCloud;
    private final Map<String, CloudProfile> cloudHosts;
    private final boolean readTokenTenant;

    @Inject
    public ScopeResolver(AzureDirectory directory, EmulatorConfig config) {
        this(directory::tenantOfSubscription, directory::cloudOfTenant, directory.defaultCloud(),
            config.tenantScoping());
    }

    /** One tenant in one cloud: every subscription is {@code defaultTenantId}'s, every tenant is in {@code defaultCloud}. */
    ScopeResolver(Supplier<String> defaultTenantId, CloudProfile defaultCloud) {
        this((subscription, caller) -> defaultTenantId.get(), tenant -> defaultCloud, defaultCloud, false);
    }

    ScopeResolver(Function<String, String> tenantOfSubscription, Function<String, CloudProfile> cloudOfTenant,
                  CloudProfile defaultCloud) {
        this((subscription, caller) -> tenantOfSubscription.apply(subscription), cloudOfTenant, defaultCloud, false);
    }

    ScopeResolver(BiFunction<String, String, String> tenantOfSubscription,
                  Function<String, CloudProfile> cloudOfTenant, CloudProfile defaultCloud, boolean readTokenTenant) {
        this.tenantOfSubscription = tenantOfSubscription;
        this.cloudOfTenant = cloudOfTenant;
        this.defaultCloud = defaultCloud;
        this.cloudHosts = cloudHosts(CloudCatalog.all());
        this.readTokenTenant = readTokenTenant;
    }

    /**
     * Whether {@code path} is one of the Entra (OpenID Connect) endpoints served at the ARM base URL:
     * {@code {tenant}/oauth2/v2.0/authorize}, {@code {tenant}/oauth2/v2.0/token}, {@code {tenant}/oauth2/token},
     * {@code {tenant}/discovery/v2.0/keys}, {@code {tenant}[/v2.0]/.well-known/openid-configuration} and
     * {@code common/userrealm/{username}}.
     */
    public static boolean isEntraPath(String path) {
        return path.contains("oauth2/v2.0/token") || path.endsWith("oauth2/token")
            || path.contains("oauth2/v2.0/authorize")
            || path.endsWith("discovery/v2.0/keys")
            || path.endsWith(".well-known/openid-configuration")
            || isUserRealmPath(path);
    }

    /**
     * {@code common/userrealm/{username}}: home realm discovery, which MSAL4J and MSAL Python call on
     * the authority host before a username-password grant. Entra serves it only under {@code common},
     * so a storage account named {@code common} is the only path it can shadow.
     */
    public static boolean isUserRealmPath(String path) {
        if (!path.startsWith(USER_REALM_PREFIX) || path.length() == USER_REALM_PREFIX.length()) {
            return false;
        }
        return path.indexOf('/', USER_REALM_PREFIX.length()) < 0;
    }

    /**
     * Resolves the scope of one request that carries no token, or whose token is not read.
     *
     * @param path the decoded request path without its leading slash
     * @param host the request host, lowercase and without a port; may be null
     */
    public ScopeContext resolve(String path, String host) {
        return resolve(path, host, null);
    }

    /**
     * Resolves the scope of one request.
     *
     * @param path          the decoded request path without its leading slash
     * @param host          the request host, lowercase and without a port; may be null
     * @param authorization the request's {@code Authorization} header value; may be null
     */
    public ScopeContext resolve(String path, String host, String authorization) {
        String safePath = path == null ? "" : path;
        String tokenTenantId = readTokenTenant ? TokenTenantHint.of(authorization).orElse(null) : null;

        Optional<ArmResourceId> armId = ArmResourceId.parse(safePath);
        if (armId.isPresent() && armId.get().subscriptionId() != null) {
            String subscription = armId.get().subscriptionId();
            String tenant = tenantOfSubscription.apply(subscription, tokenTenantId);
            return new ScopeContext(cloudOfTenant(tenant), tenant, subscription, armId.get().resourceGroup(),
                tokenTenantId, ScopeSource.ARM_PATH);
        }

        String entraTenant = entraPathTenant(safePath);
        if (entraTenant != null) {
            return new ScopeContext(cloudOfTenant(entraTenant), entraTenant, null, null, tokenTenantId,
                ScopeSource.ENTRA_PATH);
        }

        if (host != null && !host.isEmpty()) {
            Optional<DnsSuffixMatch> suffix = CloudCatalog.stripKnownDnsSuffix(host);
            if (suffix.isPresent()) {
                return new ScopeContext(suffix.get().cloud(), null, null, null, tokenTenantId, ScopeSource.HOST_SUFFIX);
            }
        }

        Optional<CloudProfile> nameOwner = dataPlaneNameOwner(safePath, host);
        if (nameOwner.isPresent()) {
            return new ScopeContext(nameOwner.get(), null, null, null, tokenTenantId, ScopeSource.DATA_PLANE_NAME);
        }

        if (host != null) {
            CloudProfile hostCloud = cloudHosts.get(host.toLowerCase(Locale.ROOT));
            if (hostCloud != null) {
                return new ScopeContext(hostCloud, null, null, null, tokenTenantId, ScopeSource.CLOUD_HOST);
            }
        }

        return new ScopeContext(defaultCloud, null, null, null, tokenTenantId, ScopeSource.DEFAULT);
    }

    /** The tenant a subscription belongs to: the directory's answer, the default tenant for an unknown one. */
    String tenantOfSubscription(String subscriptionId) {
        return tenantOfSubscription.apply(subscriptionId, null);
    }

    /** The cloud a tenant lives in: the directory's answer, the default cloud for an unknown tenant. */
    CloudProfile cloudOfTenant(String tenantId) {
        return cloudOfTenant.apply(tenantId);
    }

    /**
     * The cloud that owns a path-style or emulator-host data-plane name, when exactly one does. Empty until the
     * global-name registry exists: without it every name belongs to the default cloud, which is the answer the
     * default signal already gives.
     */
    Optional<CloudProfile> dataPlaneNameOwner(String path, String host) {
        return Optional.empty();
    }

    /** The tenant segment of an Entra path, or null when the path is not Entra's or names no tenant. */
    static String entraPathTenant(String path) {
        if (!isEntraPath(path)) {
            return null;
        }
        int slash = path.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        String first = path.substring(0, slash);
        String lower = first.toLowerCase(Locale.ROOT);
        if (TENANTLESS_ENTRA_ROOTS.contains(lower) || TENANT_ALIASES.contains(lower)) {
            return null;
        }
        return first;
    }

    /** Every catalog ARM and login host, mapped to its cloud. */
    private static Map<String, CloudProfile> cloudHosts(List<CloudProfile> clouds) {
        Map<String, CloudProfile> hosts = new HashMap<>();
        for (CloudProfile cloud : clouds) {
            addHost(hosts, hostOf(cloud.endpoints().resourceManager()), cloud);
            addHost(hosts, hostOf(cloud.endpoints().activeDirectory()), cloud);
            for (String authorityHost : cloud.endpoints().authorityHosts()) {
                addHost(hosts, hostOf(authorityHost), cloud);
            }
        }
        return Map.copyOf(hosts);
    }

    private static void addHost(Map<String, CloudProfile> hosts, String host, CloudProfile cloud) {
        if (host != null) {
            hosts.putIfAbsent(host, cloud);
        }
    }

    /** The lowercase host of an endpoint URL or bare host name, or null for a blank or unparseable value. */
    private static String hostOf(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        String candidate = endpoint.contains("://") ? URI.create(endpoint).getHost() : endpoint;
        if (candidate == null) {
            return null;
        }
        int slash = candidate.indexOf('/');
        String host = slash < 0 ? candidate : candidate.substring(0, slash);
        return host.toLowerCase(Locale.ROOT);
    }
}
