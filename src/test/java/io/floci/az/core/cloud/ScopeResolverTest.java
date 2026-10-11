package io.floci.az.core.cloud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ScopeResolver: the six ordered cloud signals")
class ScopeResolverTest {

    private static final String DEFAULT_TENANT = "00000000-0000-0000-0000-000000000002";
    private static final CloudProfile PUBLIC = CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD);
    private static final CloudProfile US_GOV = CloudCatalog.forName("AzureUSGovernment").orElseThrow();
    private static final CloudProfile CHINA = CloudCatalog.forName("AzureChinaCloud").orElseThrow();

    private final ScopeResolver resolver = new ScopeResolver(() -> DEFAULT_TENANT, PUBLIC);

    @Test
    void armPathResolvesSubscriptionTenantAndCloud() {
        ScopeContext scope = resolver.resolve(
            "subscriptions/sub-1/resourceGroups/rg-1/providers/Microsoft.Storage/storageAccounts/a?api-version=2023-05-01",
            "localhost");

        assertEquals(ScopeSource.ARM_PATH, scope.source());
        assertEquals("sub-1", scope.subscriptionId());
        assertEquals("rg-1", scope.resourceGroup());
        assertEquals(DEFAULT_TENANT, scope.tenantId());
        assertEquals(PUBLIC, scope.cloud());
        assertNull(scope.tokenTenantId());
    }

    @Test
    void armPathWinsOverACloudHost() {
        // Signal 1 precedes signal 5: the subscription decides, not the host the client used.
        ScopeContext scope = resolver.resolve("subscriptions/s/resourcegroups/rg", "management.usgovcloudapi.net");

        assertEquals(ScopeSource.ARM_PATH, scope.source());
        assertEquals("rg", scope.resourceGroup());
        assertEquals(PUBLIC, scope.cloud());
    }

    @Test
    void subscriptionListNamesNoSubscription() {
        ScopeContext scope = resolver.resolve("subscriptions", "localhost");

        assertEquals(ScopeSource.DEFAULT, scope.source());
        assertNull(scope.subscriptionId());
    }

    @Test
    void entraPathResolvesItsTenant() {
        ScopeContext scope = resolver.resolve("tenant-a/oauth2/v2.0/token", "localhost");

        assertEquals(ScopeSource.ENTRA_PATH, scope.source());
        assertEquals("tenant-a", scope.tenantId());
        assertNull(scope.subscriptionId());
        assertEquals(PUBLIC, scope.cloud());
    }

    @Test
    void everyEntraEndpointCarriesItsTenant() {
        for (String path : new String[] {
            "t1/oauth2/token", "t1/oauth2/v2.0/authorize", "t1/discovery/v2.0/keys",
            "t1/v2.0/.well-known/openid-configuration", "t1/.well-known/openid-configuration"}) {
            ScopeContext scope = resolver.resolve(path, null);
            assertEquals(ScopeSource.ENTRA_PATH, scope.source(), path);
            assertEquals("t1", scope.tenantId(), path);
        }
    }

    @Test
    void entraAliasesFallThroughToTheLoginHost() {
        for (String alias : new String[] {"common", "organizations", "consumers", "Common"}) {
            ScopeContext scope = resolver.resolve(alias + "/oauth2/v2.0/token", "login.microsoftonline.us");
            assertEquals(ScopeSource.CLOUD_HOST, scope.source(), alias);
            assertEquals(US_GOV, scope.cloud(), alias);
            assertNull(scope.tenantId(), alias);
        }
    }

    @Test
    void entraAliasWithoutACloudHostFallsToTheDefault() {
        ScopeContext scope = resolver.resolve("organizations/oauth2/v2.0/token", "localhost");

        assertEquals(ScopeSource.DEFAULT, scope.source());
        assertEquals(PUBLIC, scope.cloud());
    }

    @Test
    void tenantlessEntraPathIsNotATenant() {
        ScopeContext scope = resolver.resolve(".well-known/openid-configuration", null);

        assertEquals(ScopeSource.DEFAULT, scope.source());
        assertNull(scope.tenantId());
    }

    @Test
    void realAzureHostSuffixResolvesItsCloud() {
        ScopeContext gov = resolver.resolve("container/blob", "acct.blob.core.usgovcloudapi.net");
        assertEquals(ScopeSource.HOST_SUFFIX, gov.source());
        assertEquals(US_GOV, gov.cloud());
        assertNull(gov.tenantId());

        ScopeContext china = resolver.resolve("secrets/s1", "myvault.vault.azure.cn");
        assertEquals(ScopeSource.HOST_SUFFIX, china.source());
        assertEquals(CHINA, china.cloud());

        ScopeContext publicCloud = resolver.resolve("", "acct.queue.core.windows.net");
        assertEquals(ScopeSource.HOST_SUFFIX, publicCloud.source());
        assertEquals(PUBLIC, publicCloud.cloud());
    }

    @Test
    void entraPathWinsOverAHostSuffix() {
        ScopeContext scope = resolver.resolve("t1/oauth2/v2.0/token", "acct.blob.core.chinacloudapi.cn");

        assertEquals(ScopeSource.ENTRA_PATH, scope.source());
    }

    @Test
    void pathStyleDataPlaneFallsToTheDefaultUntilNamesHaveOwners() {
        assertTrue(resolver.dataPlaneNameOwner("devstoreaccount1/container", "localhost").isEmpty());

        ScopeContext scope = resolver.resolve("devstoreaccount1/container/blob", "localhost");
        assertEquals(ScopeSource.DEFAULT, scope.source());
        assertEquals(PUBLIC, scope.cloud());
    }

    @Test
    void catalogArmAndLoginHostsResolveTheirCloud() {
        assertEquals(CHINA, resolver.resolve("metadata/endpoints", "management.chinacloudapi.cn").cloud());
        assertEquals(ScopeSource.CLOUD_HOST, resolver.resolve("metadata/endpoints", "management.chinacloudapi.cn").source());
        assertEquals(US_GOV, resolver.resolve("tenants", "management.usgovcloudapi.net").cloud());
        assertEquals(CHINA, resolver.resolve("common/oauth2/v2.0/token", "login.partner.microsoftonline.cn").cloud());
        assertEquals(PUBLIC, resolver.resolve("tenants", "management.azure.com").cloud());
        assertEquals(ScopeSource.CLOUD_HOST, resolver.resolve("tenants", "management.azure.com").source());
    }

    @Test
    void noSignalFallsToTheDefaultCloud() {
        ScopeContext scope = resolver.resolve("devstoreaccount1-queue/q1/messages", "localhost");

        assertEquals(ScopeSource.DEFAULT, scope.source());
        assertEquals(PUBLIC, scope.cloud());
        assertNull(scope.tenantId());
        assertNull(scope.subscriptionId());
        assertNull(scope.resourceGroup());
    }

    @Test
    void unmappedTenantsLiveInTheDefaultCloud() {
        ScopeResolver govDefault = new ScopeResolver(() -> DEFAULT_TENANT, US_GOV);

        assertEquals(US_GOV, govDefault.resolve("devstoreaccount1/c", "localhost").cloud());
        assertEquals(US_GOV, govDefault.resolve("subscriptions/s", "localhost").cloud());
    }

    @Test
    void nullAndEmptyInputsResolveToTheDefault() {
        assertEquals(ScopeSource.DEFAULT, resolver.resolve(null, null).source());
        assertEquals(ScopeSource.DEFAULT, resolver.resolve("", "").source());
    }

    @Test
    void entraPathPredicateMatchesTheRoutedEndpoints() {
        assertTrue(ScopeResolver.isEntraPath("t/oauth2/v2.0/token"));
        assertTrue(ScopeResolver.isEntraPath("t/oauth2/token"));
        assertFalse(ScopeResolver.isEntraPath("subscriptions/s"));
        assertFalse(ScopeResolver.isEntraPath("t/oauth2/v2.0/logout"));
    }
}
