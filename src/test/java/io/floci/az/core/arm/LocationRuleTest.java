package io.floci.az.core.arm;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.ScopeContext;
import io.floci.az.core.cloud.ScopeSource;
import io.floci.az.core.directory.ResourceGroups;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LocationRule: canonical locations and strict refusals")
class LocationRuleTest {

    private static final String SUB = "00000000-0000-0000-0000-0000000000d1";
    private static final String RG = "subscriptions/" + SUB + "/resourceGroups/rg-loc";
    private static final String ACCOUNT = RG + "/providers/Microsoft.Storage/storageAccounts/locacct";
    private static final String ZONE = RG + "/providers/Microsoft.Network/dnsZones/example.com";
    private static final String CACHE = RG + "/providers/Microsoft.Cache/redis/loccache";

    /** Storage regional, DNS zones global, and Redis declared here as inheriting its group's location. */
    private static final ArmTypeRegistry TYPES = new ArmTypeRegistry(Map.of(
        "microsoft.storage", new ArmSurface("Microsoft.Storage", ArmLane.ARM_HANDLER, List.of(
            ArmResourceType.resourceGroup("Microsoft.Storage", "storageAccounts", LocationPolicy.REQUIRED))),
        "microsoft.network", new ArmSurface("Microsoft.Network", ArmLane.ARM_HANDLER, List.of(
            ArmResourceType.resourceGroup("Microsoft.Network", "dnsZones", LocationPolicy.GLOBAL))),
        "microsoft.cache", new ArmSurface("Microsoft.Cache", ArmLane.ARM_HANDLER, List.of(
            ArmResourceType.resourceGroup("Microsoft.Cache", "redis", LocationPolicy.INHERITS_RG)))));

    /** A call carrying {@code json} as the body the gate buffered. */
    private static ArmCall call(String method, String path, String cloud, String json) {
        ArmResourceId id = ArmResourceId.parse(path).orElseThrow();
        ScopeContext scope = new ScopeContext(CloudCatalog.forNameOrPublic(cloud), null, id.subscriptionId(),
            id.resourceGroup(), null, ScopeSource.ARM_PATH);
        ArmRequestBody body = json == null ? null
            : ArmRequestBody.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        AzureRequest request = new AzureRequest(method, "arm", "arm", path, null, null,
            Map.of("api-version", "2023-01-01"), Map.of(), null, false, null, null, path, null);
        return new ArmCall(scope, id, TYPES.match(id).type(), method, null, body, request);
    }

    private static String text(ArmVerdict.Amend amend) {
        return new String(amend.call().body().bytes(), StandardCharsets.UTF_8);
    }

    private static LocationRule normalizing(ResourceGroups groups) {
        return new LocationRule(TYPES, groups, true, false);
    }

    @Test
    void aDisplayNameBecomesTheCanonicalNameInTheBodyAndTheCall() {
        ArmVerdict.Amend amend = assertInstanceOf(ArmVerdict.Amend.class, normalizing(new ResourceGroups(null))
            .enter(call("PUT", ACCOUNT, "AzureCloud", "{\"location\":\"East US 2\",\"kind\":\"StorageV2\"}")));

        assertEquals("eastus2", amend.call().normalizedLocation());
        assertEquals("{\"location\":\"eastus2\",\"kind\":\"StorageV2\"}", text(amend));
    }

    @Test
    void anAlreadyCanonicalLocationOnlyFillsTheCall() {
        ArmVerdict.Amend amend = assertInstanceOf(ArmVerdict.Amend.class, normalizing(new ResourceGroups(null))
            .enter(call("PATCH", ACCOUNT, "AzureCloud", "{\"location\":\"westeurope\"}")));

        assertEquals("westeurope", amend.call().normalizedLocation());
        assertEquals("{\"location\":\"westeurope\"}", text(amend), "an unchanged body is not rewritten");
    }

    @Test
    void aGlobalTypeIsAlwaysGlobalOnPut() {
        LocationRule rule = normalizing(new ResourceGroups(null));

        ArmVerdict.Amend sent = assertInstanceOf(ArmVerdict.Amend.class,
            rule.enter(call("PUT", ZONE, "AzureCloud", "{\"location\":\"Global\"}")));
        ArmVerdict.Amend absent = assertInstanceOf(ArmVerdict.Amend.class,
            rule.enter(call("PUT", ZONE, "AzureCloud", "{\"tags\":{}}")));

        assertEquals("{\"location\":\"global\"}", text(sent));
        assertEquals("{\"tags\":{},\"location\":\"global\"}", text(absent));
    }

    @Test
    void anInheritingPutWithoutALocationGetsItsGroupsLocation() {
        ResourceGroups groups = new ResourceGroups(null);
        groups.createIfAbsent(SUB, "RG-LOC", "North Europe");

        ArmVerdict.Amend amend = assertInstanceOf(ArmVerdict.Amend.class,
            normalizing(groups).enter(call("PUT", CACHE, "AzureCloud", "{\"properties\":{}}")));

        assertEquals("northeurope", amend.call().normalizedLocation());
        assertEquals("{\"properties\":{},\"location\":\"northeurope\"}", text(amend));
    }

    @Test
    void theSubscriptionsCloudDecidesTheCanonicalName() {
        ArmVerdict.Amend amend = assertInstanceOf(ArmVerdict.Amend.class, normalizing(new ResourceGroups(null))
            .enter(call("PUT", ACCOUNT, "AzureUSGovernment", "{\"location\":\"US Gov Virginia\"}")));

        assertEquals("usgovvirginia", amend.call().normalizedLocation());
    }

    @Test
    void offByDefaultTheBodyIsNeitherReadNorRewritten() {
        LocationRule rule = new LocationRule(TYPES, new ResourceGroups(null), false, false);

        assertEquals(false, rule.readsBody());
        assertEquals(ArmVerdict.proceed(), rule.enter(call("PUT", ACCOUNT, "AzureCloud", "{\"location\":\"East US\"}")));
    }

    @Test
    void readsAndNonJsonBodiesPassUntouched() {
        LocationRule rule = normalizing(new ResourceGroups(null));

        assertEquals(ArmVerdict.proceed(), rule.enter(call("GET", ACCOUNT, "AzureCloud", null)));
        assertEquals(ArmVerdict.proceed(), rule.enter(call("PUT", ACCOUNT, "AzureCloud", "not json")));
        assertEquals(ArmVerdict.proceed(), rule.enter(call("PUT", ACCOUNT, "AzureCloud", "[1]")));
    }

    @Test
    void strictRefusesALocationTheCloudDoesNotOfferWithAzuresBody() {
        LocationRule rule = new LocationRule(TYPES, new ResourceGroups(null), false, true);

        Response response = assertInstanceOf(ArmVerdict.Respond.class,
            rule.enter(call("PUT", ACCOUNT, "AzureUSGovernment", "{\"location\":\"East US\"}"))).response();

        assertEquals(400, response.getStatus());
        Map<?, ?> error = (Map<?, ?>) ((Map<?, ?>) response.getEntity()).get("error");
        assertEquals("NoRegisteredProviderFound", error.get("code"));
        String message = (String) error.get("message");
        assertTrue(message.startsWith("No registered resource provider found for location 'eastus' and API version "
            + "'2023-01-01' for type 'storageAccounts'. The supported api-versions are '"), message);
        assertTrue(message.endsWith("The supported locations are 'usgovvirginia, usgoviowa, usgovarizona, "
            + "usgovtexas, usdodeast, usdodcentral'."), message);
    }

    @Test
    void strictAcceptsTheCloudsLocationsGlobalAndGlobalTypes() {
        LocationRule rule = new LocationRule(TYPES, new ResourceGroups(null), false, true);

        assertEquals(ArmVerdict.proceed(),
            rule.enter(call("PUT", ACCOUNT, "AzureCloud", "{\"location\":\"East US\"}")));
        assertEquals(ArmVerdict.proceed(),
            rule.enter(call("PUT", ACCOUNT, "AzureCloud", "{\"location\":\"global\"}")));
        assertEquals(ArmVerdict.proceed(),
            rule.enter(call("PUT", ZONE, "AzureUSGovernment", "{\"location\":\"anywhere\"}")));
        assertEquals(ArmVerdict.proceed(),
            rule.enter(call("PATCH", ACCOUNT, "AzureUSGovernment", "{\"location\":\"East US\"}")));
    }
}
