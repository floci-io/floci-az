package io.floci.az.compat;

import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.storage.file.datalake.DataLakeFileClient;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import com.azure.storage.file.datalake.DataLakeServiceClient;
import com.azure.storage.file.datalake.DataLakeServiceClientBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Data Lake Storage Compatibility")
class DataLakeCompatibilityTest {

    private DataLakeServiceClient client;

    @BeforeAll
    void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        client = new DataLakeServiceClientBuilder()
                .endpoint(EmulatorConfig.httpBase())
                .credential(new StorageSharedKeyCredential(EmulatorConfig.ACCOUNT, EmulatorConfig.DEV_KEY))
                .addPolicy((context, next) -> {
                    context.getHttpRequest().setHeader("Host", EmulatorConfig.ACCOUNT + ".dfs.core.windows.net");
                    return next.process();
                })
                .buildClient();
    }

    @Test
    @DisplayName("file create: creates path through dfs endpoint")
    void fileCreateUsesDfsEndpoint() {
        String name = "test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DataLakeFileSystemClient fileSystem = client.createFileSystem(name);

        DataLakeFileClient file = fileSystem.createFile("dir/file.txt");

        assertTrue(file.exists());

        client.deleteFileSystem(name);
    }
}
