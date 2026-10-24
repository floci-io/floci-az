package io.floci.az.core.arm;

import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudSuffix;
import io.floci.az.core.cloud.ScopeContext;
import io.floci.az.core.cloud.ScopeSource;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GlobalNameRule and ResourceNameRule: global names and name syntax in the gate")
class GlobalNameRuleTest {

    private static final GlobalName CACHE = new GlobalName(CloudSuffix.REDIS, 409, "NameNotAvailable",
        "%s is taken.");
    private static final GlobalName ACCOUNT = new GlobalName(CloudSuffix.BLOB, 409, "StorageAccountAlreadyTaken",
        "The storage account named %s is already taken.", "StorageAccountInAnotherResourceGroup",
        "The account %s is already in another resource group in this subscription.");
    private static final GlobalName SITE = new GlobalName(CloudSuffix.WEB, 409, "Conflict", "%s exists.")
        .answeredBy(name -> Response.status(409).entity(Map.of("Code", "Conflict", "Name", name)).build());

    private static final ArmTypeRegistry TYPES = new ArmTypeRegistry(Map.of(
        "microsoft.cache", new ArmSurface("Microsoft.Cache", ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup("Microsoft.Cache", "redis", LocationPolicy.REQUIRED)
                .withName(NameRule.REDIS).withGlobalName(CACHE))),
        "microsoft.storage", new ArmSurface("Microsoft.Storage", ArmLane.ARM_HANDLER, List.of(
            ArmResourceType.resourceGroup("Microsoft.Storage", "storageAccounts", LocationPolicy.REQUIRED)
                .withGlobalName(ACCOUNT))),
        "microsoft.web", new ArmSurface("Microsoft.Web", ArmLane.PROVIDER_SERVICE, List.of(
            ArmResourceType.resourceGroup("Microsoft.Web", "sites", LocationPolicy.REQUIRED)
                .withGlobalName(SITE)))));

    private final List<GlobalNameSource.Claim> stored = new ArrayList<>();
    private final GlobalNameRegistry names = new GlobalNameRegistry(() -> List.of(() -> List.copyOf(stored)),
        subscription -> CloudCatalog.forNameOrPublic("AzureCloud"), () -> Set.of(CloudSuffix.REDIS));
    private final GlobalNameRule rule = new GlobalNameRule(TYPES, names);

    private static ArmCall call(String method, String path) {
        ArmResourceId id = ArmResourceId.parse(path).orElseThrow();
        ScopeContext scope = new ScopeContext(CloudCatalog.forNameOrPublic("AzureCloud"), null, id.subscriptionId(),
            id.resourceGroup(), null, ScopeSource.ARM_PATH);
        return new ArmCall(scope, id, TYPES.match(id) == null ? null : TYPES.match(id).type(), method, null);
    }

    private static String cache(String sub, String rg, String name) {
        return "subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Cache/Redis/" + name;
    }

    private void created(ArmCall call) {
        assertEquals(ArmVerdict.proceed(), rule.enter(call));
        rule.exit(call, Response.status(201).build());
    }

    @Test
    void aNameAnotherResourceOwnsIsTheTypesConflict() {
        created(call("PUT", cache("s1", "rg", "c1")));

        Response response = assertInstanceOf(ArmVerdict.Respond.class,
            rule.enter(call("PUT", cache("s2", "rg", "C1")))).response();
        assertEquals(409, response.getStatus());
        assertEquals("NameNotAvailable", ((Map<?, ?>) ((Map<?, ?>) response.getEntity()).get("error")).get("code"));
        assertEquals(ArmVerdict.proceed(), rule.enter(call("PUT", cache("S1", "RG", "c1"))), "the owner updates");
    }

    @Test
    void theSameSubscriptionVariantAnswersWhenTheTypeDeclaresOne() {
        String account = "subscriptions/s1/resourceGroups/rg-a/providers/Microsoft.Storage/storageAccounts/acct1";
        created(call("PUT", account));

        Response sameSubscription = assertInstanceOf(ArmVerdict.Respond.class,
            rule.enter(call("PUT", account.replace("rg-a", "rg-b")))).response();
        assertEquals("StorageAccountInAnotherResourceGroup",
            ((Map<?, ?>) ((Map<?, ?>) sameSubscription.getEntity()).get("error")).get("code"));
        Response otherSubscription = assertInstanceOf(ArmVerdict.Respond.class,
            rule.enter(call("PUT", account.replace("s1", "s2")))).response();
        assertEquals("StorageAccountAlreadyTaken",
            ((Map<?, ?>) ((Map<?, ?>) otherSubscription.getEntity()).get("error")).get("code"));
    }

    @Test
    void aTypeWithAConflictResponderAnswersInItsOwnShape() {
        String site = "subscriptions/s1/resourceGroups/rg/providers/Microsoft.Web/sites/site1";
        created(call("PUT", site));

        Response response = assertInstanceOf(ArmVerdict.Respond.class,
            rule.enter(call("PUT", site.replace("s1", "s2").replace("site1", "Site1")))).response();
        assertEquals(409, response.getStatus());
        assertEquals(Map.of("Code", "Conflict", "Name", "Site1"), response.getEntity());
        assertSame(SITE.conflictResponder(), SITE.ownedPerCloud().conflictResponder(), "per cloud keeps the responder");
        assertNull(CACHE.conflictResponder(), "a type without one answers the ARM error");
    }

    @Test
    void aFailedCreateReleasesItsNameAndADeleteFreesIt() {
        ArmCall failed = call("PUT", cache("s1", "rg", "c1"));
        rule.enter(failed);
        rule.exit(failed, Response.status(400).build());
        created(call("PUT", cache("s2", "rg", "c1")));

        ArmCall delete = call("DELETE", cache("s2", "rg", "c1"));
        rule.enter(delete);
        rule.exit(delete, Response.status(202).build());
        created(call("PUT", cache("s3", "rg", "c1")));
    }

    @Test
    void aConcurrentPutToTheSameCacheFailingKeepsTheNameTheFirstPutIsCreating() {
        ArmCall first = call("PUT", cache("s1", "rg", "c1"));
        ArmCall second = call("PUT", cache("s1", "rg", "c1"));
        assertEquals(ArmVerdict.proceed(), rule.enter(first));
        assertEquals(ArmVerdict.proceed(), rule.enter(second));

        rule.exit(second, Response.status(400).build());

        assertInstanceOf(ArmVerdict.Respond.class, rule.enter(call("PUT", cache("s2", "rg", "c1"))),
            "the first PUT still holds the name");
        rule.exit(first, Response.status(201).build());
        assertEquals(first.id().canonicalKey(),
            names.owner(CloudCatalog.forNameOrPublic("AzureCloud"), CloudSuffix.REDIS, "c1").orElseThrow()
                .canonicalKey());
    }

    @Test
    void aDeleteFinishingAfterAPutRecreatedTheCacheKeepsItsName() {
        ArmCall create = call("PUT", cache("s1", "rg", "c1"));
        GlobalNameSource.Claim claim = new GlobalNameSource.Claim(CloudSuffix.REDIS, "c1", create.id());
        rule.enter(create);
        stored.add(claim);
        rule.exit(create, Response.status(201).build());

        ArmCall delete = call("DELETE", cache("s1", "rg", "c1"));
        rule.enter(delete);
        stored.remove(claim);
        ArmCall recreate = call("PUT", cache("s1", "rg", "c1"));
        assertEquals(ArmVerdict.proceed(), rule.enter(recreate), "the owner may PUT while its DELETE runs");
        stored.add(claim);
        rule.exit(recreate, Response.status(201).build());
        rule.exit(delete, Response.status(202).build());

        assertInstanceOf(ArmVerdict.Respond.class, rule.enter(call("PUT", cache("s2", "rg", "c1"))),
            "the recreated cache still owns its name");
    }

    /** Stores {@code c1} in {@code s1} through the rule, then runs its DELETE's entry and store removal. */
    private ArmCall deleting(GlobalNameSource.Claim claim) {
        ArmCall create = call("PUT", claim.owner().id());
        rule.enter(create);
        stored.add(claim);
        rule.exit(create, Response.status(201).build());
        ArmCall delete = call("DELETE", claim.owner().id());
        rule.enter(delete);
        stored.remove(claim);
        return delete;
    }

    private GlobalNameSource.Claim claimOfC1() {
        return new GlobalNameSource.Claim(CloudSuffix.REDIS, "c1",
            ArmResourceId.parse(cache("s1", "rg", "c1")).orElseThrow());
    }

    @Test
    void aDeleteFinishingBeforeAnInFlightPutStoresTheCacheLeavesItsNameToThatPut() {
        GlobalNameSource.Claim claim = claimOfC1();
        ArmCall delete = deleting(claim);
        ArmCall recreate = call("PUT", cache("s1", "rg", "c1"));
        assertEquals(ArmVerdict.proceed(), rule.enter(recreate), "the owner may PUT while its DELETE runs");

        rule.exit(delete, Response.status(202).build());
        Response refused = assertInstanceOf(ArmVerdict.Respond.class,
            rule.enter(call("PUT", cache("s2", "rg", "c1"))),
            "the name stays held while the PUT that found it owned is in flight").response();
        assertEquals("NameNotAvailable", ((Map<?, ?>) ((Map<?, ?>) refused.getEntity()).get("error")).get("code"));
        stored.add(claim);
        rule.exit(recreate, Response.status(201).build());

        assertEquals(recreate.id().canonicalKey(),
            names.owner(CloudCatalog.forNameOrPublic("AzureCloud"), CloudSuffix.REDIS, "c1").orElseThrow()
                .canonicalKey());
        assertInstanceOf(ArmVerdict.Respond.class, rule.enter(call("PUT", cache("s2", "rg", "c1"))),
            "the recreated cache still owns its name");
    }

    @Test
    void aDeferredReleaseFreesTheNameWhenTheInFlightPutFails() {
        ArmCall delete = deleting(claimOfC1());
        ArmCall recreate = call("PUT", cache("s1", "rg", "c1"));
        rule.enter(recreate);

        rule.exit(delete, Response.status(202).build());
        rule.exit(recreate, Response.status(400).build());

        created(call("PUT", cache("s2", "rg", "c1")));
    }

    @Test
    void aDeleteWithNoPutInFlightFreesTheNameAtOnceAfterPutsThatFailedOrThrew() {
        ArmCall delete = deleting(claimOfC1());
        ArmCall refused = call("PUT", cache("s1", "rg", "c1"));
        ArmCall threw = call("PUT", cache("s1", "rg", "c1"));
        rule.enter(refused);
        rule.enter(threw);
        rule.exit(refused, Response.status(409).build());
        rule.exit(threw, null);

        rule.exit(delete, Response.status(202).build());

        created(call("PUT", cache("s2", "rg", "c1")));
    }

    @Test
    void readsActionsAndPathsWithoutAGroupPass() {
        created(call("PUT", cache("s1", "rg", "c1")));

        assertEquals(ArmVerdict.proceed(), rule.enter(call("GET", cache("s2", "rg", "c1"))));
        assertEquals(ArmVerdict.proceed(), rule.enter(call("POST", cache("s2", "rg", "c1") + "/listKeys")));
        assertEquals(ArmVerdict.proceed(),
            rule.enter(call("PUT", "subscriptions/s2/providers/Microsoft.Cache/Redis/c1")));
    }

    @Test
    void underStrictNamesABadNameIsRefusedAndOtherwiseEveryNamePasses() {
        ArmCall bad = call("PUT", cache("s1", "rg", "bad--name"));

        Response refused = assertInstanceOf(ArmVerdict.Respond.class,
            new ResourceNameRule(TYPES, true).enter(bad)).response();
        assertEquals(400, refused.getStatus());
        assertEquals("InvalidResourceName",
            ((Map<?, ?>) ((Map<?, ?>) refused.getEntity()).get("error")).get("code"));
        assertEquals(ArmVerdict.proceed(), new ResourceNameRule(TYPES, true).enter(call("PUT", cache("s1", "rg", "ok-1"))));
        assertEquals(ArmVerdict.proceed(), new ResourceNameRule(TYPES, false).enter(bad));
        assertTrue(NameRule.REDIS.accepts("a") && !NameRule.REDIS.accepts("-a") && !NameRule.REDIS.accepts("a-"));
    }
}
