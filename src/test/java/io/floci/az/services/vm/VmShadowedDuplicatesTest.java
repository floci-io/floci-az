package io.floci.az.services.vm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.vm.VmModels.VirtualMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("VM: duplicates the old casing bug left behind are dropped with their containers")
class VmShadowedDuplicatesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static VirtualMachine vm(String name, String vmId) {
        VirtualMachine vm = new VirtualMachine();
        vm.setSubscriptionId("sub-dup");
        vm.setResourceGroup("rg");
        vm.setName(name);
        vm.setVmId(vmId);
        vm.setLocation("eastus");
        vm.setProvisioningState("Succeeded");
        vm.setTimeCreated(Instant.now());
        vm.setContainerId("container-" + vmId);
        return vm;
    }

    private static void put(InMemoryStorage<String, StoredObject> backend, String key, VirtualMachine vm,
                            Instant written) throws Exception {
        backend.put(key, new StoredObject(key, MAPPER.writeValueAsBytes(vm), Map.of(), written, "etag"));
    }

    @Test
    void theOlderCasingIsDroppedWithItsContainerAndTheNewerStays() throws Exception {
        InMemoryStorage<String, StoredObject> backend = new InMemoryStorage<>();
        put(backend, "sub-dup/rg/VM1", vm("VM1", "11111111-1111-1111-1111-111111111111"), Instant.now().minusSeconds(60));
        put(backend, "sub-dup/rg/vm1", vm("vm1", "22222222-2222-2222-2222-222222222222"), Instant.now());
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create("vm")).thenReturn(backend);
        VmContainerManager containers = mock(VmContainerManager.class);
        VmHandler handler = new VmHandler(mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), containers, factory);

        handler.dropShadowedVms();

        verify(containers).removeVm(argThat(vm -> "VM1".equals(vm.getName())));
        verify(containers, never()).removeVm(argThat(vm -> "vm1".equals(vm.getName())));
        assertFalse(backend.get("sub-dup/rg/VM1").isPresent(), "the shadowed duplicate is dropped");
        assertTrue(backend.get("sub-dup/rg/vm1").isPresent(), "the served copy stays");
    }
}
