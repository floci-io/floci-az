package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two subscriptions creating the same resource-group and resource name.
 *
 * <p>Resource-group-scoped names give each subscription its own resource. Global names (DNS names such as
 * {@code {name}.blob.core.windows.net}) belong to whoever created them first: the second create is refused
 * with that provider's conflict error, the second scope cannot see or delete the owner's resource, and the
 * name becomes free again once the owner deletes it.
 */
@QuarkusTest
@TestProfile(CrossSubscriptionIsolationTest.NoDocker.class)
@DisplayName("Same resource names in two subscriptions")
class CrossSubscriptionIsolationTest {

    private static final String SUB_A = "aaaaaaaa-1111-1111-1111-111111111111";
    private static final String SUB_B = "bbbbbbbb-2222-2222-2222-222222222222";
    private static final String LOC = "{\"location\":\"eastus\"}";
    private static final String SQL_ADMIN = "\"administratorLogin\":\"a\",\"administratorLoginPassword\":\"P@ssw0rd1234\"";

    /** Every backing service in ARM-state-only mode, so no case needs Docker. */
    public static class NoDocker implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-az.services.redis.mocked", "true",
                    "floci-az.services.acr.mocked", "true",
                    "floci-az.services.postgres.mocked", "true",
                    "floci-az.services.mysql.mocked", "true",
                    "floci-az.services.maria-db.mocked", "true",
                    "floci-az.services.aks.mocked", "true",
                    "floci-az.services.sql.data-plane.provider", "none");
        }
    }

    record Kind(String label, String type, String name, String api, String body) {
        String path(String sub, String rg) {
            return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/" + type + "/" + name;
        }

        String url(String sub, String rg) {
            return path(sub, rg) + "?api-version=" + api;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    record GlobalKind(Kind kind, int conflictStatus, String conflictCode) {
        @Override
        public String toString() {
            return kind.label();
        }
    }

    static List<Kind> resourceGroupScoped() {
        return List.of(
                new Kind("user-assigned identity", "Microsoft.ManagedIdentity/userAssignedIdentities", "xsub-id", "2023-01-31", LOC),
                new Kind("virtual network", "Microsoft.Network/virtualNetworks", "xsub-vnet", "2023-09-01",
                        "{\"location\":\"eastus\",\"properties\":{\"addressSpace\":{\"addressPrefixes\":[\"10.0.0.0/16\"]}}}"),
                new Kind("network security group", "Microsoft.Network/networkSecurityGroups", "xsub-nsg", "2023-09-01", LOC),
                new Kind("public IP", "Microsoft.Network/publicIPAddresses", "xsub-pip", "2023-09-01", LOC),
                new Kind("DNS zone", "Microsoft.Network/dnsZones", "xsub.example.com", "2018-05-01", "{\"location\":\"global\"}"),
                new Kind("private DNS zone", "Microsoft.Network/privateDnsZones", "xsub.internal", "2020-06-01", "{\"location\":\"global\"}"),
                new Kind("Event Grid topic", "Microsoft.EventGrid/topics", "xsub-topic", "2022-06-15", LOC),
                new Kind("Log Analytics workspace", "Microsoft.OperationalInsights/workspaces", "xsub-law", "2022-10-01", LOC),
                new Kind("AKS cluster", "Microsoft.ContainerService/managedClusters", "xsub-aks", "2024-02-01",
                        "{\"location\":\"eastus\",\"properties\":{\"dnsPrefix\":\"xsub\",\"agentPoolProfiles\":"
                                + "[{\"name\":\"pool\",\"count\":1,\"vmSize\":\"Standard_B2s\",\"mode\":\"System\"}]}}"),
                new Kind("container group", "Microsoft.ContainerInstance/containerGroups", "xsub-cg", "2023-05-01",
                        "{\"location\":\"eastus\",\"properties\":{\"osType\":\"Linux\",\"containers\":[{\"name\":\"c\","
                                + "\"properties\":{\"image\":\"nginx\",\"resources\":{\"requests\":{\"cpu\":1,\"memoryInGB\":1}}}}]}}"),
                new Kind("Container Apps environment", "Microsoft.App/managedEnvironments", "xsub-env", "2024-03-01", LOC),
                new Kind("email service", "Microsoft.Communication/emailServices", "xsub-email", "2023-04-01",
                        "{\"location\":\"global\",\"properties\":{\"dataLocation\":\"United States\"}}"));
    }

    static List<GlobalKind> globallyNamed() {
        return List.of(
                new GlobalKind(new Kind("storage account", "Microsoft.Storage/storageAccounts", "xsubsa", "2023-01-01",
                        "{\"location\":\"eastus\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}}"),
                        409, "StorageAccountAlreadyTaken"),
                new GlobalKind(new Kind("key vault", "Microsoft.KeyVault/vaults", "xsub-kv", "2023-07-01",
                        "{\"location\":\"eastus\",\"properties\":{\"tenantId\":\"00000000-0000-0000-0000-000000000002\","
                                + "\"sku\":{\"family\":\"A\",\"name\":\"standard\"}}}"),
                        409, "VaultAlreadyExists"),
                new GlobalKind(new Kind("managed HSM", "Microsoft.KeyVault/managedHSMs", "xsub-hsm", "2023-07-01",
                        "{\"location\":\"eastus\",\"sku\":{\"family\":\"B\",\"name\":\"Standard_B1\"},"
                                + "\"properties\":{\"initialAdminObjectIds\":[\"o\"]}}"),
                        409, "Conflict"),
                new GlobalKind(new Kind("container registry", "Microsoft.ContainerRegistry/registries", "xsubacr", "2023-07-01",
                        "{\"location\":\"eastus\",\"sku\":{\"name\":\"Basic\"}}"),
                        409, "AlreadyInUse"),
                new GlobalKind(new Kind("Redis cache", "Microsoft.Cache/redis", "xsub-redis", "2023-08-01",
                        "{\"location\":\"eastus\",\"properties\":{\"sku\":{\"name\":\"Basic\",\"family\":\"C\",\"capacity\":0}}}"),
                        409, "NameNotAvailable"),
                new GlobalKind(new Kind("SQL server", "Microsoft.Sql/servers", "xsub-sql", "2021-11-01",
                        "{\"location\":\"eastus\",\"properties\":{" + SQL_ADMIN + "}}"),
                        400, "NameAlreadyExists"),
                new GlobalKind(new Kind("PostgreSQL flexible server", "Microsoft.DBforPostgreSQL/flexibleServers", "xsub-pg", "2024-08-01",
                        "{\"location\":\"eastus\",\"sku\":{\"name\":\"Standard_B1ms\",\"tier\":\"Burstable\"},"
                                + "\"properties\":{" + SQL_ADMIN + ",\"version\":\"16\"}}"),
                        409, "ServerNameAlreadyExists"),
                new GlobalKind(new Kind("MySQL flexible server", "Microsoft.DBforMySQL/flexibleServers", "xsub-my", "2023-12-30",
                        "{\"location\":\"eastus\",\"sku\":{\"name\":\"Standard_B1ms\",\"tier\":\"Burstable\"},"
                                + "\"properties\":{" + SQL_ADMIN + ",\"version\":\"8.0.21\"}}"),
                        409, "ServerNameAlreadyExists"),
                new GlobalKind(new Kind("MariaDB server", "Microsoft.DBforMariaDB/servers", "xsub-maria", "2018-06-01",
                        "{\"location\":\"eastus\",\"properties\":{" + SQL_ADMIN + ",\"createMode\":\"Default\"}}"),
                        409, "ServerNameAlreadyExists"),
                new GlobalKind(new Kind("API Management service", "Microsoft.ApiManagement/service", "xsub-apim", "2022-08-01",
                        "{\"location\":\"eastus\",\"sku\":{\"name\":\"Developer\",\"capacity\":1},"
                                + "\"properties\":{\"publisherEmail\":\"a@b.c\",\"publisherName\":\"x\"}}"),
                        409, "ServiceAlreadyExists"),
                new GlobalKind(new Kind("communication service", "Microsoft.Communication/communicationServices", "xsub-comm", "2023-04-01",
                        "{\"location\":\"global\",\"properties\":{\"dataLocation\":\"United States\"}}"),
                        409, "Conflict"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resourceGroupScoped")
    void eachSubscriptionGetsItsOwnResourceForAResourceGroupScopedName(Kind kind) {
        String rg = "rg-" + kind.name();
        createGroup(SUB_A, rg);
        createGroup(SUB_B, rg);

        assertCreated(put(kind, SUB_A, rg), kind);
        assertCreated(put(kind, SUB_B, rg), kind);

        assertEquals(kind.path(SUB_A, rg).toLowerCase(), idOf(kind, SUB_A, rg));
        assertEquals(kind.path(SUB_B, rg).toLowerCase(), idOf(kind, SUB_B, rg));

        given().delete(kind.url(SUB_A, rg));
        given().get(kind.url(SUB_B, rg)).then().statusCode(200);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("globallyNamed")
    void aGlobalNameBelongsToTheFirstSubscriptionThatCreatesIt(GlobalKind global) {
        Kind kind = global.kind();
        String rg = "rg-" + kind.name();
        createGroup(SUB_A, rg);
        createGroup(SUB_B, rg);

        assertCreated(put(kind, SUB_A, rg), kind);

        put(kind, SUB_B, rg).then()
                .statusCode(global.conflictStatus())
                .body("error.code", equalTo(global.conflictCode()));

        given().get(kind.url(SUB_B, rg)).then().statusCode(404);
        given().delete(kind.url(SUB_B, rg));
        assertEquals(kind.path(SUB_A, rg).toLowerCase(), idOf(kind, SUB_A, rg));

        given().delete(kind.url(SUB_A, rg));
        awaitGone(kind, SUB_A, rg);
        assertCreated(put(kind, SUB_B, rg), kind);
        assertEquals(kind.path(SUB_B, rg).toLowerCase(), idOf(kind, SUB_B, rg));
    }

    @Test
    void aStorageAccountNameInAnotherResourceGroupOfTheSameSubscriptionIsRefused() {
        Kind storage = globallyNamed().getFirst().kind();
        Kind sameName = new Kind(storage.label(), storage.type(), "xsubsamesub", storage.api(), storage.body());
        createGroup(SUB_A, "rg-one");
        createGroup(SUB_A, "rg-two");

        assertCreated(put(sameName, SUB_A, "rg-one"), sameName);
        put(sameName, SUB_A, "rg-two").then()
                .statusCode(409)
                .body("error.code", equalTo("StorageAccountInAnotherResourceGroup"));
    }

    @Test
    void aTakenStorageAccountNameIsReportedUnavailable() {
        Kind storage = new Kind("storage account", "Microsoft.Storage/storageAccounts", "xsubnamecheck", "2023-01-01",
                globallyNamed().getFirst().kind().body());
        createGroup(SUB_A, "rg-namecheck");
        assertCreated(put(storage, SUB_A, "rg-namecheck"), storage);

        given().contentType("application/json")
                .body("{\"name\":\"xsubnamecheck\",\"type\":\"Microsoft.Storage/storageAccounts\"}")
                .post("/subscriptions/" + SUB_B + "/providers/Microsoft.Storage/checkNameAvailability?api-version=2023-01-01")
                .then().statusCode(200)
                .body("nameAvailable", equalTo(false))
                .body("reason", equalTo("AlreadyExists"));
    }

    @Test
    void aWebAppNameTakenInAnotherSubscriptionIsRefusedInTheAppServiceShape() {
        Kind site = new Kind("web app", "Microsoft.Web/sites", "xsub-site", "2023-01-01",
                "{\"location\":\"eastus\",\"kind\":\"app\",\"properties\":{}}");
        createGroup(SUB_A, "rg-site");
        createGroup(SUB_B, "rg-site");

        assertCreated(put(site, SUB_A, "rg-site"), site);
        put(site, SUB_B, "rg-site").then()
                .statusCode(409)
                .body("Code", equalTo("Conflict"))
                .body("ErrorEntity.ExtendedCode", equalTo("54001"));
    }

    @Test
    void anEmailServiceListOnlyShowsTheSubscriptionsOwnServices() {
        Kind email = resourceGroupScoped().getLast();
        createGroup(SUB_A, "rg-email-list");
        assertCreated(put(email, SUB_A, "rg-email-list"), email);

        given().get("/subscriptions/" + SUB_B + "/providers/Microsoft.Communication/emailServices?api-version=2023-04-01")
                .then().statusCode(200)
                .body("value.id", not(hasItem(email.path(SUB_A, "rg-email-list"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("globallyNamed")
    void aPathWithoutAResourceGroupCannotReachAnotherSubscriptionsResource(GlobalKind global) {
        Kind kind = global.kind();
        String rg = "rg-norg-" + kind.name();
        createGroup(SUB_A, rg);
        Kind named = new Kind(kind.label(), kind.type(), kind.name() + "norg", kind.api(), kind.body());
        assertCreated(put(named, SUB_A, rg), named);

        String noGroup = "/subscriptions/" + SUB_B + "/providers/" + named.type() + "/" + named.name()
                + "?api-version=" + named.api();
        given().get(noGroup).then().statusCode(not(equalTo(200)));
        given().delete(noGroup);
        assertEquals(named.path(SUB_A, rg).toLowerCase(), idOf(named, SUB_A, rg));
    }

    @Test
    void storageChildRoutesUnderAnotherSubscriptionCannotReachTheOwnersAccount() {
        Kind storage = new Kind("storage account", "Microsoft.Storage/storageAccounts", "xsubchild", "2023-01-01",
                globallyNamed().getFirst().kind().body());
        createGroup(SUB_A, "rg-child");
        createGroup(SUB_B, "rg-child");
        assertCreated(put(storage, SUB_A, "rg-child"), storage);

        String foreignAccount = storage.path(SUB_B, "rg-child");
        given().contentType("application/json").body("{}")
                .put(foreignAccount + "/blobServices/default/containers/intruder?api-version=2023-01-01")
                .then().statusCode(404);
        given().contentType("application/json").body("{}")
                .put(foreignAccount + "/queueServices/default/queues/intruder?api-version=2023-01-01")
                .then().statusCode(404);
        given().post(foreignAccount + "/listKeys?api-version=2023-01-01").then().statusCode(404);

        // The owner's data plane never received the container.
        given().get("/xsubchild/intruder?restype=container").then().statusCode(404);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("globallyNamed")
    void concurrentCreatesFromManySubscriptionsLeaveOneOwner(GlobalKind global) throws Exception {
        Kind kind = global.kind();
        Kind raced = new Kind(kind.label(), kind.type(), kind.name() + "race", kind.api(), kind.body());
        int contenders = 8;
        List<String> subs = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String sub = String.format("cccccccc-0000-0000-0000-%012d", i);
            subs.add(sub);
            createGroup(sub, "rg-race");
        }

        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String sub : subs) {
                results.add(pool.submit(() -> {
                    start.await();
                    return put(raced, sub, "rg-race").statusCode();
                }));
            }
            start.countDown();
            int created = 0;
            for (Future<Integer> result : results) {
                int status = result.get(30, TimeUnit.SECONDS);
                if (status >= 200 && status < 300) {
                    created++;
                }
            }
            assertEquals(1, created, raced.label() + " was created by more than one subscription");
        } finally {
            pool.shutdownNow();
        }

        long owners = subs.stream().filter(sub -> given().get(raced.url(sub, "rg-race")).statusCode() == 200).count();
        assertEquals(1, owners, raced.label() + " is readable from more than one subscription");
    }

    static List<Kind> flexibleServers() {
        return globallyNamed().stream()
                .map(GlobalKind::kind)
                .filter(kind -> kind.type().startsWith("Microsoft.DBfor"))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("flexibleServers")
    void concurrentCreatesInOneScopeEachApplyTheirOwnRequest(Kind kind) throws Exception {
        Kind raced = new Kind(kind.label(), kind.type(), kind.name() + "same", kind.api(), kind.body());
        createGroup(SUB_A, "rg-same");
        int contenders = 8;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Response>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                String tagged = raced.body().replaceFirst("^\\{", "{\"tags\":{\"request\":\"r" + i + "\"},");
                results.add(pool.submit(() -> {
                    start.await();
                    return given().contentType("application/json").body(tagged).put(raced.url(SUB_A, "rg-same"));
                }));
            }
            start.countDown();
            for (int i = 0; i < contenders; i++) {
                Response response = results.get(i).get(30, TimeUnit.SECONDS);
                int status = response.statusCode();
                assertTrue(status >= 200 && status < 300, raced.label() + " create returned " + status);
                assertEquals("r" + i, response.jsonPath().getString("tags.request"),
                        raced.label() + " reported success without applying request r" + i);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anEmailServicePathWithoutAResourceGroupIsNotFound() {
        given().get("/subscriptions/" + SUB_B + "/providers/Microsoft.Communication/emailServices/xsub-email-norg"
                        + "?api-version=2023-04-01")
                .then().statusCode(404);
        given().get("/subscriptions/" + SUB_B + "/providers/Microsoft.Communication/emailServices/xsub-email-norg"
                        + "/domains/example.com?api-version=2023-04-01")
                .then().statusCode(404);
    }

    private static void createGroup(String sub, String rg) {
        given().contentType("application/json").body(LOC)
                .put("/subscriptions/" + sub + "/resourceGroups/" + rg + "?api-version=2021-04-01")
                .then().statusCode(201);
    }

    private static Response put(Kind kind, String sub, String rg) {
        return given().contentType("application/json").body(kind.body()).put(kind.url(sub, rg));
    }

    private static void assertCreated(Response response, Kind kind) {
        int status = response.statusCode();
        assertTrue(status >= 200 && status < 300,
                () -> kind.label() + " create returned " + status + ": " + response.asString());
    }

    private static String idOf(Kind kind, String sub, String rg) {
        Response response = given().get(kind.url(sub, rg));
        assertEquals(200, response.statusCode(), () -> kind.label() + " GET: " + response.asString());
        return response.jsonPath().getString("id").toLowerCase();
    }

    /** Some providers delete asynchronously (202); poll until the resource reads as gone. */
    private static void awaitGone(Kind kind, String sub, String rg) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (given().get(kind.url(sub, rg)).statusCode() != 404) {
            assertTrue(System.currentTimeMillis() < deadline, () -> kind.label() + " was never deleted");
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for " + kind.label() + " to be deleted", e);
            }
        }
    }
}
