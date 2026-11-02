package io.floci.az.core.arm;

import io.floci.az.services.managedidentity.UserAssignedIdentityRule;
import io.floci.az.services.monitor.activitylog.ActivityLogRule;
import io.floci.az.services.policy.evaluation.PolicyEvaluationRule;
import io.floci.az.services.entra.TokenRule;
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
    void theProductionRulesRunInTheirDeclaredOrder() {
        List<Class<?>> expected = List.of(TokenRule.class, SubscriptionRule.class, ActivityLogRule.class, ProviderRule.class,
            ResourceGroupRule.class, LocationRule.class, ResourceNameRule.class, GlobalNameRule.class,
            PolicyEvaluationRule.class, AppendedLocationRule.class, UserAssignedIdentityRule.class);
        List<Integer> orders = List.of(TokenRule.ORDER, SubscriptionRule.ORDER, ActivityLogRule.ORDER, ProviderRule.ORDER,
            ResourceGroupRule.ORDER, LocationRule.ORDER, ResourceNameRule.ORDER, GlobalNameRule.ORDER,
            PolicyEvaluationRule.ORDER, AppendedLocationRule.ORDER, UserAssignedIdentityRule.ORDER);
        List<ArmRequestRule> rules = gate.rules();
        assertEquals(expected.size(), rules.size(), "rules: " + rules);
        for (int i = 0; i < expected.size(); i++) {
            assertInstanceOf(expected.get(i), rules.get(i));
            assertEquals(orders.get(i), rules.get(i).order());
        }
    }
}
