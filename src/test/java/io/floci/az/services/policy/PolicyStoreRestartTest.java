package io.floci.az.services.policy;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.PersistentStorage;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.policy.PolicyPath.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PolicyStore: every policy resource type survives a restart of a persistent backend")
class PolicyStoreRestartTest {

    private static final TypeReference<Map<String, StoredObject>> TYPE_REF = new TypeReference<>() {};
    private static final String AUTH = "/providers/Microsoft.Authorization";
    private static final String SUB = "/subscriptions/sub-restart";
    private static final String DEFINITION = SUB + AUTH + "/policyDefinitions/Kept-Def";
    private static final String SET = SUB + AUTH + "/policySetDefinitions/kept-set";
    private static final String ASSIGNMENT = SUB + "/resourceGroups/rg" + AUTH + "/policyAssignments/kept-assign";
    private static final String EXEMPTION = SUB + "/resourceGroups/rg" + AUTH + "/policyExemptions/kept-exempt";

    @TempDir
    Path tempDir;

    private PolicyStore open(Path file) {
        PersistentStorage<String, StoredObject> backend = new PersistentStorage<>(file, TYPE_REF);
        backend.load();
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(PolicyStore.STORE)).thenReturn(backend);
        return new PolicyStore(factory);
    }

    private static void write(PolicyStore store, Type type, String id, Map<String, Object> properties) {
        store.createOrUpdate(type, id, existing -> Map.of("id", id, "name", id.substring(id.lastIndexOf('/') + 1),
            "type", type.armType(), "properties", properties));
    }

    @Test
    void readsListingsAndReferencesComeBackFromDisk() {
        Path file = tempDir.resolve("policy.json");
        PolicyStore first = open(file);
        write(first, Type.DEFINITION, DEFINITION, Map.of("policyType", "Custom"));
        write(first, Type.SET_DEFINITION, SET,
            Map.of("policyDefinitions", List.of(Map.of("policyDefinitionId", DEFINITION))));
        write(first, Type.ASSIGNMENT, ASSIGNMENT, Map.of("policyDefinitionId", SET));
        write(first, Type.EXEMPTION, EXEMPTION, Map.of("policyAssignmentId", ASSIGNMENT));

        PolicyStore restarted = open(file);

        assertEquals(DEFINITION, restarted.getDefinition(DEFINITION.toUpperCase()).get("id"));
        assertEquals(SET, restarted.getSetDefinition(SET).get("id"));
        assertEquals(ASSIGNMENT, restarted.getAssignment(ASSIGNMENT).get("id"));
        assertEquals(List.of(EXEMPTION), restarted.listExemptions().stream().map(e -> e.get("id")).toList());
        assertEquals(1, restarted.listDefinitions().size());
        assertEquals(Optional.of(SET), restarted.findSetDefinitionReferencing(DEFINITION.toLowerCase()));
        assertEquals(Optional.of(ASSIGNMENT), restarted.findAssignmentReferencing(SET));
    }

    @Test
    void anAssignmentsExemptionsRemovedAfterARestartStayRemoved() {
        Path file = tempDir.resolve("policy.json");
        PolicyStore first = open(file);
        write(first, Type.ASSIGNMENT, ASSIGNMENT, Map.of("policyDefinitionId", DEFINITION));
        write(first, Type.EXEMPTION, EXEMPTION, Map.of("policyAssignmentId", ASSIGNMENT));

        PolicyStore second = open(file);
        second.removeExemptionsForAssignment(ASSIGNMENT.toUpperCase());
        second.remove(Type.ASSIGNMENT, ASSIGNMENT);

        PolicyStore third = open(file);
        assertEquals(List.of(), third.listExemptions());
        assertNull(third.getAssignment(ASSIGNMENT));
    }
}
