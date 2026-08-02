package io.floci.az.services;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
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
            .body(containsString("ResourceNotFound"));
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
}
