package io.floci.az.core.arm;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableBean;
import io.quarkus.arc.InjectableContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("ArmTypeRegistry: built at startup")
class ArmTypeRegistryStartupTest {

    @Test
    void theRegistryExistsBeforeAnyRequestOrInjectionAsksForIt() {
        InjectableBean<ArmTypeRegistry> bean = Arc.container().instance(ArmTypeRegistry.class).getBean();
        InjectableContext context = Arc.container().getActiveContext(ApplicationScoped.class);

        assertTrue(context.getState().getContextualInstances().containsKey(bean),
            "the registry should be created at startup, so a declaration defect fails startup");
    }
}
