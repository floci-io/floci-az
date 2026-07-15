package io.floci.az.compat;

import com.azure.core.credential.AccessToken;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.azure.core.http.rest.PagedResponse;
import com.azure.core.util.Context;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.storage.file.datalake.DataLakeDirectoryClient;
import com.azure.storage.file.datalake.DataLakeFileClient;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import com.azure.storage.file.datalake.DataLakeServiceClient;
import com.azure.storage.file.datalake.DataLakeServiceClientBuilder;
import com.azure.storage.file.datalake.models.DataLakeStorageException;
import com.azure.storage.file.datalake.models.ListPathsOptions;
import com.azure.storage.file.datalake.models.PathItem;
import com.azure.storage.file.datalake.models.UserDelegationKey;
import com.azure.storage.file.datalake.sas.DataLakeServiceSasSignatureValues;
import com.azure.storage.file.datalake.sas.FileSystemSasPermission;
import com.azure.storage.file.datalake.sas.PathSasPermission;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    @DisplayName("user delegation SAS: path scope and permissions are enforced")
    void userDelegationSasEnforcesPathScopeAndPermissions() {
        OffsetDateTime start = OffsetDateTime.now().minusMinutes(5);
        OffsetDateTime expiry = OffsetDateTime.now().plusHours(1);
        DataLakeServiceClient bearerClient = new DataLakeServiceClientBuilder()
                .endpoint(EmulatorConfig.httpBase())
                .credential(request -> Mono.just(new AccessToken("fake-token", expiry)))
                .addPolicy(dfsHostPolicy())
                .buildClient();
        UserDelegationKey key = bearerClient.getUserDelegationKey(start, expiry);

        String name = "test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DataLakeFileSystemClient fileSystem = bearerClient.createFileSystem(name);
        fileSystem.createFile("allowed.txt");
        fileSystem.createFile("denied.txt");

        DataLakeServiceSasSignatureValues pathValues = new DataLakeServiceSasSignatureValues(
                expiry, new PathSasPermission().setReadPermission(true))
                .setStartTime(start);
        String pathSas = fileSystem.getFileClient("allowed.txt")
                .generateUserDelegationSas(pathValues, key, EmulatorConfig.ACCOUNT, Context.NONE);
        DataLakeServiceClient pathSasClient = new DataLakeServiceClientBuilder()
                .endpoint(EmulatorConfig.httpBase())
                .sasToken(pathSas)
                .addPolicy(dfsHostPolicy())
                .buildClient();

        assertTrue(pathSasClient.getFileSystemClient(name).getFileClient("allowed.txt").exists());
        DataLakeStorageException siblingFailure = assertThrows(DataLakeStorageException.class,
                () -> pathSasClient.getFileSystemClient(name).getFileClient("denied.txt").exists());
        assertEquals(403, siblingFailure.getStatusCode());

        DataLakeServiceSasSignatureValues readOnlyValues = new DataLakeServiceSasSignatureValues(
                expiry, new FileSystemSasPermission().setReadPermission(true))
                .setStartTime(start);
        String readOnlySas = fileSystem.generateUserDelegationSas(
                readOnlyValues, key, EmulatorConfig.ACCOUNT, Context.NONE);
        DataLakeServiceClient readOnlyClient = new DataLakeServiceClientBuilder()
                .endpoint(EmulatorConfig.httpBase())
                .sasToken(readOnlySas)
                .addPolicy(dfsHostPolicy())
                .buildClient();
        DataLakeStorageException permissionFailure = assertThrows(DataLakeStorageException.class,
                () -> readOnlyClient.getFileSystemClient(name).createFile("cannot-create.txt"));
        assertEquals(403, permissionFailure.getStatusCode());

        bearerClient.deleteFileSystem(name);
    }

    @Test
    @DisplayName("listPaths: recursive, non-recursive, directory, and continuation behavior")
    void listPathsSupportsSdkOptionsAndContinuation() {
        String name = "test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DataLakeFileSystemClient fileSystem = client.createFileSystem(name);
        createSdkPaths(fileSystem);

        List<PathItem> nonRecursive = pathItems(fileSystem.listPaths(
                new ListPathsOptions().setRecursive(false), null));
        assertEquals(List.of("dir", "root.txt"), names(nonRecursive));
        assertTrue(nonRecursive.stream().filter(item -> item.getName().equals("dir")).findFirst().orElseThrow()
                .isDirectory());

        List<PathItem> recursive = pathItems(fileSystem.listPaths(
                new ListPathsOptions().setRecursive(true), null));
        assertEquals(List.of("dir/file.txt", "dir/sub/leaf.txt", "root.txt"), names(recursive));

        List<PathItem> directory = pathItems(fileSystem.listPaths(
                new ListPathsOptions().setPath("dir").setRecursive(false), null));
        assertEquals(List.of("dir/file.txt", "dir/sub"), names(directory));

        List<String> firstPage = new ArrayList<>();
        String continuation = null;
        for (PagedResponse<PathItem> page : fileSystem.listPaths(
                new ListPathsOptions().setRecursive(true).setMaxResults(2), null).iterableByPage()) {
            firstPage.addAll(names(page.getValue()));
            continuation = page.getContinuationToken();
            break;
        }
        assertEquals(List.of("dir/file.txt", "dir/sub/leaf.txt"), firstPage);
        assertNotNull(continuation);

        client.deleteFileSystem(name);
    }

    @Test
    @DisplayName("listPaths: user delegation SAS list scope is enforced")
    void listPathsEnforcesSasListScope() {
        OffsetDateTime start = OffsetDateTime.now().minusMinutes(5);
        OffsetDateTime expiry = OffsetDateTime.now().plusHours(1);
        DataLakeServiceClient bearerClient = bearerClient(expiry);
        UserDelegationKey key = bearerClient.getUserDelegationKey(start, expiry);

        String name = "test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DataLakeFileSystemClient fileSystem = bearerClient.createFileSystem(name);
        createSdkPaths(fileSystem);

        String filesystemSas = fileSystem.generateUserDelegationSas(
                new DataLakeServiceSasSignatureValues(expiry,
                        new FileSystemSasPermission().setListPermission(true)).setStartTime(start),
                key, EmulatorConfig.ACCOUNT, Context.NONE);
        DataLakeFileSystemClient sasFileSystem = sasClient(filesystemSas).getFileSystemClient(name);
        assertEquals(List.of("dir/file.txt", "dir/sub/leaf.txt", "root.txt"), names(pathItems(
                sasFileSystem.listPaths(new ListPathsOptions().setRecursive(true), null))));

        String readOnlySas = fileSystem.generateUserDelegationSas(
                new DataLakeServiceSasSignatureValues(expiry,
                        new FileSystemSasPermission().setReadPermission(true)).setStartTime(start),
                key, EmulatorConfig.ACCOUNT, Context.NONE);
        DataLakeStorageException permissionFailure = assertThrows(DataLakeStorageException.class,
                () -> pathItems(sasClient(readOnlySas).getFileSystemClient(name)
                        .listPaths(new ListPathsOptions().setRecursive(true), null)));
        assertEquals(403, permissionFailure.getStatusCode());

        DataLakeDirectoryClient directoryClient = fileSystem.getDirectoryClient("dir");
        String directorySas = directoryClient.generateUserDelegationSas(
                new DataLakeServiceSasSignatureValues(expiry,
                        new PathSasPermission().setListPermission(true)).setStartTime(start),
                key, EmulatorConfig.ACCOUNT, Context.NONE);
        DataLakeFileSystemClient directorySasFileSystem = sasClient(directorySas).getFileSystemClient(name);
        assertEquals(List.of("dir/file.txt", "dir/sub/leaf.txt"), names(pathItems(
                directorySasFileSystem.listPaths(new ListPathsOptions().setPath("dir").setRecursive(true), null))));
        DataLakeStorageException outsideScope = assertThrows(DataLakeStorageException.class,
                () -> pathItems(directorySasFileSystem.listPaths(new ListPathsOptions().setRecursive(true), null)));
        assertEquals(403, outsideScope.getStatusCode());

        bearerClient.deleteFileSystem(name);
    }

    @Test
    @DisplayName("vended SAS property map: builds DataLake SDK client without SharedKey")
    void vendedSasPropertyMapBuildsSdkClientWithoutSharedKey() {
        OffsetDateTime start = OffsetDateTime.now().minusMinutes(5);
        OffsetDateTime expiry = OffsetDateTime.now().plusHours(1);
        DataLakeServiceClient bearerClient = bearerClient(expiry);
        UserDelegationKey key = bearerClient.getUserDelegationKey(start, expiry);

        String name = "test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DataLakeFileSystemClient fileSystem = bearerClient.createFileSystem(name);
        String sas = fileSystem.generateUserDelegationSas(
                new DataLakeServiceSasSignatureValues(expiry, new FileSystemSasPermission()
                        .setCreatePermission(true)
                        .setReadPermission(true)
                        .setWritePermission(true)
                        .setListPermission(true))
                        .setStartTime(start),
                key, EmulatorConfig.ACCOUNT, Context.NONE);

        Map<String, String> properties = Map.of(
                "adls.account-name", EmulatorConfig.ACCOUNT,
                "adls.sas-token." + EmulatorConfig.ACCOUNT + ".dfs.core.windows.net", "expired-token",
                "adls.sas-token-expires-at-ms." + EmulatorConfig.ACCOUNT + ".dfs.core.windows.net",
                Long.toString(System.currentTimeMillis() - 1),
                "adls.sas-token." + EmulatorConfig.ACCOUNT, sas,
                "adls.sas-token", "unused-fallback"
        );

        DataLakeServiceClient vendedClient = vendedClient(properties);
        DataLakeFileSystemClient vendedFileSystem = vendedClient.getFileSystemClient(name);
        vendedFileSystem.createFile("vended/file.txt");

        assertTrue(vendedFileSystem.getFileClient("vended/file.txt").exists());
        assertEquals(List.of("vended/file.txt"), names(pathItems(
                vendedFileSystem.listPaths(new ListPathsOptions().setRecursive(true), null))));

        bearerClient.deleteFileSystem(name);
    }

    private static HttpPipelinePolicy dfsHostPolicy() {
        return (context, next) -> {
            context.getHttpRequest().setHeader("Host", EmulatorConfig.ACCOUNT + ".dfs.core.windows.net");
            return next.process();
        };
    }

    private static DataLakeServiceClient bearerClient(OffsetDateTime expiry) {
        return new DataLakeServiceClientBuilder()
                .endpoint(EmulatorConfig.httpBase())
                .credential(request -> Mono.just(new AccessToken("fake-token", expiry)))
                .addPolicy(dfsHostPolicy())
                .buildClient();
    }

    private static DataLakeServiceClient sasClient(String sas) {
        return new DataLakeServiceClientBuilder()
                .endpoint(EmulatorConfig.httpBase())
                .sasToken(sas)
                .addPolicy(dfsHostPolicy())
                .buildClient();
    }

    private static DataLakeServiceClient vendedClient(Map<String, String> properties) {
        String account = properties.get("adls.account-name");
        String host = account + ".dfs.core.windows.net";
        String sas = resolveVendedSas(properties, account, host, System.currentTimeMillis());
        return sasClient(sas);
    }

    private static String resolveVendedSas(Map<String, String> properties, String account, String host, long nowMs) {
        for (String key : List.of("adls.sas-token." + host, "adls.sas-token." + account, "adls.sas-token")) {
            String value = properties.get(key);
            if (value != null && !isExpired(properties, key, nowMs)) {
                return value;
            }
        }
        throw new IllegalArgumentException("No unexpired ADLS SAS token property found");
    }

    private static boolean isExpired(Map<String, String> properties, String tokenKey, long nowMs) {
        String expiresAt = properties.get(tokenKey.replace("adls.sas-token", "adls.sas-token-expires-at-ms"));
        return expiresAt != null && Long.parseLong(expiresAt) <= nowMs;
    }

    private static void createSdkPaths(DataLakeFileSystemClient fileSystem) {
        fileSystem.createFile("root.txt");
        fileSystem.createFile("dir/file.txt");
        fileSystem.createFile("dir/sub/leaf.txt");
    }

    private static List<PathItem> pathItems(Iterable<PathItem> items) {
        List<PathItem> result = new ArrayList<>();
        items.forEach(result::add);
        return result;
    }

    private static List<String> names(List<PathItem> items) {
        return items.stream().map(PathItem::getName).toList();
    }
}
