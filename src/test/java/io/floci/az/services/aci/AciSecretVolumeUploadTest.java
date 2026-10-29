package io.floci.az.services.aci;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.PortAllocator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Secret volume files are written between create and start. They must travel in one archive
 * upload: Podman hides a file uploaded into a volume from the second upload on (#472).
 */
class AciSecretVolumeUploadTest {

    @Test
    void everySecretFileOfAContainerIsUploadedInOneArchiveBeforeStart() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        ContainerSpec spec = new ContainerSpec("busybox");

        when(config.services()).thenReturn(services);
        when(services.dockerNetwork()).thenReturn(Optional.empty());
        when(containerBuilder.newContainer("busybox")).thenReturn(builder);
        when(builder.build()).thenReturn(spec);
        when(lifecycleManager.create(spec)).thenReturn("container-id");
        when(lifecycleManager.startCreated("container-id", spec))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo("container-id", Map.of()));

        AciContainerGroupManager manager = new AciContainerGroupManager(containerBuilder, lifecycleManager,
                mock(PortAllocator.class), mock(ContainerDetector.class), config);
        manager.startGroup(group());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, byte[]>> files = ArgumentCaptor.forClass(Map.class);
        InOrder order = inOrder(lifecycleManager);
        order.verify(lifecycleManager).copyFilesToContainer(eq("container-id"), files.capture());
        order.verify(lifecycleManager).startCreated("container-id", spec);
        verify(lifecycleManager, never()).copyBytesToContainer(anyString(), any(), anyString());
        Map<String, String> uploaded = new LinkedHashMap<>();
        files.getValue().forEach((path, bytes) -> uploaded.put(path, new String(bytes, StandardCharsets.UTF_8)));
        assertEquals(Map.of(
                "/mnt/config/app.json", "{}",
                "/mnt/config/token", "abc",
                "/mnt/tls/key.pem", "key"), uploaded);
    }

    private static AciModels.ContainerGroup group() {
        AciModels.ContainerGroup group = new AciModels.ContainerGroup();
        group.setSubscriptionId("sub");
        group.setResourceGroup("rg");
        group.setName("cg-secrets");
        group.setLocation("eastus");
        group.setProperties(Map.of(
                "containers", List.of(Map.of(
                        "name", "app",
                        "properties", Map.of(
                                "image", "busybox",
                                "volumeMounts", List.of(
                                        Map.of("name", "config", "mountPath", "/mnt/config"),
                                        Map.of("name", "tls", "mountPath", "/mnt/tls"))))),
                "volumes", List.of(
                        Map.of("name", "config", "secret", Map.of(
                                "app.json", base64("{}"),
                                "token", base64("abc"))),
                        Map.of("name", "tls", "secret", Map.of("key.pem", base64("key"))))));
        return group;
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
