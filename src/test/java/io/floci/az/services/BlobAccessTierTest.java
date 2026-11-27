package io.floci.az.services;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class BlobAccessTierTest {

    private static final String ACCOUNT = "devstoreaccount1";
    private static final String CONTAINER = "tier-test";
    private static final String BLOB = "blob";
    private static final String BLOB_PATH = "/{account}/{container}/{blob}";
    private static final String LIST_PATH = "/{account}/{container}?restype=container&comp=list";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        given().put("/{account}/{container}?restype=container", ACCOUNT, CONTAINER).then().statusCode(201);
    }

    @Test
    void explicitAndInferredTiersAppearInListingsAndProperties() {
        given().header("x-ms-blob-type", "BlockBlob").header("x-ms-access-tier", "Cool")
                .body("cool data").put(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().statusCode(201);
        given().header("x-ms-blob-type", "BlockBlob").body("default data")
                .put(BLOB_PATH, ACCOUNT, CONTAINER, "default").then().statusCode(201);

        String listing = given().get(LIST_PATH, ACCOUNT, CONTAINER).then().statusCode(200).extract().asString();
        assertThat(listing, containsString("<AccessTier>Cool</AccessTier>"));
        assertThat(listing, containsString("<AccessTier>Hot</AccessTier>"));
        assertThat(listing, containsString("<AccessTierInferred>true</AccessTierInferred>"));
        given().head(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().statusCode(200)
                .header("x-ms-access-tier", "Cool")
                .header("x-ms-access-tier-inferred", nullValue());
        given().head(BLOB_PATH, ACCOUNT, CONTAINER, "default").then().statusCode(200)
                .header("x-ms-access-tier", "Hot")
                .header("x-ms-access-tier-inferred", "true");
    }

    @Test
    void inferredTierTracksAccountUpdatesButExplicitTierDoesNot() {
        String accountPath = "/subscriptions/tier-sub/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/"
                + ACCOUNT + "?api-version=2023-01-01";
        String cool = "{\"location\":\"eastus\",\"properties\":{\"accessTier\":\"Cool\"}}";
        given().contentType("application/json").body(cool).put(accountPath).then().statusCode(200);
        given().header("x-ms-blob-type", "BlockBlob").body("default")
                .put(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().statusCode(201);
        given().header("x-ms-blob-type", "BlockBlob").header("x-ms-access-tier", "Cold").body("explicit")
                .put(BLOB_PATH, ACCOUNT, CONTAINER, "explicit").then().statusCode(201);
        given().head(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().header("x-ms-access-tier", "Cool")
                .header("x-ms-access-tier-inferred", "true");
        given().queryParam("prefix", BLOB).get(LIST_PATH, ACCOUNT, CONTAINER).then()
                .body(containsString("<AccessTier>Cool</AccessTier>"))
                .body(containsString("<AccessTierInferred>true</AccessTierInferred>"));
        given().contentType("application/json").body("{\"location\":\"eastus\"}").put(accountPath)
                .then().statusCode(200).body("properties.accessTier", equalTo("Cool"));
        given().contentType("application/json")
                .body("{\"sku\":{\"name\":\"Standard_ZRS\"},"
                        + "\"properties\":{\"accessTier\":\"Hot\",\"minimumTlsVersion\":\"TLS1_2\"}}")
                .patch(accountPath).then().statusCode(200)
                .body("sku.name", equalTo("Standard_ZRS"))
                .body("properties.minimumTlsVersion", equalTo("TLS1_2"))
                .body("properties.accessTier", equalTo("Hot"));
        given().get(accountPath).then().body("sku.name", equalTo("Standard_ZRS"))
                .body("properties.minimumTlsVersion", equalTo("TLS1_2"));
        given().head(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().header("x-ms-access-tier", "Hot");
        given().queryParam("prefix", BLOB).get(LIST_PATH, ACCOUNT, CONTAINER).then()
                .body(containsString("<AccessTier>Hot</AccessTier>"))
                .body(containsString("<AccessTierInferred>true</AccessTierInferred>"));
        given().head(BLOB_PATH, ACCOUNT, CONTAINER, "explicit").then().header("x-ms-access-tier", "Cold");
        given().contentType("application/json").body("{\"properties\":{\"accessTier\":\"Cool\"}}").patch(accountPath)
                .then().statusCode(200);
        given().delete(accountPath).then().statusCode(200);
        given().head(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().header("x-ms-access-tier", "Hot");
    }

    @Test
    void crossTypeOverwriteDoesNotRetainBlockTierAndDfsOverwriteDoes() {
        given().header("x-ms-blob-type", "BlockBlob").header("x-ms-access-tier", "Cool").body("block")
                .put(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().statusCode(201);
        given().header("x-ms-blob-type", "AppendBlob").body("")
                .put(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().statusCode(201);
        given().get(LIST_PATH, ACCOUNT, CONTAINER).then()
                .body(not(containsString("<AccessTier>")));
        given().header("x-ms-blob-type", "BlockBlob").header("x-ms-access-tier", "Cool").body("file")
                .put(BLOB_PATH, ACCOUNT, CONTAINER, "file").then().statusCode(201);
        given().header("Host", ACCOUNT + ".dfs.core.windows.net")
                .put("/{container}/file?resource=file", CONTAINER).then().statusCode(201);
        given().head(BLOB_PATH, ACCOUNT, CONTAINER, "file").then().header("x-ms-access-tier", "Cool");
    }

    @Test
    void tierChangesRespectLeasesAndPutBlobFromUrl() {
        given().header("x-ms-blob-type", "BlockBlob").body("source")
                .put(BLOB_PATH, ACCOUNT, CONTAINER, "source").then().statusCode(201);
        given().header("x-ms-blob-type", "BlockBlob").header("x-ms-access-tier", "Cool")
                .header("x-ms-copy-source", "http://127.0.0.1:4577/" + ACCOUNT + "/" + CONTAINER + "/source")
                .put(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().statusCode(201);
        String lease = given().header("x-ms-lease-action", "acquire").header("x-ms-lease-duration", "-1")
                .put(BLOB_PATH + "?comp=lease", ACCOUNT, CONTAINER, BLOB).then().statusCode(201)
                .extract().header("x-ms-lease-id");
        given().header("x-ms-access-tier", "Hot").put(BLOB_PATH + "?comp=tier", ACCOUNT, CONTAINER, BLOB)
                .then().statusCode(412);
        given().header("x-ms-access-tier", "Hot").header("x-ms-lease-id", lease)
                .put(BLOB_PATH + "?comp=tier", ACCOUNT, CONTAINER, BLOB).then().statusCode(200);
        given().get(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then()
                .header("x-ms-access-tier", "Hot").body(equalTo("source"));
    }

    @Test
    void changingTierPreservesContentEtagAndSnapshotIsolation() {
        given().header("x-ms-blob-type", "BlockBlob").header("x-ms-access-tier", "Cool")
                .body("content").put(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().statusCode(201);
        Response before = given().head(BLOB_PATH, ACCOUNT, CONTAINER, BLOB);
        String snapshot = given().put(BLOB_PATH + "?comp=snapshot", ACCOUNT, CONTAINER, BLOB)
                .then().statusCode(201).extract().header("x-ms-snapshot");

        given().header("x-ms-access-tier", "Hot")
                .put(BLOB_PATH + "?comp=tier", ACCOUNT, CONTAINER, BLOB).then().statusCode(200);
        Response after = given().get(BLOB_PATH, ACCOUNT, CONTAINER, BLOB);
        after.then().statusCode(200).body(equalTo("content")).header("x-ms-access-tier", "Hot");
        assertEquals(before.header("ETag"), after.header("ETag"));
        assertEquals(before.header("Last-Modified"), after.header("Last-Modified"));
        given().queryParam("snapshot", snapshot).head(BLOB_PATH, ACCOUNT, CONTAINER, BLOB)
                .then().header("x-ms-access-tier", "Cool");

        given().header("x-ms-access-tier", "Cold").queryParam("snapshot", snapshot)
                .put(BLOB_PATH + "?comp=tier", ACCOUNT, CONTAINER, BLOB).then().statusCode(200);
        given().queryParam("snapshot", snapshot).head(BLOB_PATH, ACCOUNT, CONTAINER, BLOB)
                .then().header("x-ms-access-tier", "Cold");
        given().head(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().header("x-ms-access-tier", "Hot");
        given().get(LIST_PATH, ACCOUNT, CONTAINER).then().body(containsString("<AccessTier>Hot</AccessTier>"));
    }

    @Test
    void overwritingAndCommittingBlocksPreserveOrReplaceExplicitTier() {
        given().header("x-ms-blob-type", "BlockBlob").header("x-ms-access-tier", "Cool")
                .body("first").put(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().statusCode(201);
        given().header("x-ms-blob-type", "BlockBlob").body("second")
                .put(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().statusCode(201);
        given().head(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().header("x-ms-access-tier", "Cool");

        String block = Base64.getEncoder().encodeToString("block".getBytes(StandardCharsets.UTF_8));
        given().queryParam("comp", "block").queryParam("blockid", block).body("block data")
                .put(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().statusCode(201);
        given().header("x-ms-access-tier", "Cold").body("<BlockList><Latest>" + block + "</Latest></BlockList>")
                .put(BLOB_PATH + "?comp=blocklist", ACCOUNT, CONTAINER, BLOB).then().statusCode(201);
        given().get(LIST_PATH, ACCOUNT, CONTAINER).then().body(containsString("<AccessTier>Cold</AccessTier>"));
        given().get(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then()
                .header("x-ms-access-tier", "Cold").body(equalTo("block data"));
        given().body("<BlockList><Latest>" + block + "</Latest></BlockList>")
                .put(BLOB_PATH + "?comp=blocklist", ACCOUNT, CONTAINER, BLOB).then().statusCode(201);
        given().head(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().header("x-ms-access-tier", "Cold");
    }

    @Test
    void copyingABlobUsesTheRequestedOrExistingDestinationTier() {
        given().header("x-ms-blob-type", "BlockBlob").header("x-ms-access-tier", "Cool")
                .body("source").put(BLOB_PATH, ACCOUNT, CONTAINER, "source").then().statusCode(201);
        given().header("x-ms-blob-type", "BlockBlob").header("x-ms-access-tier", "Cold")
                .body("destination").put(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().statusCode(201);

        String source = "/" + ACCOUNT + "/" + CONTAINER + "/source";
        given().header("x-ms-copy-source", source)
                .put(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().statusCode(202);
        given().get(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then()
                .header("x-ms-access-tier", "Cold").body(equalTo("source"));
        given().header("x-ms-copy-source", source).header("x-ms-access-tier", "Hot")
                .put(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().statusCode(202);
        given().head(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().header("x-ms-access-tier", "Hot");
    }

    @Test
    void rejectedTierRequestsDoNotChangeTheBlob() {
        given().header("x-ms-blob-type", "BlockBlob").header("x-ms-access-tier", "Cool")
                .header("x-ms-tags", "env=prod")
                .body("content").put(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().statusCode(201);
        given().put(BLOB_PATH + "?comp=tier", ACCOUNT, CONTAINER, BLOB)
                .then().statusCode(400).header("x-ms-error-code", "MissingRequiredHeader");
        given().header("x-ms-access-tier", "invalid").put(BLOB_PATH + "?comp=tier", ACCOUNT, CONTAINER, BLOB)
                .then().statusCode(400).header("x-ms-error-code", "InvalidHeaderValue");
        given().header("x-ms-access-tier", "Hot").header("x-ms-if-tags", "\"env\" = 'other'")
                .put(BLOB_PATH + "?comp=tier", ACCOUNT, CONTAINER, BLOB)
                .then().statusCode(412).header("x-ms-error-code", "ConditionNotMet");
        given().header("x-ms-access-tier", "Archive").put(BLOB_PATH + "?comp=tier", ACCOUNT, CONTAINER, BLOB)
                .then().statusCode(501);
        given().header("x-ms-access-tier", "invalid").header("x-ms-blob-type", "BlockBlob")
                .body("replacement").put(BLOB_PATH, ACCOUNT, CONTAINER, BLOB)
                .then().statusCode(400).header("x-ms-error-code", "InvalidHeaderValue");
        given().header("x-ms-access-tier", "Cold").header("x-ms-blob-type", "AppendBlob")
                .body("replacement").put(BLOB_PATH, ACCOUNT, CONTAINER, BLOB)
                .then().statusCode(400).header("x-ms-error-code", "AccessTierNotSupportedForBlobType");
        given().header("x-ms-blob-type", "AppendBlob").body("")
                .put(BLOB_PATH, ACCOUNT, CONTAINER, "append").then().statusCode(201);
        given().header("x-ms-access-tier", "Cold").put(BLOB_PATH + "?comp=tier", ACCOUNT, CONTAINER, "append")
                .then().statusCode(400).header("x-ms-error-code", "AccessTierNotSupportedForBlobType");
        given().get(BLOB_PATH, ACCOUNT, CONTAINER, BLOB).then().statusCode(200)
                .header("x-ms-access-tier", "Cool").body(equalTo("content"));
        given().get(LIST_PATH, ACCOUNT, CONTAINER).then().body(not(containsString("<AccessTier>Cold</AccessTier>")));
    }
}
