package io.floci.az.compat;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobClientBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobRange;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.BlockListType;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.specialized.BlockBlobClient;
import com.azure.core.util.Context;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
}
