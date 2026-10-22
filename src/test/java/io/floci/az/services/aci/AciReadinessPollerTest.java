package io.floci.az.services.aci;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.aci.AciModels.ContainerGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ACI readiness poller: a DELETE or a replacing PUT during the check is not undone")
class AciReadinessPollerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final String KEY =
        "/subscriptions/sub-poll/resourcegroups/rg/providers/microsoft.containerinstance/containergroups/web";

    private final InMemoryStorage<String, StoredObject> backend = new InMemoryStorage<>();
    private final AciContainerGroupManager containers = mock(AciContainerGroupManager.class);
    private AciHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create("aci")).thenReturn(backend);
        handler = new AciHandler(mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), containers, factory);
        write(group("Creating", "old-container"));
    }

    private static ContainerGroup group(String state, String containerId) {
        ContainerGroup group = new ContainerGroup();
        group.setSubscriptionId("sub-poll");
        group.setResourceGroup("rg");
        group.setName("web");
        group.setLocation("eastus");
        group.setProvisioningState(state);
        group.setTimeCreated(Instant.now());
        group.setContainerIds(Map.of("web", containerId));
        return group;
    }

    private void write(ContainerGroup group) throws Exception {
        backend.put(KEY, new StoredObject(KEY, MAPPER.writeValueAsBytes(group), Map.of(), Instant.now(), "etag"));
    }

    private ContainerGroup stored() throws Exception {
        return MAPPER.readValue(backend.get(KEY).orElseThrow().data(), ContainerGroup.class);
    }

    @Test
    void aStartedGroupBecomesSucceeded() throws Exception {
        when(containers.allContainersStarted(any())).thenReturn(true);

        handler.markStartedGroups();

        assertEquals("Succeeded", stored().getProvisioningState());
    }

    @Test
    void aGroupDeletedDuringTheCheckStaysDeleted() {
        when(containers.allContainersStarted(any())).thenAnswer(invocation -> {
            backend.delete(KEY);
            return true;
        });

        handler.markStartedGroups();

        assertFalse(backend.get(KEY).isPresent(), "the poller wrote back a group deleted while it checked");
    }

    @Test
    void aGroupReplacedDuringTheCheckKeepsItsOwnState() throws Exception {
        when(containers.allContainersStarted(any())).thenAnswer(invocation -> {
            write(group("Creating", "new-container"));
            return true;
        });

        handler.markStartedGroups();

        ContainerGroup replaced = stored();
        assertEquals("Creating", replaced.getProvisioningState());
        assertEquals(Map.of("web", "new-container"), replaced.getContainerIds());
    }

    @Test
    void aDuplicateUnderAnOldCasingIsDroppedWithItsContainers() throws Exception {
        ContainerGroup older = group("Succeeded", "older-container");
        older.setResourceGroup("RG");
        ContainerGroup newer = group("Succeeded", "newer-container");
        newer.setResourceGroup("rg");
        backend.delete(KEY);
        backend.put("sub-poll/RG/web", new StoredObject("sub-poll/RG/web", MAPPER.writeValueAsBytes(older), Map.of(),
            Instant.now().minusSeconds(60), "etag"));
        backend.put("sub-poll/rg/web", new StoredObject("sub-poll/rg/web", MAPPER.writeValueAsBytes(newer), Map.of(),
            Instant.now(), "etag"));

        handler.dropShadowedGroups();

        verify(containers).removeGroup(argThat(group -> "sub-poll/RG/web".equals(group.containerKey())));
        verify(containers, never()).removeGroup(argThat(group -> "sub-poll/rg/web".equals(group.containerKey())));
        assertFalse(backend.get("sub-poll/RG/web").isPresent(), "the shadowed duplicate is dropped");
        assertTrue(backend.get("sub-poll/rg/web").isPresent(), "the served copy stays");
    }
}
