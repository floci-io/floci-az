package io.floci.az.compat;

import com.azure.core.http.rest.PagedResponse;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobClientBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobRange;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobServiceProperties;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.BlockListType;
import com.azure.storage.blob.models.CustomerProvidedKey;
import com.azure.storage.blob.models.LeaseStateType;
import com.azure.storage.blob.models.LeaseStatusType;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.options.BlobInputStreamOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.blob.specialized.BlobLeaseClient;
import com.azure.storage.blob.specialized.BlobLeaseClientBuilder;
import com.azure.storage.blob.specialized.BlockBlobClient;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Blob Storage Compatibility")
class BlobCompatibilityTest {

    private BlobServiceClient client;

    @BeforeAll
    void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        client = new BlobServiceClientBuilder()
            .connectionString(EmulatorConfig.BLOB_CONN)
            .buildClient();
    }

    private String containerName() {
        return "test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    // --- Golden path ---

    @Test
    @DisplayName("container lifecycle: create → list → delete")
    void containerLifecycle() {
        String name = containerName();

        client.createBlobContainer(name);

        List<String> names = client.listBlobContainers().stream()
            .map(c -> c.getName()).toList();
        assertTrue(names.contains(name));

        client.deleteBlobContainer(name);

        List<String> after = client.listBlobContainers().stream()
            .map(c -> c.getName()).toList();
        assertFalse(after.contains(name));
    }

    @Test
    @DisplayName("blob lifecycle: upload → download → list → delete")
    void blobLifecycle() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        BlobClient blob = container.getBlobClient("hello.txt");

        byte[] content = "Hello from Azure SDK Java!".getBytes();
        blob.upload(new java.io.ByteArrayInputStream(content), content.length, true);

        byte[] downloaded = blob.downloadContent().toBytes();
        assertArrayEquals(content, downloaded);

        List<BlobItem> blobs = container.listBlobs().stream().toList();
        assertEquals(1, blobs.size());
        assertEquals("hello.txt", blobs.get(0).getName());

        blob.delete();
        assertEquals(0, container.listBlobs().stream().count());

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("blob properties: mandatory Get Blob headers are populated")
    void mandatoryResponseHeaders() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        BlobClient blob = container.getBlobClient("props.txt");
        byte[] content = "header check".getBytes(StandardCharsets.UTF_8);
        blob.upload(new java.io.ByteArrayInputStream(content), content.length, true);

        BlobProperties props = blob.getProperties();
        assertNotNull(props.getCreationTime(), "x-ms-creation-time must be returned");
        assertEquals(LeaseStatusType.UNLOCKED, props.getLeaseStatus());
        assertEquals(LeaseStateType.AVAILABLE, props.getLeaseState());
        assertTrue(props.isServerEncrypted(), "x-ms-server-encrypted must be true");

        // creation time must not track last-modified across a metadata update
        blob.setMetadata(Map.of("owner", "compat"));
        assertEquals(props.getCreationTime(), blob.getProperties().getCreationTime());

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("customer-provided key: encrypted blob reads require the matching key")
    void customerProvidedKeyIsRequiredForEncryptedBlobReads() throws Exception {
        EmulatorConfig.installEmulatorTlsCert();
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        String endpoint = EmulatorConfig.httpBase().replace("http://", "https://")
                + "/" + EmulatorConfig.ACCOUNT + "/" + name + "/encrypted.txt";
        StorageSharedKeyCredential credential =
                new StorageSharedKeyCredential(EmulatorConfig.ACCOUNT, EmulatorConfig.DEV_KEY);
        CustomerProvidedKey key = new CustomerProvidedKey(
                "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=");
        CustomerProvidedKey wrongKey = new CustomerProvidedKey(new byte[32]);
        BlobClient encryptedBlob = new BlobClientBuilder()
                .endpoint(endpoint)
                .credential(credential)
                .customerProvidedKey(key)
                .buildClient();
        BlobClient blobWithoutKey = new BlobClientBuilder()
                .endpoint(endpoint)
                .credential(credential)
                .buildClient();
        BlobClient blobWithWrongKey = new BlobClientBuilder()
                .endpoint(endpoint)
                .credential(credential)
                .customerProvidedKey(wrongKey)
                .buildClient();
        byte[] content = "customer-provided key".getBytes(StandardCharsets.UTF_8);
        encryptedBlob.upload(new ByteArrayInputStream(content), content.length, true);

        BlobStorageException missingKeyDownload = assertThrows(
                BlobStorageException.class, blobWithoutKey::downloadContent);
        assertEquals(409, missingKeyDownload.getStatusCode());
        assertEquals(BlobErrorCode.BLOB_USES_CUSTOMER_SPECIFIED_ENCRYPTION,
                missingKeyDownload.getErrorCode());

        BlobStorageException missingKeyProperties = assertThrows(
                BlobStorageException.class, blobWithoutKey::getProperties);
        assertEquals(409, missingKeyProperties.getStatusCode());
        assertEquals(BlobErrorCode.BLOB_USES_CUSTOMER_SPECIFIED_ENCRYPTION,
                missingKeyProperties.getErrorCode());

        BlobStorageException wrongKeyDownload = assertThrows(
                BlobStorageException.class, blobWithWrongKey::downloadContent);
        assertEquals(409, wrongKeyDownload.getStatusCode());
        assertEquals(BlobErrorCode.fromString("BlobCustomerSpecifiedEncryptionMismatch"),
                wrongKeyDownload.getErrorCode());

        assertArrayEquals(content, encryptedBlob.downloadContent().toBytes());
        assertEquals(key.getKeySha256(), encryptedBlob.getProperties().getEncryptionKeySha256());

        container.delete();
    }

    @Test
    @DisplayName("customer-provided key: encrypted block commits require the matching key")
    void customerProvidedKeyIsRequiredForEncryptedBlockCommit() throws Exception {
        EmulatorConfig.installEmulatorTlsCert();
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        String endpoint = EmulatorConfig.httpBase().replace("http://", "https://")
                + "/" + EmulatorConfig.ACCOUNT + "/" + name + "/encrypted-blocks.bin";
        StorageSharedKeyCredential credential =
                new StorageSharedKeyCredential(EmulatorConfig.ACCOUNT, EmulatorConfig.DEV_KEY);
        CustomerProvidedKey key = new CustomerProvidedKey(
                "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=");
        CustomerProvidedKey wrongKey = new CustomerProvidedKey(new byte[32]);
        BlockBlobClient encryptedBlob = new BlobClientBuilder()
                .endpoint(endpoint)
                .credential(credential)
                .customerProvidedKey(key)
                .buildClient()
                .getBlockBlobClient();
        BlockBlobClient blobWithoutKey = new BlobClientBuilder()
                .endpoint(endpoint)
                .credential(credential)
                .buildClient()
                .getBlockBlobClient();
        BlockBlobClient blobWithWrongKey = new BlobClientBuilder()
                .endpoint(endpoint)
                .credential(credential)
                .customerProvidedKey(wrongKey)
                .buildClient()
                .getBlockBlobClient();
        byte[] content = "customer-provided block".getBytes(StandardCharsets.UTF_8);
        String blockId = Base64.getEncoder().encodeToString("block-1".getBytes(StandardCharsets.UTF_8));

        try {
            encryptedBlob.stageBlock(blockId, new ByteArrayInputStream(content), content.length);

            BlobStorageException missingKey = assertThrows(
                    BlobStorageException.class, () -> blobWithoutKey.commitBlockList(List.of(blockId)));
            assertEquals(409, missingKey.getStatusCode());
            assertEquals(BlobErrorCode.BLOB_USES_CUSTOMER_SPECIFIED_ENCRYPTION,
                    missingKey.getErrorCode());

            BlobStorageException mismatchedKey = assertThrows(
                    BlobStorageException.class, () -> blobWithWrongKey.commitBlockList(List.of(blockId)));
            assertEquals(409, mismatchedKey.getStatusCode());
            assertEquals(BlobErrorCode.fromString("BlobCustomerSpecifiedEncryptionMismatch"),
                    mismatchedKey.getErrorCode());

            encryptedBlob.commitBlockList(List.of(blockId));
            assertArrayEquals(content, encryptedBlob.downloadContent().toBytes());
        } finally {
            container.delete();
        }
    }

    @Test
    @DisplayName("customer-provided key: staged blocks use one encryption state")
    void customerProvidedKeyMustMatchStagedBlocks() throws Exception {
        EmulatorConfig.installEmulatorTlsCert();
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        String endpoint = EmulatorConfig.httpBase().replace("http://", "https://")
                + "/" + EmulatorConfig.ACCOUNT + "/" + name;
        StorageSharedKeyCredential credential =
                new StorageSharedKeyCredential(EmulatorConfig.ACCOUNT, EmulatorConfig.DEV_KEY);
        CustomerProvidedKey key = new CustomerProvidedKey(
                "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=");
        CustomerProvidedKey wrongKey = new CustomerProvidedKey(new byte[32]);
        byte[] content = "customer-provided block".getBytes(StandardCharsets.UTF_8);
        String firstBlockId = Base64.getEncoder().encodeToString("block-1".getBytes(StandardCharsets.UTF_8));
        String secondBlockId = Base64.getEncoder().encodeToString("block-2".getBytes(StandardCharsets.UTF_8));

        try {
            BlockBlobClient keyedBlob = new BlobClientBuilder()
                    .endpoint(endpoint + "/keyed-blocks.bin")
                    .credential(credential)
                    .customerProvidedKey(key)
                    .buildClient()
                    .getBlockBlobClient();
            BlockBlobClient wrongKeyBlob = new BlobClientBuilder()
                    .endpoint(endpoint + "/keyed-blocks.bin")
                    .credential(credential)
                    .customerProvidedKey(wrongKey)
                    .buildClient()
                    .getBlockBlobClient();
            BlockBlobClient keyedBlobWithoutKey = new BlobClientBuilder()
                    .endpoint(endpoint + "/keyed-blocks.bin")
                    .credential(credential)
                    .buildClient()
                    .getBlockBlobClient();

            keyedBlob.stageBlock(firstBlockId, new ByteArrayInputStream(content), content.length);

            BlobStorageException mismatchedKey = assertThrows(BlobStorageException.class,
                    () -> wrongKeyBlob.stageBlock(secondBlockId,
                            new ByteArrayInputStream(content), content.length));
            assertEquals(409, mismatchedKey.getStatusCode());
            assertEquals(BlobErrorCode.fromString("BlobCustomerSpecifiedEncryptionMismatch"),
                    mismatchedKey.getErrorCode());

            BlobStorageException missingKey = assertThrows(BlobStorageException.class,
                    () -> keyedBlobWithoutKey.stageBlock(secondBlockId,
                            new ByteArrayInputStream(content), content.length));
            assertEquals(409, missingKey.getStatusCode());
            assertEquals(BlobErrorCode.BLOB_USES_CUSTOMER_SPECIFIED_ENCRYPTION,
                    missingKey.getErrorCode());

            BlockBlobClient keylessBlob = new BlobClientBuilder()
                    .endpoint(endpoint + "/keyless-blocks.bin")
                    .credential(credential)
                    .buildClient()
                    .getBlockBlobClient();
            BlockBlobClient keylessBlobWithKey = new BlobClientBuilder()
                    .endpoint(endpoint + "/keyless-blocks.bin")
                    .credential(credential)
                    .customerProvidedKey(key)
                    .buildClient()
                    .getBlockBlobClient();
            keylessBlob.stageBlock(firstBlockId, new ByteArrayInputStream(content), content.length);

            BlobStorageException addedKey = assertThrows(BlobStorageException.class,
                    () -> keylessBlobWithKey.stageBlock(secondBlockId,
                            new ByteArrayInputStream(content), content.length));
            assertEquals(409, addedKey.getStatusCode());
            assertEquals(BlobErrorCode.fromString("BlobDoesNotUseCustomerSpecifiedEncryption"),
                    addedKey.getErrorCode());

            BlobStorageException keyedCommit = assertThrows(BlobStorageException.class,
                    () -> keylessBlobWithKey.commitBlockList(List.of(firstBlockId)));
            assertEquals(409, keyedCommit.getStatusCode());
            assertEquals(BlobErrorCode.fromString("BlobDoesNotUseCustomerSpecifiedEncryption"),
                    keyedCommit.getErrorCode());

            keylessBlob.commitBlockList(List.of(firstBlockId));
            assertArrayEquals(content, keylessBlob.downloadContent().toBytes());

            BlobStorageException keyedRead = assertThrows(
                    BlobStorageException.class, keylessBlobWithKey::downloadContent);
            assertEquals(409, keyedRead.getStatusCode());
            assertEquals(BlobErrorCode.fromString("BlobDoesNotUseCustomerSpecifiedEncryption"),
                    keyedRead.getErrorCode());
        } finally {
            container.delete();
        }
    }

    @Test
    @DisplayName("blob HTTP headers: upload options round-trip through properties")
    void blobHttpHeadersRoundTrip() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        BlobClient blob = container.getBlobClient("image.png");
        byte[] content = "image".getBytes(StandardCharsets.UTF_8);
        byte[] md5 = Base64.getDecoder().decode("eIBaIhqYjnnvP0LXxb/UGA==");
        BlobHttpHeaders headers = new BlobHttpHeaders()
            .setContentType("image/png")
            .setCacheControl("public, max-age=31536000")
            .setContentDisposition("inline; filename=image.png")
            .setContentEncoding("gzip")
            .setContentLanguage("en-GB")
            .setContentMd5(md5);

        blob.getBlockBlobClient().uploadWithResponse(
            new java.io.ByteArrayInputStream(content),
            content.length,
            headers,
            null,
            null,
            null,
            null,
            null,
            Context.NONE);

        BlobProperties properties = blob.getProperties();
        assertEquals("image/png", properties.getContentType());
        assertEquals("public, max-age=31536000", properties.getCacheControl());
        assertEquals("inline; filename=image.png", properties.getContentDisposition());
        assertEquals("gzip", properties.getContentEncoding());
        assertEquals("en-GB", properties.getContentLanguage());
        assertArrayEquals(md5, properties.getContentMd5());

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("multiple blobs: upload 5 → list → count matches")
    void multipleBlobs() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);

        for (int i = 0; i < 5; i++) {
            byte[] data = ("content-" + i).getBytes();
            container.getBlobClient("file-" + i + ".txt")
                .upload(new java.io.ByteArrayInputStream(data), data.length, true);
        }

        long count = container.listBlobs().stream().count();
        assertEquals(5, count);

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("blob hierarchy listing: returns common prefixes")
    void blobHierarchyListingReturnsPrefixes() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);

        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        container.getBlobClient("level0/file1.txt").upload(new java.io.ByteArrayInputStream(data), data.length, true);
        container.getBlobClient("level0/file2.txt").upload(new java.io.ByteArrayInputStream(data), data.length, true);
        container.getBlobClient("root.txt").upload(new java.io.ByteArrayInputStream(data), data.length, true);

        List<BlobItem> items = container.listBlobsByHierarchy("/", new ListBlobsOptions(), null).stream().toList();
        assertEquals(List.of("level0/", "root.txt"), items.stream().map(BlobItem::getName).sorted().toList());
        assertTrue(items.stream().filter(BlobItem::isPrefix).map(BlobItem::getName).toList().contains("level0/"));
        assertTrue(items.stream().filter(item -> !item.isPrefix()).map(BlobItem::getName).toList().contains("root.txt"));

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("blob listing pagination: follows continuation token")
    void blobListingPaginationFollowsContinuationToken() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);

        for (int i = 0; i < 5; i++) {
            byte[] data = ("content-" + i).getBytes(StandardCharsets.UTF_8);
            container.getBlobClient("file-" + i + ".txt")
                .upload(new java.io.ByteArrayInputStream(data), data.length, true);
        }

        var pages = container.listBlobs(new ListBlobsOptions().setMaxResultsPerPage(2), null)
                .iterableByPage()
                .iterator();
        assertTrue(pages.hasNext());
        List<String> firstPage = pages.next().getElements().stream().map(BlobItem::getName).toList();
        assertTrue(pages.hasNext());
        List<String> secondPage = pages.next().getElements().stream().map(BlobItem::getName).toList();

        assertEquals(List.of("file-0.txt", "file-1.txt"), firstPage);
        assertEquals(List.of("file-2.txt", "file-3.txt"), secondPage);

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("blob listing startFrom: starts at the inclusive blob name")
    void blobListingStartsFromInclusiveName() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        for (String blobName : List.of("a.txt", "b.txt", "c.txt")) {
            container.getBlobClient(blobName).upload(new ByteArrayInputStream(data), data.length, true);
        }

        List<String> blobs = container.listBlobs(new ListBlobsOptions().setStartFrom("b.txt"), null).stream()
                .map(BlobItem::getName)
                .toList();
        assertEquals(List.of("b.txt", "c.txt"), blobs);

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("hierarchical blob listing startFrom: applies to prefixes and blobs")
    void hierarchicalBlobListingStartsFromInclusiveName() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        for (String blobName : List.of("a/one", "b.txt", "c/one", "d.txt")) {
            container.getBlobClient(blobName).upload(new ByteArrayInputStream(data), data.length, true);
        }

        Iterator<PagedResponse<BlobItem>> pages = container.listBlobsByHierarchy(
                        "/", new ListBlobsOptions().setStartFrom("b").setMaxResultsPerPage(2), null)
                .iterableByPage()
                .iterator();
        assertEquals(List.of("b.txt", "c/"),
                pages.next().getElements().stream().map(BlobItem::getName).toList());
        assertEquals(List.of("d.txt"), pages.next().getElements().stream().map(BlobItem::getName).toList());

        client.deleteBlobContainer(name);
    }

    // --- Error cases ---

    @Test
    @DisplayName("download missing blob → BlobNotFound (404)")
    void blobNotFound() {
        String name = containerName();
        client.createBlobContainer(name);
        BlobClient blob = client.getBlobContainerClient(name).getBlobClient("no-such.txt");

        BlobStorageException ex = assertThrows(BlobStorageException.class,
            () -> blob.downloadContent());
        assertEquals(BlobErrorCode.BLOB_NOT_FOUND, ex.getErrorCode());
        assertEquals(404, ex.getStatusCode());

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("expired blob SAS: rejects request")
    void expiredBlobSasRejectsRequest() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        BlobClient blob = container.getBlobClient("sas.txt");

        byte[] content = "sas".getBytes(StandardCharsets.UTF_8);
        blob.upload(new java.io.ByteArrayInputStream(content), content.length, true);

        BlobClient sasBlob = new BlobClientBuilder()
                .endpoint(EmulatorConfig.httpBase() + "/" + EmulatorConfig.ACCOUNT + "/" + name
                        + "/sas.txt?sv=2023-11-03&sr=b&sp=r&se=2020-01-01T00%3A00%3A00Z&sig=expired")
                .buildClient();
        BlobStorageException ex = assertThrows(BlobStorageException.class, sasBlob::downloadContent);
        assertEquals(BlobErrorCode.AUTHENTICATION_FAILED, ex.getErrorCode());
        assertEquals(403, ex.getStatusCode());

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("service SAS: SDK-generated read token downloads blob")
    void serviceSasDownloadsBlob() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        BlobClient blob = container.getBlobClient("sas.txt");
        byte[] content = "service sas".getBytes(StandardCharsets.UTF_8);
        blob.upload(new ByteArrayInputStream(content), content.length, true);

        OffsetDateTime start = OffsetDateTime.now();
        String sas = blob.generateSas(new BlobServiceSasSignatureValues(
                start.plusHours(1),
                new BlobSasPermission().setReadPermission(true))
                .setStartTime(start));
        BlobClient sasBlob = new BlobClientBuilder()
                .endpoint(blob.getBlobUrl())
                .sasToken(sas)
                .buildClient();
        assertArrayEquals(content, sasBlob.downloadContent().toBytes());

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("development storage: SDK default key signs a valid service SAS")
    void developmentStorageKeySignsServiceSas() {
        BlobServiceClient developmentClient = new BlobServiceClientBuilder()
                .connectionString("UseDevelopmentStorage=true")
                .endpoint(EmulatorConfig.httpBase() + "/" + EmulatorConfig.ACCOUNT)
                .buildClient();
        BlobContainerClient container = developmentClient.createBlobContainer(containerName());
        try {
            BlobClient blob = container.getBlobClient("sas.txt");
            byte[] content = "development storage sas".getBytes(StandardCharsets.UTF_8);
            blob.upload(new ByteArrayInputStream(content), content.length, true);
            BlobServiceSasSignatureValues values = new BlobServiceSasSignatureValues(
                    OffsetDateTime.now().plusHours(1), new BlobSasPermission().setReadPermission(true));
            BlobClient sasBlob = new BlobClientBuilder()
                    .endpoint(blob.getBlobUrl())
                    .sasToken(blob.generateSas(values))
                    .buildClient();
            assertArrayEquals(content, sasBlob.downloadContent().toBytes());

            byte[] wrongKey = Base64.getDecoder().decode(EmulatorConfig.DEV_KEY);
            wrongKey[wrongKey.length - 1] ^= 1;
            BlobClient signer = new BlobClientBuilder()
                    .endpoint(blob.getBlobUrl())
                    .credential(new StorageSharedKeyCredential(EmulatorConfig.ACCOUNT,
                            Base64.getEncoder().encodeToString(wrongKey)))
                    .buildClient();
            BlobClient invalid = new BlobClientBuilder()
                    .endpoint(blob.getBlobUrl())
                    .sasToken(signer.generateSas(values))
                    .buildClient();
            BlobStorageException failure = assertThrows(BlobStorageException.class, invalid::downloadContent);
            assertEquals(403, failure.getStatusCode());
            assertEquals(BlobErrorCode.AUTHENTICATION_FAILED, failure.getErrorCode());
        } finally {
            container.delete();
        }
    }

    @Test
    @DisplayName("ARM account: returned key signs a valid service SAS")
    void armAccountKeySignsServiceSas() throws Exception {
        String account = "sas" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String armUrl = EmulatorConfig.httpBase()
                + "/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/sas-compat"
                + "/providers/Microsoft.Storage/storageAccounts/" + account;
        String api = "?api-version=2023-01-01";
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> created = http.send(HttpRequest.newBuilder(URI.create(armUrl + api))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("""
                            {"location":"eastus","sku":{"name":"Standard_LRS"},"kind":"StorageV2"}
                            """))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, created.statusCode(), created.body());
            try {
                HttpResponse<String> keys = http.send(HttpRequest.newBuilder(URI.create(armUrl + "/listKeys" + api))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, keys.statusCode(), keys.body());
                String key = new ObjectMapper().readTree(keys.body()).path("keys").get(0).path("value").asText();
                BlobServiceClient accountClient = new BlobServiceClientBuilder()
                        .endpoint(EmulatorConfig.httpBase() + "/" + account)
                        .credential(new StorageSharedKeyCredential(account, key))
                        .buildClient();
                BlobContainerClient container = accountClient.createBlobContainer(containerName());
                try {
                    BlobClient blob = container.getBlobClient("sas.txt");
                    byte[] content = "account key sas".getBytes(StandardCharsets.UTF_8);
                    blob.upload(new ByteArrayInputStream(content), content.length);
                    BlobServiceSasSignatureValues values = new BlobServiceSasSignatureValues(
                            OffsetDateTime.now().plusHours(1), new BlobSasPermission().setReadPermission(true));
                    BlobClient reader = new BlobClientBuilder().endpoint(blob.getBlobUrl())
                            .sasToken(blob.generateSas(values)).buildClient();
                    assertArrayEquals(content, reader.downloadContent().toBytes());

                    byte[] wrongKey = Base64.getDecoder().decode(key);
                    wrongKey[0] ^= 1;
                    BlobClient signer = new BlobClientBuilder().endpoint(blob.getBlobUrl())
                            .credential(new StorageSharedKeyCredential(account, Base64.getEncoder().encodeToString(wrongKey)))
                            .buildClient();
                    BlobClient invalid = new BlobClientBuilder().endpoint(blob.getBlobUrl())
                            .sasToken(signer.generateSas(values)).buildClient();
                    BlobStorageException failure = assertThrows(BlobStorageException.class, invalid::downloadContent);
                    assertEquals(403, failure.getStatusCode());
                    assertEquals(BlobErrorCode.AUTHENTICATION_FAILED, failure.getErrorCode());
                } finally {
                    container.delete();
                }
            } finally {
                HttpResponse<String> deleted = http.send(HttpRequest.newBuilder(URI.create(armUrl + api))
                        .DELETE().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, deleted.statusCode(), deleted.body());
            }
        }
    }

    @Test
    @DisplayName("flat namespace service SAS: SDK-generated read token downloads blob")
    void flatNamespaceServiceSasDownloadsBlob() {
        String account = "flataccount";
        BlobServiceClient flatClient = new BlobServiceClientBuilder()
                .connectionString(String.format(
                        "DefaultEndpointsProtocol=http;AccountName=%s;AccountKey=%s;BlobEndpoint=%s/%s;",
                        account, EmulatorConfig.DEV_KEY, EmulatorConfig.httpBase(), account))
                .buildClient();
        String name = containerName();
        BlobContainerClient container = flatClient.createBlobContainer(name);
        BlobClient blob = container.getBlobClient("sas.txt");
        byte[] content = "flat service sas".getBytes(StandardCharsets.UTF_8);
        blob.upload(new ByteArrayInputStream(content), content.length, true);

        OffsetDateTime start = OffsetDateTime.now();
        String sas = blob.generateSas(new BlobServiceSasSignatureValues(
                start.plusHours(1),
                new BlobSasPermission().setReadPermission(true))
                .setStartTime(start));
        BlobClient sasBlob = new BlobClientBuilder()
                .endpoint(blob.getBlobUrl())
                .sasToken(sas)
                .buildClient();
        assertArrayEquals(content, sasBlob.downloadContent().toBytes());

        flatClient.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("create duplicate container → ContainerAlreadyExists (409)")
    void containerAlreadyExists() {
        String name = containerName();
        client.createBlobContainer(name);

        BlobStorageException ex = assertThrows(BlobStorageException.class,
            () -> client.createBlobContainer(name));
        assertEquals(BlobErrorCode.CONTAINER_ALREADY_EXISTS, ex.getErrorCode());
        assertEquals(409, ex.getStatusCode());

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("blob overwrite: second upload replaces content")
    void blobOverwrite() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        BlobClient blob = container.getBlobClient("overwrite.txt");

        byte[] v1 = "original".getBytes();
        byte[] v2 = "updated".getBytes();
        blob.upload(new java.io.ByteArrayInputStream(v1), v1.length, true);
        blob.upload(new java.io.ByteArrayInputStream(v2), v2.length, true);

        assertArrayEquals(v2, blob.downloadContent().toBytes());

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("blob metadata: upload, update, get properties, and list with metadata")
    void blobMetadataRoundTrip() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        BlobClient blob = container.getBlobClient("metadata.txt");

        byte[] content = "metadata".getBytes(StandardCharsets.UTF_8);
        blob.getBlockBlobClient().uploadWithResponse(
            new java.io.ByteArrayInputStream(content),
            content.length,
            null,
            Map.of("owner", "compat"),
            null,
            null,
            null,
            null,
            Context.NONE);
        assertEquals(Map.of("owner", "compat"), blob.getProperties().getMetadata());

        blob.setMetadata(Map.of("owner", "updated", "purpose", "blob-parity"));
        assertEquals(Map.of("owner", "updated", "purpose", "blob-parity"), blob.getProperties().getMetadata());

        List<BlobItem> listed = container.listBlobs().stream().toList();
        assertEquals(1, listed.size());
        assertEquals("metadata.txt", listed.get(0).getName());

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("blob range download: returns requested byte slice")
    void blobRangeDownload() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        BlobClient blob = container.getBlobClient("range.txt");

        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);
        blob.upload(new java.io.ByteArrayInputStream(content), content.length, true);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        blob.downloadStreamWithResponse(out, new BlobRange(2, 4L), null, null, false, null, Context.NONE);
        assertEquals("2345", out.toString(StandardCharsets.UTF_8));

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("empty blob range download: invalid range includes content range")
    void emptyBlobRangeDownloadReturnsContentRange() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        BlobClient blob = container.getBlobClient("empty-range.txt");

        blob.upload(new java.io.ByteArrayInputStream(new byte[0]), 0, true);

        BlobStorageException ex = assertThrows(BlobStorageException.class,
            () -> blob.downloadStreamWithResponse(new ByteArrayOutputStream(), new BlobRange(0, 1L), null, null, false, null, Context.NONE));
        assertEquals(BlobErrorCode.INVALID_RANGE, ex.getErrorCode());
        assertEquals(416, ex.getStatusCode());
        assertEquals("bytes */0", ex.getResponse().getHeaders().getValue("Content-Range"));

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("empty blob stream: reads to end without an error")
    void emptyBlobInputStreamReadsToEnd() throws Exception {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        BlobClient blob = container.getBlobClient("empty-stream.txt");
        blob.upload(new ByteArrayInputStream(new byte[0]), 0, true);

        try (InputStream input = blob.openInputStream(
                new BlobInputStreamOptions().setRange(new BlobRange(0)))) {
            assertEquals(-1, input.read());
        }

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("hierarchical namespace: container root exists as a blob path")
    void hierarchicalNamespaceRootExistsAsBlobPath() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        assertTrue(container.getBlobClient("/").exists());
        client.deleteBlobContainer(name);

        String account = "flataccount";
        BlobServiceClient flatClient = new BlobServiceClientBuilder()
                .endpoint(EmulatorConfig.httpBase() + "/" + account)
                .credential(new StorageSharedKeyCredential(account, EmulatorConfig.DEV_KEY))
                .buildClient();
        BlobContainerClient flatContainer = flatClient.createBlobContainer(containerName());
        assertFalse(flatContainer.getBlobClient("/").exists());
        flatContainer.delete();
    }

    @Test
    @DisplayName("blob paths: dot segments resolve within the container")
    void blobPathsResolveDotSegmentsWithinContainer() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        byte[] content = "normalized".getBytes(StandardCharsets.UTF_8);

        container.getBlobClient("a/../b").upload(
                new ByteArrayInputStream(content), content.length, true);
        assertArrayEquals(content, container.getBlobClient("b").downloadContent().toBytes());

        BlobStorageException failure = assertThrows(BlobStorageException.class,
                () -> container.getBlobClient("../file").upload(
                        new ByteArrayInputStream(content), content.length, true));
        assertEquals(400, failure.getStatusCode());

        BlobStorageException rootFailure = assertThrows(BlobStorageException.class,
                () -> container.getBlobClient("a/..").upload(
                        new ByteArrayInputStream(content), content.length, true));
        assertEquals(400, rootFailure.getStatusCode());
        assertEquals(BlobErrorCode.INVALID_URI, rootFailure.getErrorCode());

        client.deleteBlobContainer(name);
    }

    // --- Block Blob ---

    @Test
    @DisplayName("block blob: stage blocks, commit, download reassembled content")
    void blockBlobStageAndCommit() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        BlockBlobClient blockBlob = container.getBlobClient("multipart.bin").getBlockBlobClient();

        // Prepare 3 blocks (each ~10 bytes for simplicity)
        byte[] part1 = "Hello, ".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "Block ".getBytes(StandardCharsets.UTF_8);
        byte[] part3 = "World!".getBytes(StandardCharsets.UTF_8);

        String id1 = Base64.getEncoder().encodeToString("block-001".getBytes(StandardCharsets.UTF_8));
        String id2 = Base64.getEncoder().encodeToString("block-002".getBytes(StandardCharsets.UTF_8));
        String id3 = Base64.getEncoder().encodeToString("block-003".getBytes(StandardCharsets.UTF_8));

        // Stage blocks
        blockBlob.stageBlock(id1, new java.io.ByteArrayInputStream(part1), part1.length);
        blockBlob.stageBlock(id2, new java.io.ByteArrayInputStream(part2), part2.length);
        blockBlob.stageBlock(id3, new java.io.ByteArrayInputStream(part3), part3.length);

        // Commit in order
        blockBlob.commitBlockList(List.of(id1, id2, id3));

        // Download and verify reassembled content
        byte[] downloaded = blockBlob.downloadContent().toBytes();
        assertEquals("Hello, Block World!", new String(downloaded, StandardCharsets.UTF_8));

        // Verify blob appears in listing
        List<BlobItem> blobs = container.listBlobs().stream().toList();
        assertEquals(1, blobs.size());
        assertEquals("multipart.bin", blobs.get(0).getName());

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("block blob: get block list — uncommitted before commit, committed after")
    void blockBlobGetBlockList() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        BlockBlobClient blockBlob = container.getBlobClient("listed.bin").getBlockBlobClient();

        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        String id1 = Base64.getEncoder().encodeToString("blk1".getBytes(StandardCharsets.UTF_8));
        String id2 = Base64.getEncoder().encodeToString("blk2".getBytes(StandardCharsets.UTF_8));

        blockBlob.stageBlock(id1, new java.io.ByteArrayInputStream(data), data.length);
        blockBlob.stageBlock(id2, new java.io.ByteArrayInputStream(data), data.length);

        // Before commit: both blocks are uncommitted
        var beforeCommit = blockBlob.listBlocks(BlockListType.ALL);
        assertEquals(0, beforeCommit.getCommittedBlocks().size(),
                "no committed blocks before commit");
        assertEquals(2, beforeCommit.getUncommittedBlocks().size(),
                "two uncommitted blocks staged");

        // Commit
        blockBlob.commitBlockList(List.of(id1, id2));

        // After commit: blocks are committed, none uncommitted
        var afterCommit = blockBlob.listBlocks(BlockListType.ALL);
        assertEquals(2, afterCommit.getCommittedBlocks().size(),
                "two committed blocks after commit");
        assertEquals(0, afterCommit.getUncommittedBlocks().size(),
                "no uncommitted blocks after commit");

        // Verify the committed block names match what we staged
        List<String> committedNames = afterCommit.getCommittedBlocks().stream()
                .map(b -> b.getName())
                .toList();
        assertTrue(committedNames.contains(id1), "id1 in committed list");
        assertTrue(committedNames.contains(id2), "id2 in committed list");

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("blob conditional download: rejects wrong etag")
    void blobConditionalDownloadRejectsWrongEtag() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        BlobClient blob = container.getBlobClient("conditional.txt");

        byte[] content = "condition".getBytes(StandardCharsets.UTF_8);
        blob.upload(new java.io.ByteArrayInputStream(content), content.length, true);
        String etag = blob.getProperties().getETag();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        blob.downloadStreamWithResponse(out, null, null, new BlobRequestConditions().setIfMatch(etag), false, null, Context.NONE);
        assertEquals("condition", out.toString(StandardCharsets.UTF_8));

        BlobStorageException ex = assertThrows(BlobStorageException.class,
            () -> blob.downloadContentWithResponse(null, new BlobRequestConditions().setIfMatch("wrong-etag"), null, Context.NONE));
        assertEquals(412, ex.getStatusCode());

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("service properties: getProperties returns StorageServiceProperties XML")
    void getServiceProperties() {
        BlobServiceProperties props = client.getProperties();

        assertNotNull(props);
        assertNotNull(props.getLogging());
        assertNotNull(props.getHourMetrics());
        assertNotNull(props.getMinuteMetrics());
    }

    // --- Leases (the WebJobs/Durable Functions host backplane primitive) ---

    private BlobClient leasedBlob(BlobContainerClient container, String blobName) {
        BlobClient blob = container.getBlobClient(blobName);
        byte[] content = "lock".getBytes(StandardCharsets.UTF_8);
        blob.upload(new java.io.ByteArrayInputStream(content), content.length, true);
        return blob;
    }

    @Test
    @DisplayName("blob lease lifecycle: acquire → renew → release")
    void blobLeaseLifecycle() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        BlobClient blob = leasedBlob(container, "singleton-lock");
        BlobLeaseClient lease = new BlobLeaseClientBuilder().blobClient(blob).buildClient();

        String leaseId = lease.acquireLease(-1);
        assertNotNull(leaseId);

        BlobProperties props = blob.getProperties();
        assertEquals(LeaseStatusType.LOCKED, props.getLeaseStatus());
        assertEquals(LeaseStateType.LEASED, props.getLeaseState());

        assertEquals(leaseId, lease.renewLease());
        lease.releaseLease();

        props = blob.getProperties();
        assertEquals(LeaseStatusType.UNLOCKED, props.getLeaseStatus());
        assertEquals(LeaseStateType.AVAILABLE, props.getLeaseState());

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("leased blob: writes require the lease id")
    void leasedBlobWriteGuards() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        BlobClient blob = leasedBlob(container, "guarded");
        BlobLeaseClient lease = new BlobLeaseClientBuilder().blobClient(blob).buildClient();
        String leaseId = lease.acquireLease(-1);

        BlobStorageException ex = assertThrows(BlobStorageException.class,
            () -> blob.setMetadata(Map.of("owner", "nobody")));
        assertEquals(412, ex.getStatusCode());
        assertEquals(BlobErrorCode.LEASE_ID_MISSING, ex.getErrorCode());

        blob.setMetadataWithResponse(Map.of("owner", "host-a"),
            new BlobRequestConditions().setLeaseId(leaseId), null, Context.NONE);
        assertEquals("host-a", blob.getProperties().getMetadata().get("owner"));

        lease.releaseLease();
        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("competing acquire fails, then succeeds after break (lease steal)")
    void leaseStealViaBreak() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        BlobClient blob = leasedBlob(container, "partition-lease");
        BlobLeaseClient first = new BlobLeaseClientBuilder().blobClient(blob).buildClient();
        first.acquireLease(-1);

        BlobLeaseClient second = new BlobLeaseClientBuilder().blobClient(blob).buildClient();
        BlobStorageException ex = assertThrows(BlobStorageException.class,
            () -> second.acquireLease(-1));
        assertEquals(409, ex.getStatusCode());
        assertEquals(BlobErrorCode.LEASE_ALREADY_PRESENT, ex.getErrorCode());

        first.breakLease();
        assertNotNull(second.acquireLease(-1));

        client.deleteBlobContainer(name);
    }

    @Test
    @DisplayName("change lease: ownership handoff to a proposed id")
    void changeLeaseHandsOff() {
        String name = containerName();
        BlobContainerClient container = client.createBlobContainer(name);
        BlobClient blob = leasedBlob(container, "handoff");
        BlobLeaseClient lease = new BlobLeaseClientBuilder().blobClient(blob).buildClient();
        lease.acquireLease(-1);

        String proposed = UUID.randomUUID().toString();
        assertEquals(proposed, lease.changeLease(proposed));

        client.deleteBlobContainer(name);
    }
}
