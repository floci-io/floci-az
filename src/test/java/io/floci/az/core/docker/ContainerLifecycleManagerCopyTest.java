package io.floci.az.core.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import io.floci.az.config.EmulatorConfig;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Files injected before start must reach the container in one archive upload. On Podman, an
 * upload into a volume mount point of a created container lands under the mount point, and the
 * volume is filled from there once, on the second upload: anything uploaded from then on is
 * hidden by the volume at start (#472).
 */
@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerCopyTest {

    @Mock
    private DockerClient dockerClient;

    @Mock
    private ImageCacheService imageCacheService;

    @Mock
    private ContainerDetector containerDetector;

    @Mock
    private EmulatorConfig config;

    @Mock
    private CopyArchiveToContainerCmd copyCmd;

    private ContainerLifecycleManager manager;

    @BeforeEach
    void setUp() {
        manager = new ContainerLifecycleManager(dockerClient, imageCacheService, containerDetector, config);
        when(dockerClient.copyArchiveToContainerCmd("container-id")).thenReturn(copyCmd);
        when(copyCmd.withRemotePath("/")).thenReturn(copyCmd);
    }

    @Test
    void everyFileTravelsInTheSameArchiveUpload() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("/var/lib/artemis-instance/etc-override/broker.xml", bytes("<configuration/>"));
        files.put("/var/lib/artemis-instance/etc-override/artemis.p12", new byte[] {1, 2, 3});
        files.put("/var/lib/artemis-instance/lib/floci-az-artemis-extension.jar", bytes("jar"));
        ArgumentCaptor<InputStream> archive = ArgumentCaptor.forClass(InputStream.class);
        when(copyCmd.withTarInputStream(archive.capture())).thenReturn(copyCmd);

        manager.copyFilesToContainer("container-id", files);

        verify(dockerClient, times(1)).copyArchiveToContainerCmd("container-id");
        verify(copyCmd, times(1)).exec();
        assertEquals(Map.of(
                "var/lib/artemis-instance/etc-override/broker.xml", "<configuration/>",
                "var/lib/artemis-instance/etc-override/artemis.p12", new String(new byte[] {1, 2, 3},
                        StandardCharsets.UTF_8),
                "var/lib/artemis-instance/lib/floci-az-artemis-extension.jar", "jar"),
                entries(archive.getValue()));
    }

    @Test
    void aSingleFileCopyStillUploadsOneArchive() throws IOException {
        ArgumentCaptor<InputStream> archive = ArgumentCaptor.forClass(InputStream.class);
        when(copyCmd.withTarInputStream(archive.capture())).thenReturn(copyCmd);

        manager.copyFileToContainer("container-id", "SELECT 1;", "/docker-entrypoint-initdb.d/10.sql");

        verify(copyCmd, times(1)).exec();
        assertEquals(Map.of("docker-entrypoint-initdb.d/10.sql", "SELECT 1;"), entries(archive.getValue()));
    }

    private static Map<String, String> entries(InputStream archive) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(archive)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(tar.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
