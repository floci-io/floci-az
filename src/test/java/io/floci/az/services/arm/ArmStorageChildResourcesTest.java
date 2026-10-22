package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@DisplayName("ARM storage child resources: blob containers and queues answer from the data plane's state")
class ArmStorageChildResourcesTest {

    private static final String ACCOUNT = "/subscriptions/sub-children/resourceGroups/rg-children"
        + "/providers/Microsoft.Storage/storageAccounts/childacct";
    private static final String API = "?api-version=2023-01-01";

    @BeforeEach
    void account() {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(ACCOUNT + API).then().statusCode(200);
    }

    @Test
    void aContainerExistsFromItsPutUntilItsDeleteThroughBothPlanes() {
        String container = ACCOUNT + "/blobServices/default/containers/";

        given().when().get(container + "never" + API).then().statusCode(404)
            .body("error.code", equalTo("ContainerNotFound"));

        given().contentType("application/json").body("{}")
            .when().put(container + "logs" + API).then().statusCode(201);
        given().when().get(container + "logs" + API).then().statusCode(200).body("name", equalTo("logs"));
        given().when().get("/childacct/logs?restype=container").then().statusCode(200);

        given().when().delete(container + "logs" + API).then().statusCode(200);
        given().when().get(container + "logs" + API).then().statusCode(404);
        given().when().get("/childacct/logs?restype=container").then().statusCode(404);
        given().when().delete(container + "logs" + API).then().statusCode(204);
    }

    @Test
    void aContainerMadeOnTheDataPlaneIsReadThroughArm() {
        given().when().put("/childacct/dataplane?restype=container").then().statusCode(201);

        given().when().get(ACCOUNT + "/blobServices/default/containers/dataplane" + API).then().statusCode(200);
    }

    @Test
    void aQueueAnswersTheSpecsStatusesAndItsDeleteReachesTheDataPlane() {
        String queue = ACCOUNT + "/queueServices/default/queues/";

        given().when().get(queue + "never" + API).then().statusCode(404)
            .body("error.code", equalTo("QueueNotFound"));

        given().contentType("application/json").body("{}")
            .when().put(queue + "jobs" + API).then().statusCode(200).body("name", equalTo("jobs"));
        given().contentType("application/json").body("{}")
            .when().put(queue + "jobs" + API).then().statusCode(200);
        given().when().get(queue + "jobs" + API).then().statusCode(200);
        given().when().get("/childacct-queue/jobs?comp=metadata").then().statusCode(200);

        given().when().delete(queue + "jobs" + API).then().statusCode(204);
        given().when().get(queue + "jobs" + API).then().statusCode(404);
        given().when().get("/childacct-queue/jobs?comp=metadata").then().statusCode(404);
        given().when().delete(queue + "jobs" + API).then().statusCode(204);
    }
}
