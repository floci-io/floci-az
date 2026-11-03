package io.floci.az.services.arm;

import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageFactory;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@DisplayName("ARM storage child resources: blob containers and queues answer from the data plane's state")
class ArmStorageChildResourcesTest {

    private static final String ACCOUNT = "/subscriptions/sub-children/resourceGroups/rg-children"
        + "/providers/Microsoft.Storage/storageAccounts/childacct";
    private static final String API = "?api-version=2023-01-01";
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
        .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    @Inject
    StorageFactory storageFactory;

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
    void aContainerPutAnswersTheEtagAndLastModifiedTheDataPlaneStores() {
        String container = ACCOUNT + "/blobServices/default/containers/versioned" + API;

        Response first = given().contentType("application/json").body("{}")
            .when().put(container);
        first.then().statusCode(201)
            .body("etag", matchesPattern("^\"0x[A-F0-9]{15,}\"$"))
            .body("properties.lastModifiedTime", matchesPattern("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$"));
        String etag = first.path("etag");
        String lastModified = first.path("properties.lastModifiedTime");

        given().when().get("/childacct/versioned?restype=container").then().statusCode(200)
            .header("ETag", equalTo(etag))
            .header("Last-Modified", equalTo(HTTP_DATE.format(Instant.parse(lastModified))));

        given().contentType("application/json").body("{}")
            .when().put(container).then().statusCode(200)
            .body("etag", equalTo(etag))
            .body("properties.lastModifiedTime", equalTo(lastModified));
        given().when().get("/childacct/versioned?restype=container").then().statusCode(200)
            .header("ETag", equalTo(etag));
    }

    @Test
    void aContainerPutOverAContainerStoredWithoutAnEtagAnswersTheEtagItGivesIt() {
        storageFactory.create("blob").put("__ns__:childacct/legacy", new StoredObject("", new byte[0],
                Map.of(), Instant.EPOCH, ""));

        String etag = given().contentType("application/json").body("{}")
            .when().put(ACCOUNT + "/blobServices/default/containers/legacy" + API).then().statusCode(200)
            .body("etag", matchesPattern("^\"0x[A-F0-9]{15,}\"$"))
            .body("properties.lastModifiedTime", not(equalTo("1970-01-01T00:00:00Z")))
            .extract().path("etag");

        given().when().get("/childacct/legacy?restype=container").then().statusCode(200)
            .header("ETag", equalTo(etag));
    }

    @Test
    void aContainerGetAnswersTheEtagAndLastModifiedOfItsPut() {
        String container = ACCOUNT + "/blobServices/default/containers/readback" + API;
        Response put = given().contentType("application/json").body("{}").when().put(container);
        put.then().statusCode(201);

        given().when().get(container).then().statusCode(200)
            .body("etag", equalTo(put.path("etag")))
            .body("properties.lastModifiedTime", equalTo(put.path("properties.lastModifiedTime")))
            .body("properties.publicAccess", equalTo("None"))
            .body("properties.leaseStatus", equalTo("Unlocked"))
            .body("properties.leaseState", equalTo("Available"));
    }

    @Test
    void aContainerGetOfAContainerMadeOnTheDataPlaneAnswersTheDataPlanesEtag() {
        String lastModified = given().when().put("/childacct/dataplane-etag?restype=container")
            .then().statusCode(201).extract().header("Last-Modified");
        String etag = given().when().get("/childacct/dataplane-etag?restype=container")
            .then().statusCode(200).extract().header("ETag");

        String armLastModified = given()
            .when().get(ACCOUNT + "/blobServices/default/containers/dataplane-etag" + API)
            .then().statusCode(200)
            .body("etag", equalTo(etag))
            .extract().path("properties.lastModifiedTime");
        assertEquals(lastModified,
            HTTP_DATE.format(Instant.parse(armLastModified)));
    }

    @Test
    void aContainerGetOverAContainerStoredWithoutAnEtagAnswersTheEtagItGivesIt() {
        storageFactory.create("blob").put("__ns__:childacct/legacy-get", new StoredObject("", new byte[0],
                Map.of(), Instant.EPOCH, ""));

        String etag = given().when().get(ACCOUNT + "/blobServices/default/containers/legacy-get" + API)
            .then().statusCode(200)
            .body("etag", matchesPattern("^\"0x[A-F0-9]{15,}\"$"))
            .body("properties.lastModifiedTime", not(equalTo("1970-01-01T00:00:00Z")))
            .extract().path("etag");

        given().when().get("/childacct/legacy-get?restype=container").then().statusCode(200)
            .header("ETag", equalTo(etag));
    }

    @Test
    void aRepeatedContainerPutKeepsTheFilesystemProperties() {
        given().header("Host", "childacct.dfs.core.windows.net").header("x-ms-properties", "a2V5PXZhbHVl")
            .when().put("/withprops?resource=filesystem").then().statusCode(201);
        String etag = given().header("Host", "childacct.dfs.core.windows.net")
            .when().head("/withprops?resource=filesystem").then().statusCode(200)
            .extract().header("ETag");

        given().contentType("application/json").body("{}")
            .when().put(ACCOUNT + "/blobServices/default/containers/withprops" + API).then().statusCode(200)
            .body("etag", equalTo(etag));

        given().header("Host", "childacct.dfs.core.windows.net")
            .when().head("/withprops?resource=filesystem").then().statusCode(200)
            .header("ETag", equalTo(etag))
            .header("x-ms-properties", equalTo("a2V5PXZhbHVl"));
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

    @Test
    void anArmDeleteTakesTheContainersBlobsAndTheQueuesMessagesWithIt() {
        String container = ACCOUNT + "/blobServices/default/containers/withblobs";
        given().contentType("application/json").body("{}").when().put(container + API).then().statusCode(201);
        given().header("x-ms-blob-type", "BlockBlob").body("kept?")
            .when().put("/childacct/withblobs/old.txt").then().statusCode(201);
        given().when().delete(container + API).then().statusCode(200);
        given().contentType("application/json").body("{}").when().put(container + API).then().statusCode(201);
        given().when().get("/childacct/withblobs/old.txt").then().statusCode(404);

        String queue = ACCOUNT + "/queueServices/default/queues/withmessages";
        given().contentType("application/json").body("{}").when().put(queue + API).then().statusCode(200);
        given().body("<QueueMessage><MessageText>kept?</MessageText></QueueMessage>")
            .when().post("/childacct-queue/withmessages/messages").then().statusCode(201);
        given().when().delete(queue + API).then().statusCode(204);
        given().contentType("application/json").body("{}").when().put(queue + API).then().statusCode(200);
        given().when().get("/childacct-queue/withmessages/messages?peekonly=true").then().statusCode(200)
            .body(not(containsString("kept?")));
    }
}
