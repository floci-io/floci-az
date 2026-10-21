package io.floci.az.core.arm;

import io.floci.az.services.policy.evaluation.PolicyEvaluationRule;

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
    void theSubscriptionProviderResourceGroupAndPolicyRulesRunInThatOrder() {
        List<ArmRequestRule> rules = gate.rules();
        assertEquals(4, rules.size(), "rules: " + rules);
        assertInstanceOf(SubscriptionRule.class, rules.getFirst());
        assertEquals(SubscriptionRule.ORDER, rules.getFirst().order());
        assertInstanceOf(ProviderRule.class, rules.get(1));
        assertEquals(ProviderRule.ORDER, rules.get(1).order());
        assertInstanceOf(ResourceGroupRule.class, rules.get(2));
        assertEquals(ResourceGroupRule.ORDER, rules.get(2).order());
        assertInstanceOf(PolicyEvaluationRule.class, rules.get(3));
        assertEquals(PolicyEvaluationRule.ORDER, rules.get(3).order());
    }
}
