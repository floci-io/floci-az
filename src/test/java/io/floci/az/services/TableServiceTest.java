package io.floci.az.services;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
public class TableServiceTest {

    private static final String ACCOUNT = "devstoreaccount1-table";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void getTableServicePropertiesReturnsXml() {
        given()
            .when().get("/{account}?restype=service&comp=properties", ACCOUNT)
            .then()
            .statusCode(200)
            .contentType(containsString("xml"))
            .body(containsString("<StorageServiceProperties>"))
            .body(containsString("<Logging>"))
            .body(containsString("<HourMetrics>"))
            .body(containsString("<MinuteMetrics>"))
            .body(not(containsString("\"value\"")));
    }

    @Test
    void setTableServicePropertiesIsAccepted() {
        given()
            .contentType("application/xml")
            .body("<StorageServiceProperties><Logging><Version>1.0</Version></Logging></StorageServiceProperties>")
            .when().put("/{account}?restype=service&comp=properties", ACCOUNT)
            .then()
            .statusCode(202);
    }

    @Test
    void postTableServicePropertiesIsNotImplemented() {
        given()
            .when().post("/{account}?restype=service&comp=properties", ACCOUNT)
            .then()
            .statusCode(501);
    }

    @Test
    void listTablesStillReturnsJson() {
        given()
            .contentType("application/json")
            .body("{\"TableName\":\"mytable\"}")
            .when().post("/{account}/Tables", ACCOUNT)
            .then().statusCode(201);

        given()
            .when().get("/{account}/Tables", ACCOUNT)
            .then()
            .statusCode(200)
            .header("Content-Type", startsWith("application/json"))
            .body("value.TableName", hasItem("mytable"));
    }

    // The JSON error path carries the same crash class: the Azure SDK for C++ calls json::parse on the
    // body whenever content-type contains "json", also without an empty-buffer guard.
    @Test
    void headOnUnsupportedTableOperationOmitsContentType() {
        given()
            .when().head("/{account}/Tables", ACCOUNT)
            .then()
            .statusCode(501)
            .header("Content-Type", nullValue())
            .header("x-ms-error-code", "NotImplemented");
    }

    // GET is allowed a body, so the JSON error document and its content type must survive.
    @Test
    void getMissingEntityStillReturnsErrorBody() {
        given()
            .when().get("/{account}/no-such-table(PartitionKey='p',RowKey='r')", ACCOUNT)
            .then()
            .statusCode(404)
            .contentType(containsString("json"))
            .body(containsString("TableNotFound"));
    }

    @Test
    void entityOperationsOnMissingTableReturnTableNotFound() {
        insertEntity("NeverCreated", "{\"PartitionKey\":\"p\",\"RowKey\":\"r\"}")
            .then()
            .statusCode(404)
            .header("x-ms-error-code", "TableNotFound")
            .contentType(containsString("odata=minimalmetadata"))
            .body("'odata.error'.code", equalTo("TableNotFound"))
            .body("'odata.error'.message.value", equalTo("The table specified does not exist."));

        given()
            .when().get("/{account}/NeverCreated()", ACCOUNT)
            .then().statusCode(404).body("'odata.error'.code", equalTo("TableNotFound"));
        given()
            .when().get("/{account}/NeverCreated(PartitionKey='p',RowKey='r')", ACCOUNT)
            .then().statusCode(404).body("'odata.error'.code", equalTo("TableNotFound"));
        given()
            .contentType("application/json")
            .body("{\"v\":1}")
            .when().put("/{account}/NeverCreated(PartitionKey='p',RowKey='r')", ACCOUNT)
            .then().statusCode(404).body("'odata.error'.code", equalTo("TableNotFound"));
        given()
            .contentType("application/json")
            .body("{\"v\":1}")
            .when().request("MERGE", "/{account}/NeverCreated(PartitionKey='p',RowKey='r')", ACCOUNT)
            .then().statusCode(404).body("'odata.error'.code", equalTo("TableNotFound"));
        given()
            .header("If-Match", "*")
            .when().delete("/{account}/NeverCreated(PartitionKey='p',RowKey='r')", ACCOUNT)
            .then().statusCode(404).body("'odata.error'.code", equalTo("TableNotFound"));

        // Nothing was written for the missing table, so creating it afterwards starts empty.
        createTable("NeverCreated");
        given()
            .when().get("/{account}/NeverCreated()", ACCOUNT)
            .then().statusCode(200).body("value.size()", equalTo(0));
    }

    @Test
    void entityOperationsAfterDeleteTableReturnTableNotFound() {
        createTable("Dropped");
        insertEntity("Dropped", "{\"PartitionKey\":\"p\",\"RowKey\":\"r\"}").then().statusCode(201);
        given()
            .when().delete("/{account}/Tables('Dropped')", ACCOUNT)
            .then().statusCode(204);

        insertEntity("Dropped", "{\"PartitionKey\":\"p\",\"RowKey\":\"r2\"}")
            .then().statusCode(404).header("x-ms-error-code", "TableNotFound");
        given()
            .when().get("/{account}/Dropped()", ACCOUNT)
            .then().statusCode(404).header("x-ms-error-code", "TableNotFound");
    }

    @Test
    void batchOnMissingTableFailsWithTableNotFound() {
        submitBatch(batchOperation("POST", "NoBatchTable", "{\"PartitionKey\":\"p\",\"RowKey\":\"r\"}"))
            .then()
            .statusCode(202)
            .body(containsString("HTTP/1.1 404 Not Found"))
            .body(containsString("TableNotFound"))
            .body(containsString("\"value\":\"0:The table specified does not exist.\""));

        createTable("NoBatchTable");
        given()
            .when().get("/{account}/NoBatchTable()", ACCOUNT)
            .then().statusCode(200).body("value.size()", equalTo(0));
    }

    @Test
    void duplicateCreateTableReturnsODataErrorEnvelope() {
        given()
            .contentType("application/json")
            .body("{\"TableName\":\"DupCreate\"}")
            .when().post("/{account}/Tables", ACCOUNT)
            .then().statusCode(201);

        given()
            .contentType("application/json")
            .body("{\"TableName\":\"DupCreate\"}")
            .when().post("/{account}/Tables", ACCOUNT)
            .then()
            .statusCode(409)
            .header("x-ms-error-code", "TableAlreadyExists")
            .contentType(containsString("odata=minimalmetadata"))
            .body("'odata.error'.code", equalTo("TableAlreadyExists"))
            .body("'odata.error'.message.lang", equalTo("en-US"))
            .body("'odata.error'.message.value", containsString("already exists"));
    }

    @Test
    void getMissingEntityReturnsODataErrorEnvelope() {
        given()
            .contentType("application/json")
            .body("{\"TableName\":\"EnvelopeMiss\"}")
            .when().post("/{account}/Tables", ACCOUNT)
            .then().statusCode(201);

        given()
            .when().get("/{account}/EnvelopeMiss(PartitionKey='p',RowKey='absent')", ACCOUNT)
            .then()
            .statusCode(404)
            .header("x-ms-error-code", "ResourceNotFound")
            .contentType(containsString("odata=minimalmetadata"))
            .body("'odata.error'.code", equalTo("ResourceNotFound"))
            .body("'odata.error'.message.value", containsString("does not exist"));
    }

    @Test
    void insertExistingEntityReturnsConflictAndKeepsStoredEntity() {
        createTable("InsertDup");
        String etag = insertEntity("InsertDup", "{\"PartitionKey\":\"p\",\"RowKey\":\"r\",\"v\":1}")
            .then().statusCode(201)
            .extract().header("ETag");

        insertEntity("InsertDup", "{\"PartitionKey\":\"p\",\"RowKey\":\"r\",\"v\":2}")
            .then()
            .statusCode(409)
            .header("x-ms-error-code", "EntityAlreadyExists")
            .contentType(containsString("odata=minimalmetadata"))
            .body("'odata.error'.code", equalTo("EntityAlreadyExists"))
            .body("'odata.error'.message.value", containsString("already exists"));

        given()
            .when().get("/{account}/InsertDup(PartitionKey='p',RowKey='r')", ACCOUNT)
            .then()
            .statusCode(200)
            .header("ETag", etag)
            .body("v", equalTo(1));
    }

    @Test
    void concurrentInsertsOfOneKeyLetExactlyOneSucceed() throws Exception {
        createTable("InsertRace");
        int writers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            for (int round = 0; round < 10; round++) {
                String rowKey = "r" + round;
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> statuses = new ArrayList<>();
                for (int writer = 0; writer < writers; writer++) {
                    String entity = "{\"PartitionKey\":\"p\",\"RowKey\":\"" + rowKey + "\",\"writer\":" + writer + "}";
                    statuses.add(pool.submit(() -> {
                        start.await();
                        return insertEntity("InsertRace", entity).statusCode();
                    }));
                }
                start.countDown();

                int created = 0;
                int conflicts = 0;
                for (Future<Integer> status : statuses) {
                    int code = status.get();
                    if (code == 201) {
                        created++;
                    } else if (code == 409) {
                        conflicts++;
                    }
                }
                assertEquals(1, created, "round " + round + " created");
                assertEquals(writers - 1, conflicts, "round " + round + " conflicts");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void upsertOfExistingEntityStillOverwrites() {
        createTable("UpsertKeep");
        insertEntity("UpsertKeep", "{\"PartitionKey\":\"p\",\"RowKey\":\"r\",\"v\":1}")
            .then().statusCode(201);

        given()
            .contentType("application/json")
            .body("{\"v\":2}")
            .when().put("/{account}/UpsertKeep(PartitionKey='p',RowKey='r')", ACCOUNT)
            .then().statusCode(204);

        given()
            .contentType("application/json")
            .body("{\"w\":3}")
            .when().request("MERGE", "/{account}/UpsertKeep(PartitionKey='p',RowKey='r')", ACCOUNT)
            .then().statusCode(204);

        given()
            .when().get("/{account}/UpsertKeep(PartitionKey='p',RowKey='r')", ACCOUNT)
            .then()
            .statusCode(200)
            .body("v", equalTo(2))
            .body("w", equalTo(3));
    }

    @Test
    void batchInsertOfExistingEntityRollsBackWholeChangeset() {
        createTable("BatchDup");
        insertEntity("BatchDup", "{\"PartitionKey\":\"p\",\"RowKey\":\"r\",\"v\":1}")
            .then().statusCode(201);

        submitBatch(
                batchOperation("POST", "BatchDup", "{\"PartitionKey\":\"p\",\"RowKey\":\"new\"}"),
                batchOperation("POST", "BatchDup", "{\"PartitionKey\":\"p\",\"RowKey\":\"r\",\"v\":2}"))
            .then()
            .statusCode(202)
            .body(containsString("HTTP/1.1 409 Conflict"))
            .body(containsString("EntityAlreadyExists"))
            .body(containsString("\"value\":\"1:The specified entity already exists.\""));

        given()
            .when().get("/{account}/BatchDup(PartitionKey='p',RowKey='new')", ACCOUNT)
            .then().statusCode(404);
        given()
            .when().get("/{account}/BatchDup(PartitionKey='p',RowKey='r')", ACCOUNT)
            .then().statusCode(200).body("v", equalTo(1));
    }

    @Test
    void batchUpsertOfExistingEntityStillCommits() {
        createTable("BatchUpsert");
        insertEntity("BatchUpsert", "{\"PartitionKey\":\"p\",\"RowKey\":\"r\",\"v\":1}")
            .then().statusCode(201);

        submitBatch(
                batchOperation("POST", "BatchUpsert", "{\"PartitionKey\":\"p\",\"RowKey\":\"new\"}"),
                batchOperation("PUT", "BatchUpsert(PartitionKey='p',RowKey='r')",
                        "{\"PartitionKey\":\"p\",\"RowKey\":\"r\",\"v\":2}"))
            .then()
            .statusCode(202)
            .body(containsString("HTTP/1.1 201 Created"))
            .body(containsString("HTTP/1.1 204 No Content"))
            .body(not(containsString("HTTP/1.1 409")));

        given()
            .when().get("/{account}/BatchUpsert(PartitionKey='p',RowKey='new')", ACCOUNT)
            .then().statusCode(200);
        given()
            .when().get("/{account}/BatchUpsert(PartitionKey='p',RowKey='r')", ACCOUNT)
            .then().statusCode(200).body("v", equalTo(2));
    }

    @Test
    void entitiesWhoseKeysConcatenateIdenticallyStayDistinct() {
        createTable("Collide");

        given()
            .contentType("application/json")
            .body("{\"who\":\"first\"}")
            .when().put("/{account}/Collide(PartitionKey='a_b',RowKey='c')", ACCOUNT)
            .then().statusCode(204);
        given()
            .contentType("application/json")
            .body("{\"who\":\"second\"}")
            .when().put("/{account}/Collide(PartitionKey='a',RowKey='b_c')", ACCOUNT)
            .then().statusCode(204);

        given()
            .when().get("/{account}/Collide(PartitionKey='a_b',RowKey='c')", ACCOUNT)
            .then().statusCode(200).body("who", equalTo("first"));
        given()
            .when().get("/{account}/Collide(PartitionKey='a',RowKey='b_c')", ACCOUNT)
            .then().statusCode(200).body("who", equalTo("second"));
        given()
            .when().get("/{account}/Collide()", ACCOUNT)
            .then().statusCode(200).body("value.size()", equalTo(2));

        given()
            .when().delete("/{account}/Collide(PartitionKey='a',RowKey='b_c')", ACCOUNT)
            .then().statusCode(204);
        given()
            .when().get("/{account}/Collide(PartitionKey='a_b',RowKey='c')", ACCOUNT)
            .then().statusCode(200).body("who", equalTo("first"));
    }

    @Test
    void getTableAclOfNewTableReturnsEmptySignedIdentifiers() {
        createTable("AclEmpty");
        insertEntity("AclEmpty", "{\"PartitionKey\":\"p\",\"RowKey\":\"r\"}").then().statusCode(201);

        given()
            .when().get("/{account}/AclEmpty?comp=acl", ACCOUNT)
            .then()
            .statusCode(200)
            .contentType(containsString("xml"))
            .body(containsString("<SignedIdentifiers>"))
            .body(not(containsString("<SignedIdentifier>")))
            .body(not(containsString("\"value\"")));
    }

    @Test
    void setTableAclThenGetReturnsStoredPolicies() {
        createTable("AclRoundTrip");

        given()
            .contentType("application/xml")
            .body(signedIdentifiers(2, "raud"))
            .when().put("/{account}/AclRoundTrip?comp=acl", ACCOUNT)
            .then()
            .statusCode(204);

        given()
            .when().get("/{account}/AclRoundTrip?comp=acl", ACCOUNT)
            .then()
            .statusCode(200)
            .contentType(containsString("xml"))
            .body("SignedIdentifiers.SignedIdentifier.size()", equalTo(2))
            .body("SignedIdentifiers.SignedIdentifier[0].Id", equalTo("policy-0"))
            .body("SignedIdentifiers.SignedIdentifier[1].AccessPolicy.Start", equalTo("2026-01-01T00:00:00.0000000Z"))
            .body("SignedIdentifiers.SignedIdentifier[1].AccessPolicy.Expiry", equalTo("2027-01-01T00:00:00.0000000Z"))
            .body("SignedIdentifiers.SignedIdentifier[1].AccessPolicy.Permission", equalTo("raud"));

        // The ACL lives on the table record: entities and the table listing are untouched.
        given()
            .when().get("/{account}/Tables", ACCOUNT)
            .then().statusCode(200).body("value.TableName", hasItem("AclRoundTrip"));
        given()
            .when().get("/{account}/AclRoundTrip()", ACCOUNT)
            .then().statusCode(200).body("value.size()", equalTo(0));
    }

    @Test
    void setTableAclWithEmptyBodyClearsPolicies() {
        createTable("AclClear");
        given()
            .contentType("application/xml")
            .body(signedIdentifiers(1, "r"))
            .when().put("/{account}/AclClear?comp=acl", ACCOUNT)
            .then().statusCode(204);

        given()
            .contentType("application/xml")
            .body("")
            .when().put("/{account}/AclClear?comp=acl", ACCOUNT)
            .then().statusCode(204);

        given()
            .when().get("/{account}/AclClear?comp=acl", ACCOUNT)
            .then()
            .statusCode(200)
            .body(not(containsString("<SignedIdentifier>")));
    }

    @Test
    void setTableAclWithMoreThanFivePoliciesIsRejected() {
        createTable("AclTooMany");

        given()
            .contentType("application/xml")
            .body(signedIdentifiers(6, "r"))
            .when().put("/{account}/AclTooMany?comp=acl", ACCOUNT)
            .then()
            .statusCode(400)
            .header("x-ms-error-code", "InvalidXmlDocument")
            .body(containsString("<Code>InvalidXmlDocument</Code>"));

        given()
            .when().get("/{account}/AclTooMany?comp=acl", ACCOUNT)
            .then().statusCode(200).body(not(containsString("<SignedIdentifier>")));
    }

    @Test
    void setTableAclWithQueuePermissionIsRejected() {
        createTable("AclBadPerm");

        given()
            .contentType("application/xml")
            .body(signedIdentifiers(1, "rp"))
            .when().put("/{account}/AclBadPerm?comp=acl", ACCOUNT)
            .then()
            .statusCode(400)
            .header("x-ms-error-code", "InvalidXmlDocument");
    }

    @Test
    void tableAclOnMissingTableReturnsTableNotFound() {
        given()
            .when().get("/{account}/AclMissing?comp=acl", ACCOUNT)
            .then()
            .statusCode(404)
            .header("x-ms-error-code", "TableNotFound")
            .contentType(containsString("xml"));

        given()
            .contentType("application/xml")
            .body(signedIdentifiers(1, "r"))
            .when().put("/{account}/AclMissing?comp=acl", ACCOUNT)
            .then()
            .statusCode(404)
            .header("x-ms-error-code", "TableNotFound");
    }

    private static String signedIdentifiers(int count, String permission) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><SignedIdentifiers>");
        for (int i = 0; i < count; i++) {
            xml.append("<SignedIdentifier><Id>policy-").append(i).append("</Id><AccessPolicy>")
                .append("<Start>2026-01-01T00:00:00.0000000Z</Start>")
                .append("<Expiry>2027-01-01T00:00:00.0000000Z</Expiry>")
                .append("<Permission>").append(permission).append("</Permission>")
                .append("</AccessPolicy></SignedIdentifier>");
        }
        return xml.append("</SignedIdentifiers>").toString();
    }

    private static void createTable(String name) {
        given()
            .contentType("application/json")
            .body("{\"TableName\":\"" + name + "\"}")
            .when().post("/{account}/Tables", ACCOUNT)
            .then().statusCode(201);
    }

    private static Response insertEntity(String table, String entityJson) {
        return given()
            .contentType("application/json")
            .body(entityJson)
            .when().post("/{account}/{table}", ACCOUNT, table);
    }

    private static String batchOperation(String method, String path, String entityJson) {
        return "Content-Type: application/http\r\n"
            + "Content-Transfer-Encoding: binary\r\n\r\n"
            + method + " http://localhost/" + ACCOUNT + "/" + path + " HTTP/1.1\r\n"
            + "Content-Type: application/json\r\n\r\n"
            + entityJson + "\r\n";
    }

    private static Response submitBatch(String... operations) {
        String changeset = "changeset_test";
        StringBuilder body = new StringBuilder()
            .append("--batch_test\r\n")
            .append("Content-Type: multipart/mixed; boundary=").append(changeset).append("\r\n\r\n");
        for (String operation : operations) {
            body.append("--").append(changeset).append("\r\n").append(operation);
        }
        body.append("--").append(changeset).append("--\r\n")
            .append("--batch_test--\r\n");
        return given()
            .contentType("multipart/mixed; boundary=batch_test")
            .body(body.toString().getBytes(StandardCharsets.UTF_8))
            .when().post("/{account}/$batch", ACCOUNT);
    }
}
