package io.floci.az.services.web;

import io.floci.az.services.functions.FunctionsServiceHandler;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    @InjectSpy
    FunctionsServiceHandler functions;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static int putSite(String linuxFxVersion) {
        return given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{\"siteConfig\":{\"linuxFxVersion\":\"" + linuxFxVersion
                + "\"}}}")
            .when().put(SITE + API).then().extract().statusCode();
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
}
