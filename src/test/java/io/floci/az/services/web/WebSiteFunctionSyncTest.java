package io.floci.az.services.web;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.GlobalNameRegistry;
import io.floci.az.core.arm.GlobalNameRule;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.cloud.CloudSuffix;
import io.floci.az.core.cloud.ScopeContext;
import io.floci.az.core.cloud.ScopeSource;
import io.floci.az.services.functions.FunctionsServiceHandler;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@QuarkusTest
@DisplayName("Web ARM: a site write and its Function App sync are one step")
class WebSiteFunctionSyncTest {

    private static final String API = "?api-version=2023-01-01";
    private static final String SITE = "/subscriptions/sub-sync/resourceGroups/rg/providers/Microsoft.Web/sites/syncsite";
    private static final String PYTHON = "Python|3.12";
    private static final String NODE = "Node|20";

    private static final CloudProfile CLOUD = CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD);

    @InjectSpy
    FunctionsServiceHandler functions;

    @Inject
    WebArmService web;

    @Inject
    GlobalNameRegistry names;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static int putSite(String linuxFxVersion) {
        return putSite(SITE, linuxFxVersion);
    }

    private static int putSite(String site, String linuxFxVersion) {
        return given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{\"siteConfig\":{\"linuxFxVersion\":\"" + linuxFxVersion
                + "\"}}}")
            .when().put(site + API).then().extract().statusCode();
    }

    /** Waits up to half a second for {@code call}: long enough to finish when nothing blocks it. */
    private static void settle(Future<Integer> call) throws Exception {
        try {
            call.get(500, TimeUnit.MILLISECONDS);
        } catch (TimeoutException expected) {
            // The call waits for the paused PUT's lock; it completes once that is released.
        }
    }

    /**
     * The Python PUT is held after its Function App sync and before its site write. Without one lock across both, the
     * Node PUT syncs and writes in that gap and the Python write lands last, so the site says Python while the
     * Function App runs Node.
     */
    @Test
    void concurrentSitePutsLeaveTheSiteAndItsFunctionAppOnOneRuntime() throws Exception {
        CountDownLatch synced = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (PYTHON.equals(invocation.getArgument(3))) {
                synced.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            return result;
        }).when(functions).upsertApp(anyString(), anyString(), anyString(), anyString(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> python = pool.submit(() -> putSite(PYTHON));
            assertTrue(synced.await(10, TimeUnit.SECONDS));
            Future<Integer> node = pool.submit(() -> putSite(NODE));
            try {
                node.get(500, TimeUnit.MILLISECONDS);
            } catch (TimeoutException expected) {
                // The Node PUT waits for the Python PUT's write; it completes once that is released.
            }
            release.countDown();
            assertEquals(200, python.get(10, TimeUnit.SECONDS));
            assertEquals(200, node.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        String site = given().when().get(SITE + API).then().statusCode(200)
            .extract().path("properties.siteConfig.linuxFxVersion");
        String app = given().when().get("/devstoreaccount1-functions/admin/apps/syncsite").then().statusCode(200)
            .extract().path("linuxFxVersion");
        assertEquals(site, app);
    }

    /**
     * Subscription A's re-PUT is held inside its Function App sync, after the gate let it through. A DELETE of A's
     * site and then subscription B's create of the same name arrive meanwhile. Without the DELETE waiting for the
     * PUT and the PUT holding its name again under the lock, the delete frees the name, B reserves it, and both PUTs
     * store a site named {@code syncsite}.
     *
     * <p>A's PUT runs in process, with the gate's name steps around it as {@link GlobalNameRule} takes them: on this
     * branch the routing filter dispatches requests to workers in order per event loop, so a paused HTTP request
     * can hold back an unrelated one and hide the race.
     */
    @Test
    void aDeleteDuringASitePutCannotHandItsNameToAnotherSubscription() throws Exception {
        String siteB = "/subscriptions/sub-sync-b/resourceGroups/rg/providers/Microsoft.Web/sites/syncsite";
        assertEquals(200, putSite(NODE));

        CountDownLatch synced = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (PYTHON.equals(invocation.getArgument(3))) {
                synced.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            return result;
        }).when(functions).upsertApp(anyString(), anyString(), anyString(), anyString(), any());

        ExecutorService pool = Executors.newFixedThreadPool(3);
        int putA;
        int deleteStatus;
        int putB;
        try {
            Future<Integer> rePut = pool.submit(() -> putInProcess(PYTHON));
            assertTrue(synced.await(10, TimeUnit.SECONDS));
            Future<Integer> delete = pool.submit(() -> given().when().delete(SITE + API).then().extract().statusCode());
            settle(delete);
            Future<Integer> create = pool.submit(() -> putSite(siteB, NODE));
            settle(create);
            release.countDown();
            putA = rePut.get(10, TimeUnit.SECONDS);
            deleteStatus = delete.get(10, TimeUnit.SECONDS);
            putB = create.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertEquals(200, putA);
        assertEquals(200, deleteStatus);
        int aStored = given().when().get(SITE + API).then().extract().statusCode();
        int bStored = given().when().get(siteB + API).then().extract().statusCode();
        assertTrue(aStored != 200 || bStored != 200,
            "both subscriptions store a site named syncsite (B's PUT answered " + putB + ")");
    }

    /** A's site PUT straight to the service, between the gate's reserve and commit of its name. */
    private int putInProcess(String linuxFxVersion) {
        ScopeContext scope = new ScopeContext(CLOUD, null, "sub-sync", "rg", null, ScopeSource.ARM_PATH);
        String body = "{\"location\":\"eastus\",\"properties\":{\"siteConfig\":{\"linuxFxVersion\":\""
            + linuxFxVersion + "\"}}}";
        String path = SITE.substring(1);
        AzureRequest request = new AzureRequest("PUT", "arm", "arm", path, null,
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), Map.of("api-version", "2023-01-01"),
            Map.of(), null, false, null, null, path, null).withScope(scope);
        ArmResourceId id = ArmResourceId.parse(SITE).orElseThrow();
        GlobalNameRegistry.Reservation reservation = names.reserve(CLOUD, CloudSuffix.WEB, "syncsite", id);
        assertEquals(GlobalNameRegistry.Outcome.OWNED, reservation.outcome());
        int status = web.handleArm(request, path, "PUT", "sub-sync").getStatus();
        if (status / 100 == 2) {
            names.commit(CLOUD, CloudSuffix.WEB, "syncsite", id);
        }
        return status;
    }
}
