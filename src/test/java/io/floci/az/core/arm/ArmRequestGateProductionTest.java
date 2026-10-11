package io.floci.az.core.arm;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("ArmRequestGate: the production gate has no rules yet")
class ArmRequestGateProductionTest {

    @Inject
    ArmRequestGate gate;

    @Test
    void theProductionGateIsAPurePassThrough() {
        assertTrue(gate.rules().isEmpty(), "rules: " + gate.rules());
    }
}
