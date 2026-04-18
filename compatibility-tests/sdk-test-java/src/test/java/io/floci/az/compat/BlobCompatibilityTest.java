package io.floci.az.compat;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobStorageException;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Blob Storage Compatibility")
class BlobCompatibilityTest {

    private BlobServiceClient client;

    @BeforeAll
    void setup() {
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
}
