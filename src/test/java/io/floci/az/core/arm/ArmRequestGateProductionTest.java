package io.floci.az.core.arm;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@QuarkusTest
@DisplayName("ArmRequestGate: the production rules")
class ArmRequestGateProductionTest {

    @Inject
    ArmRequestGate gate;

    @Test
    void theSubscriptionRuleIsTheOnlyProductionRuleAndRunsFirst() {
        List<ArmRequestRule> rules = gate.rules();
        assertEquals(1, rules.size(), "rules: " + rules);
        assertInstanceOf(SubscriptionRule.class, rules.getFirst());
        assertEquals(SubscriptionRule.ORDER, rules.getFirst().order());
    }
}
