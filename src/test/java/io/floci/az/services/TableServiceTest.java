package io.floci.az.services;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
}
