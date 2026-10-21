package io.floci.az.services.vm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.vm.VmModels.VirtualMachine;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(VmHandlerTest.MockedProfile.class)
@DisplayName("VM: one store keyed by canonical id")
class VmResourceStoreTest {

    private static final String API = "?api-version=2024-07-01";
    private static final String BODY =
        "{\"location\":\"eastus\",\"properties\":{\"hardwareProfile\":{\"vmSize\":\"Standard_B1s\"}}}";

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String vm(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg
            + "/providers/Microsoft.Compute/virtualMachines/" + name;
    }

    @Test
    void aPutThroughOtherCasingUpdatesTheOneVm() {
        given().contentType("application/json").body(BODY).when().put(vm("sub-case", "RG-Case", "Vm1") + API)
            .then().statusCode(201);
        given().contentType("application/json").body(BODY).when().put(vm("sub-case", "rg-case", "vm1") + API)
            .then().statusCode(200)
            .body("id", equalTo(vm("sub-case", "RG-Case", "Vm1")));

        given().when().get("/subscriptions/sub-case/resourceGroups/rg-case/providers/Microsoft.Compute"
                + "/virtualMachines" + API)
            .then().statusCode(200).body("value", hasSize(1));
    }

    @Test
    void aVmStoredUnderTheOldKeyIsServedAndMovesOnItsFirstWrite() throws Exception {
        VirtualMachine legacy = new VirtualMachine();
        legacy.setSubscriptionId("Sub-Legacy");
        legacy.setResourceGroup("RG-Legacy");
        legacy.setName("legacyvm");
        legacy.setLocation("eastus");
        legacy.setProvisioningState("Succeeded");
        legacy.setTimeCreated(Instant.now());
        legacy.setVmId("00000000-0000-0000-0000-00000000abcd");
        StorageBackend<String, StoredObject> backend = storageFactory.create("vm");
        String oldKey = "Sub-Legacy/RG-Legacy/legacyvm";
        backend.put(oldKey, new StoredObject(oldKey,
            new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsBytes(legacy), Map.of(), Instant.now(),
            oldKey));

        given().when().get(vm("sub-legacy", "rg-legacy", "legacyvm") + API).then().statusCode(200)
            .body("name", equalTo("legacyvm"));
        given().when().get("/subscriptions/Sub-Legacy/providers/Microsoft.Compute/virtualMachines" + API)
            .then().statusCode(200).body("value", hasSize(1));

        given().contentType("application/json").body("{\"tags\":{\"env\":\"t\"}}")
            .when().patch(vm("Sub-Legacy", "RG-Legacy", "legacyvm") + API).then().statusCode(200);
        assertFalse(backend.get(oldKey).isPresent(), "the write moved the entry off its old key");
        assertTrue(backend.keys().stream()
            .anyMatch(key -> key.equals(vm("sub-legacy", "rg-legacy", "legacyvm").toLowerCase())));
    }
}
