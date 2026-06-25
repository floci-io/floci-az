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
            .header("x-ms-meta-owner", "compat")
            .contentType("text/plain")
            .body(BLOB_CONTENT)
            .when().put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then().statusCode(201);

        given()
            .when().get("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then()
            .statusCode(200)
            .header("x-ms-meta-owner", "compat")
            .body(equalTo(BLOB_CONTENT));
    }

    @Test
    void createDfsFile() {
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER);

        given()
            .header("Host", ACCOUNT + ".dfs.core.windows.net")
            .header("x-ms-version", "2023-11-03")
            .when().put("/{container}/dir/file.txt?resource=file", CONTAINER)
            .then()
            .statusCode(201)
            .header("x-ms-request-server-encrypted", "true");

        given()
            .when().get("/{account}/{container}/dir/file.txt", ACCOUNT, CONTAINER)
            .then()
            .statusCode(200)
            .body(equalTo(""));
    }

    @Test
    void expiredSasReturnsAuthenticationFailed() {
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER);
        given()
            .header("x-ms-blob-type", "BlockBlob")
            .body(BLOB_CONTENT)
            .put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB);

        given()
            .when().get("/{account}/{container}/{blob}?se=2000-01-01T00%3A00Z&sp=r&sv=2026-04-06&sr=b&sig=ignored",
                    ACCOUNT, CONTAINER, BLOB)
            .then()
            .statusCode(403)
            .header("x-ms-error-code", "AuthenticationFailed")
            .body(containsString("AuthenticationFailed"));
    }

    @Test
    void setAndGetBlobMetadata() {
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER);
        given()
            .header("x-ms-blob-type", "BlockBlob")
            .header("x-ms-meta-owner", "initial")
            .body(BLOB_CONTENT)
            .put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB);

        given()
            .header("x-ms-meta-owner", "updated")
            .header("x-ms-meta-purpose", "blob-parity")
            .when().put("/{account}/{container}/{blob}?comp=metadata", ACCOUNT, CONTAINER, BLOB)
            .then().statusCode(200);

        given()
            .when().get("/{account}/{container}/{blob}?comp=metadata", ACCOUNT, CONTAINER, BLOB)
            .then()
            .statusCode(200)
            .header("x-ms-meta-owner", "updated")
            .header("x-ms-meta-purpose", "blob-parity")
            .header("x-ms-meta-missing", nullValue());
    }

    @Test
    void listBlobsIncludesMetadataWhenRequested() {
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER);
        given()
            .header("x-ms-blob-type", "BlockBlob")
            .header("x-ms-meta-owner", "compat")
            .body("data")
            .put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB);

        given()
            .when().get("/{account}/{container}?restype=container&comp=list&include=metadata", ACCOUNT, CONTAINER)
            .then()
            .statusCode(200)
            .body(containsString("<Metadata>"))
            .body(containsString("<owner>compat</owner>"));
    }

    @Test
    void blobConditionalGetHonorsIfMatch() {
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER);
        String etag = given()
            .header("x-ms-blob-type", "BlockBlob")
            .body(BLOB_CONTENT)
            .put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then()
            .statusCode(201)
            .extract().header("ETag");

        given()
            .header("If-Match", etag)
            .when().get("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then().statusCode(200);

        given()
            .header("If-Match", "wrong-etag")
            .when().get("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then()
            .statusCode(412)
            .header("x-ms-error-code", "ConditionNotMet");
    }

    @Test
    void blobConditionalDeleteHonorsIfNoneMatch() {
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER);
        String etag = given()
            .header("x-ms-blob-type", "BlockBlob")
            .body(BLOB_CONTENT)
            .put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then()
            .statusCode(201)
            .extract().header("ETag");

        given()
            .header("If-None-Match", etag)
            .when().delete("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then()
            .statusCode(412)
            .header("x-ms-error-code", "ConditionNotMet");

        given()
            .when().get("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then().statusCode(200);
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
    void listBlobsWithDelimiterReturnsBlobPrefixes() {
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER);
        given()
            .header("x-ms-blob-type", "BlockBlob")
            .body("nested")
            .put("/{account}/{container}/level0/file.txt", ACCOUNT, CONTAINER);
        given()
            .header("x-ms-blob-type", "BlockBlob")
            .body("top")
            .put("/{account}/{container}/other.txt", ACCOUNT, CONTAINER);

        given()
            .when().get("/{account}/{container}?restype=container&comp=list&delimiter=/", ACCOUNT, CONTAINER)
            .then()
            .statusCode(200)
            .contentType(containsString("xml"))
            .body(containsString("<Delimiter>/</Delimiter>"))
            .body(containsString("<BlobPrefix><Name>level0/</Name></BlobPrefix>"))
            .body(containsString("<Blob><Name>other.txt</Name>"))
            .body(not(containsString("<Blob><Name>level0/file.txt</Name>")));
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
    void emptyBlobInvalidRangeIncludesContentRange() {
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER);
        given()
            .header("x-ms-blob-type", "BlockBlob")
            .body("")
            .put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB);

        given()
            .header("x-ms-range", "bytes=0-0")
            .when().get("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, BLOB)
            .then()
            .statusCode(416)
            .header("Content-Range", "bytes */0")
            .header("x-ms-error-code", "InvalidRange");
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
