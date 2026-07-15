package io.floci.az.compat;

import com.azure.core.credential.AccessToken;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.azure.core.util.Context;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.storage.file.datalake.DataLakeFileClient;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import com.azure.storage.file.datalake.DataLakeServiceClient;
import com.azure.storage.file.datalake.DataLakeServiceClientBuilder;
import com.azure.storage.file.datalake.models.UserDelegationKey;
import com.azure.storage.file.datalake.sas.DataLakeServiceSasSignatureValues;
import com.azure.storage.file.datalake.sas.FileSystemSasPermission;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
                .addPolicy(dfsHostPolicy())
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

    @Test
    @DisplayName("user delegation key: SDK-generated SAS can create path through dfs endpoint")
    void userDelegationKeyCanGenerateUsableDfsSas() {
        OffsetDateTime start = OffsetDateTime.now().minusMinutes(5);
        OffsetDateTime expiry = OffsetDateTime.now().plusHours(1);
        DataLakeServiceClient bearerClient = new DataLakeServiceClientBuilder()
                .endpoint(EmulatorConfig.httpBase())
                .credential(request -> Mono.just(new AccessToken("fake-token", expiry)))
                .addPolicy(dfsHostPolicy())
                .buildClient();

        UserDelegationKey key = bearerClient.getUserDelegationKey(start, expiry);
        assertEquals("b", key.getSignedService());
        assertNotNull(key.getValue());

        String name = "test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DataLakeFileSystemClient fileSystem = bearerClient.createFileSystem(name);
        FileSystemSasPermission permissions = new FileSystemSasPermission()
                .setCreatePermission(true)
                .setReadPermission(true)
                .setWritePermission(true);
        DataLakeServiceSasSignatureValues values = new DataLakeServiceSasSignatureValues(expiry, permissions)
                .setStartTime(start);
        String sas = fileSystem.generateUserDelegationSas(values, key, EmulatorConfig.ACCOUNT, Context.NONE);
        assertTrue(sas.contains("sig="));

        DataLakeServiceClient sasClient = new DataLakeServiceClientBuilder()
                .endpoint(EmulatorConfig.httpBase())
                .sasToken(sas)
                .addPolicy(dfsHostPolicy())
                .buildClient();
        DataLakeFileSystemClient sasFileSystem = sasClient.getFileSystemClient(name);
        DataLakeFileClient file = sasFileSystem.createFile("dir/sas-file.txt");

        assertTrue(file.exists());

        bearerClient.deleteFileSystem(name);
    }

    private static HttpPipelinePolicy dfsHostPolicy() {
        return (context, next) -> {
            context.getHttpRequest().setHeader("Host", EmulatorConfig.ACCOUNT + ".dfs.core.windows.net");
            return next.process();
        };
    }
}
