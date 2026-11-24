package io.floci.az.services.search;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * What asks for the Solr sidecar and what lets it go, with the sidecar mocked: the first search service starts it,
 * a write to a service's data plane asks for it too, and the delete of the last service removes it unless an
 * undeclared service still has an index, whose delete then removes it.
 */
@QuarkusTest
@DisplayName("Azure AI Search: what starts and stops the Solr sidecar")
class SearchEngineTriggersTest {

    private static final String SUB = "5ea4c400-0000-4000-8000-000000000002";
    private static final String ARM_API = "?api-version=2025-05-01";
    private static final String API = "?api-version=2025-09-01";
    private static final String BODY = "{\"location\": \"westus\", \"sku\": {\"name\": \"basic\"}}";

    @InjectMock
    SearchSolrManager engine;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        Mockito.clearInvocations(engine);
    }

    private static String service(String name) {
        return "/subscriptions/" + SUB + "/resourceGroups/rg/providers/Microsoft.Search/searchServices/" + name;
    }

    @Test
    void creatingAServiceStartsTheSidecarAndUpdatingItDoesNot() {
        given().contentType("application/json").body(BODY).when().put(service("trigger-one") + ARM_API)
            .then().statusCode(201);
        verify(engine).prepare();

        Mockito.clearInvocations(engine);
        given().contentType("application/json").body(BODY).when().put(service("trigger-one") + ARM_API)
            .then().statusCode(200);
        verify(engine, never()).prepare();
    }

    @Test
    void deletingTheLastServiceStopsTheSidecarButNotWhileOneIsLeft() {
        given().contentType("application/json").body(BODY).when().put(service("trigger-a") + ARM_API)
            .then().statusCode(201);
        given().contentType("application/json").body(BODY).when().put(service("trigger-b") + ARM_API)
            .then().statusCode(201);

        given().when().delete(service("trigger-a") + ARM_API).then().statusCode(200);
        verify(engine, never()).stop();

        given().when().delete(service("trigger-b") + ARM_API).then().statusCode(200);
        verify(engine).stop();
    }

    @Test
    void aSuccessfulDataPlaneWriteAsksForTheSidecarAReadOrRefusedOneDoesNotAndItsIndexKeepsItUntilDeleted() {
        given().when().get("/undeclared-search/indexes" + API).then().statusCode(200);
        given().contentType("application/json").body("{\"name\": \"idx\", \"fields\": []}")
            .when().post("/undeclared-search/indexes" + API).then().statusCode(400);
        verify(engine, never()).prepare();

        given().contentType("application/json")
            .body("{\"name\": \"idx\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", \"key\": true}]}")
            .when().post("/undeclared-search/indexes" + API).then().statusCode(201);
        verify(engine).prepare();

        // Its index still needs Solr when the last ARM service goes.
        given().contentType("application/json").body(BODY).when().put(service("trigger-c") + ARM_API)
            .then().statusCode(201);
        given().when().delete(service("trigger-c") + ARM_API).then().statusCode(200);
        verify(engine, never()).stop();

        // Until that index goes too.
        given().when().delete("/undeclared-search/indexes('idx')" + API).then().statusCode(204);
        verify(engine).stop();
    }
}
