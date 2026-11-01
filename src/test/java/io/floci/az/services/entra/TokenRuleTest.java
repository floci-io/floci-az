package io.floci.az.services.entra;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.SyntheticHttpHeaders;
import io.floci.az.core.arm.ArmCall;
import io.floci.az.core.arm.ArmVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("TokenRule: when the check does not run")
class TokenRuleTest {

    private static ArmCall call(SyntheticHttpHeaders headers) {
        AzureRequest request = new AzureRequest("GET", "arm", "arm", "subscriptions", headers, null, Map.of(), Map.of(),
            null, true, "floci-az", null, "subscriptions", null, "floci-az:4577");
        return new ArmCall(null, null, null, "GET", null, null, request);
    }

    @Test
    void anInternalRequestPassesEvenWithoutAToken() {
        TokenValidator validator = mock(TokenValidator.class);
        TokenRule rule = new TokenRule(validator, mock(EmulatorConfig.class), true);

        assertInstanceOf(ArmVerdict.Proceed.class, rule.enter(call(new SyntheticHttpHeaders(Map.of()))));

        verifyNoInteractions(validator);
    }

    @Test
    void offMeansTheTokenIsNotLookedAt() {
        TokenValidator validator = mock(TokenValidator.class);
        TokenRule rule = new TokenRule(validator, mock(EmulatorConfig.class), false);

        assertInstanceOf(ArmVerdict.Proceed.class, rule.enter(call(null)));

        verifyNoInteractions(validator);
    }
}
