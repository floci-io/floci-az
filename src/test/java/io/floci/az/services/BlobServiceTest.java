package io.floci.az.services;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class BlobServiceTest {

    private static final String ACCOUNT = "devstoreaccount1";
    private static final String CONTAINER = "test-container";
    private static final String BLOB = "test-blob.txt";
    private static final String BLOB_CONTENT = "Hello, Blob!";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void createAndDeleteContainer() {
        given()
            .when().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER)
            .then().statusCode(201);

        given()
            .when().delete("/{account}/{container}?restype=container", ACCOUNT, CONTAINER)
            .then().statusCode(202);
    }

    @Test
    void createContainerTwiceReturnsConflict() {
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER);

        given()
            .when().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER)
            .then().statusCode(409);
    }

    @Test
    void putAndGetBlob() {
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER);

        given()
            .header("x-ms-blob-type", "BlockBlob")
            .contentType("text/plain")
            .body(BLOB_CONTENT)
            .when().put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then().statusCode(201);

        given()
            .when().get("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then()
            .statusCode(200)
            .body(equalTo(BLOB_CONTENT));
    }

    @Test
    void getMissingBlobReturns404() {
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER);

        given()
            .when().get("/{account}/{container}/missing.txt", ACCOUNT, CONTAINER)
            .then().statusCode(404);
    }

    @Test
    void deleteBlob() {
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER);
        given()
            .header("x-ms-blob-type", "BlockBlob")
            .body(BLOB_CONTENT)
            .put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB);

        given()
            .when().delete("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then().statusCode(202);

        given()
            .when().get("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then().statusCode(404);
    }

    @Test
    void listBlobs() {
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER);
        given()
            .header("x-ms-blob-type", "BlockBlob")
            .body("data")
            .put("/{account}/{container}/blob1.txt", ACCOUNT, CONTAINER);
        given()
            .header("x-ms-blob-type", "BlockBlob")
            .body("data")
            .put("/{account}/{container}/blob2.txt", ACCOUNT, CONTAINER);

        given()
            .when().get("/{account}/{container}?restype=container&comp=list", ACCOUNT, CONTAINER)
            .then()
            .statusCode(200)
            .contentType(containsString("xml"))
            .body(containsString("blob1.txt"))
            .body(containsString("blob2.txt"));
    }

    @Test
    void rangeRequestReturnsPartialContent() {
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER);
        given()
            .header("x-ms-blob-type", "BlockBlob")
            .body("0123456789")
            .put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB);

        given()
            .header("Range", "bytes=2-5")
            .when().get("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then()
            .statusCode(206)
            .body(equalTo("2345"));
    }

    @Test
    void invalidRangeReturns416() {
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER);
        given()
            .header("x-ms-blob-type", "BlockBlob")
            .body("short")
            .put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB);

        given()
            .header("Range", "bytes=9999-99999")
            .when().get("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then()
            .statusCode(416);
    }

    @Test
    void malformedRangeReturns416() {
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER);
        given()
            .header("x-ms-blob-type", "BlockBlob")
            .body("data")
            .put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB);

        given()
            .header("Range", "bytes=abc-def")
            .when().get("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then()
            .statusCode(416);
    }
}