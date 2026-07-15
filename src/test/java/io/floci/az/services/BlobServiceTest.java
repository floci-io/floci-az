package io.floci.az.services;

import io.floci.az.core.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class BlobServiceTest {

    private static final String ACCOUNT = "devstoreaccount1";
    private static final String CONTAINER = "test-container";
    private static final String BLOB = "test-blob.txt";
    private static final String BLOB_CONTENT = "Hello, Blob!";
    private static final Pattern ISO_UTC_SECONDS = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");

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
    void getUserDelegationKeyReturnsAzureXmlForBearerAuth() {
        String xml = """
                <KeyInfo>
                  <Start>2026-07-15T10:00:00Z</Start>
                  <Expiry>2026-07-15T11:00:00Z</Expiry>
                </KeyInfo>
                """;

        String response = given()
            .header("Authorization", "Bearer fake-token")
            .header("x-ms-version", "2024-11-04")
            .contentType("application/xml")
            .body(xml)
            .when().post("/{account}?restype=service&comp=userdelegationkey", ACCOUNT)
            .then()
            .statusCode(200)
            .contentType(containsString("xml"))
            .extract().asString();

        assertThat(XmlParser.extractFirst(response, "SignedOid", null),
                equalTo("00000000-0000-0000-0000-000000000000"));
        assertThat(XmlParser.extractFirst(response, "SignedTid", null),
                equalTo("00000000-0000-0000-0000-000000000000"));
        assertThat(XmlParser.extractFirst(response, "SignedStart", null), equalTo("2026-07-15T10:00:00Z"));
        assertThat(XmlParser.extractFirst(response, "SignedExpiry", null), equalTo("2026-07-15T11:00:00Z"));
        assertThat(XmlParser.extractFirst(response, "SignedService", null), equalTo("b"));
        assertThat(XmlParser.extractFirst(response, "SignedVersion", null), equalTo("2024-11-04"));
        assertThat(XmlParser.extractFirst(response, "Value", null), not(isEmptyOrNullString()));
    }

    @Test
    void getUserDelegationKeyDefaultsMissingStart() {
        String expiry = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).withNano(0).toString();
        String xml = """
                <KeyInfo>
                  <Expiry>%s</Expiry>
                </KeyInfo>
                """.formatted(expiry);

        String response = given()
            .header("Authorization", "Bearer fake-token")
            .contentType("application/xml")
            .body(xml)
            .when().post("/{account}?restype=service&comp=userdelegationkey", ACCOUNT)
            .then()
            .statusCode(200)
            .extract().asString();

        assertThat(XmlParser.extractFirst(response, "SignedStart", null),
                matchesPattern(ISO_UTC_SECONDS));
        assertThat(XmlParser.extractFirst(response, "SignedExpiry", null), equalTo(expiry));
    }

    @Test
    void getUserDelegationKeyRejectsMissingExpiry() {
        given()
            .header("Authorization", "Bearer fake-token")
            .contentType("application/xml")
            .body("<KeyInfo><Start>2026-07-15T10:00:00Z</Start></KeyInfo>")
            .when().post("/{account}?restype=service&comp=userdelegationkey", ACCOUNT)
            .then()
            .statusCode(400)
            .header("x-ms-error-code", "InvalidXmlDocument");
    }

    @Test
    void getUserDelegationKeyRejectsMalformedTimestamp() {
        given()
            .header("Authorization", "Bearer fake-token")
            .contentType("application/xml")
            .body("<KeyInfo><Start>not-a-date</Start><Expiry>2026-07-15T11:00:00Z</Expiry></KeyInfo>")
            .when().post("/{account}?restype=service&comp=userdelegationkey", ACCOUNT)
            .then()
            .statusCode(400)
            .header("x-ms-error-code", "InvalidXmlDocument");
    }

    @Test
    void getUserDelegationKeyRejectsExpiryBeforeStart() {
        given()
            .header("Authorization", "Bearer fake-token")
            .contentType("application/xml")
            .body("<KeyInfo><Start>2026-07-15T11:00:00Z</Start><Expiry>2026-07-15T10:00:00Z</Expiry></KeyInfo>")
            .when().post("/{account}?restype=service&comp=userdelegationkey", ACCOUNT)
            .then()
            .statusCode(400)
            .header("x-ms-error-code", "OutOfRangeInput");
    }

    @Test
    void getUserDelegationKeyRejectsDurationsOverSevenDays() {
        given()
            .header("Authorization", "Bearer fake-token")
            .contentType("application/xml")
            .body("<KeyInfo><Start>2026-07-15T10:00:00Z</Start><Expiry>2026-07-23T10:00:00Z</Expiry></KeyInfo>")
            .when().post("/{account}?restype=service&comp=userdelegationkey", ACCOUNT)
            .then()
            .statusCode(400)
            .header("x-ms-error-code", "OutOfRangeInput");
    }

    @Test
    void getUserDelegationKeyRequiresBearerAuth() {
        String xml = """
                <KeyInfo>
                  <Start>2026-07-15T10:00:00Z</Start>
                  <Expiry>2026-07-15T11:00:00Z</Expiry>
                </KeyInfo>
                """;

        given()
            .contentType("application/xml")
            .body(xml)
            .when().post("/{account}?restype=service&comp=userdelegationkey", ACCOUNT)
            .then()
            .statusCode(403)
            .header("x-ms-error-code", "AuthenticationFailed");

        given()
            .header("Authorization", "SharedKey " + ACCOUNT + ":ignored")
            .contentType("application/xml")
            .body(xml)
            .when().post("/{account}?restype=service&comp=userdelegationkey", ACCOUNT)
            .then()
            .statusCode(403)
            .header("x-ms-error-code", "AuthenticationFailed");
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
    void listBlobsHonorsMaxResultsAndMarker() {
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER);
        for (String name : new String[] {"a.txt", "b.txt", "c.txt"}) {
            given()
                .header("x-ms-blob-type", "BlockBlob")
                .body(name)
                .put("/{account}/{container}/{blob}", ACCOUNT, CONTAINER, name);
        }

        String page1 = given()
            .when().get("/{account}/{container}?restype=container&comp=list&maxresults=2", ACCOUNT, CONTAINER)
            .then()
            .statusCode(200)
            .contentType(containsString("xml"))
            .extract().asString();

        assertThat(page1, containsString("<MaxResults>2</MaxResults>"));
        assertThat(page1, containsString("<Blob><Name>a.txt</Name>"));
        assertThat(page1, containsString("<Blob><Name>b.txt</Name>"));
        assertThat(page1, not(containsString("<Blob><Name>c.txt</Name>")));

        String marker = nextMarker(page1);
        assertThat(marker, not(emptyString()));

        String page2 = given()
            .queryParam("marker", marker)
            .when().get("/{account}/{container}?restype=container&comp=list&maxresults=2", ACCOUNT, CONTAINER)
            .then()
            .statusCode(200)
            .contentType(containsString("xml"))
            .extract().asString();

        assertThat(page2, containsString("<Marker>" + marker + "</Marker>"));
        assertThat(page2, not(containsString("<Blob><Name>a.txt</Name>")));
        assertThat(page2, not(containsString("<Blob><Name>b.txt</Name>")));
        assertThat(page2, containsString("<Blob><Name>c.txt</Name>"));
        assertThat(nextMarker(page2), is(""));
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

    private static String nextMarker(String response) {
        Matcher matcher = Pattern.compile("<NextMarker>(.*?)</NextMarker>").matcher(response);
        assertThat(matcher.find(), is(true));
        return matcher.group(1);
    }
}
