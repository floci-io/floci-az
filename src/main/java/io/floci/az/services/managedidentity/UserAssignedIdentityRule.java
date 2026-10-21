package io.floci.az.services.managedidentity;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.arm.ArmCall;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmRequestRule;
import io.floci.az.core.arm.ArmVerdict;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * The gate rule for the user-assigned identities a write names in {@code identity.userAssignedIdentities}: each
 * must exist, or the {@code PUT} or {@code PATCH} is refused with ARM's {@code 400 FailedIdentityOperation} before
 * its handler stores the resource. It covers every resource kind that takes an {@code identity}.
 */
@ApplicationScoped
public class UserAssignedIdentityRule implements ArmRequestRule {

    /** Position in the gate: after policy evaluation (order 40) and its appended-location pass (order 45). */
    public static final int ORDER = 50;

    private final ManagedIdentityStore store;
    private final EmulatorConfig config;

    @Inject
    public UserAssignedIdentityRule(ManagedIdentityStore store, EmulatorConfig config) {
        this.store = store;
        this.config = config;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public boolean readsBody() {
        return config.services().managedIdentity().enabled();
    }

    @Override
    public ArmVerdict enter(ArmCall call) {
        if (call.body() == null || !config.services().managedIdentity().enabled()) {
            return ArmVerdict.proceed();
        }
        Map<String, Object> identity = ArmJson.cast(ArmJson.cast(call.body().json()).get("identity"));
        Object identities = identity.get("userAssignedIdentities");
        if (!(identities instanceof Map<?, ?> named)) {
            return ArmVerdict.proceed();
        }
        for (Object id : named.keySet()) {
            String identityId = String.valueOf(id);
            if (store.findByResourceId(identityId).isEmpty()) {
                return ArmVerdict.respond(ArmErrors.error(400, "FailedIdentityOperation",
                    "The user assigned identity '" + identityId + "' could not be found."));
            }
        }
        return ArmVerdict.proceed();
    }
}
