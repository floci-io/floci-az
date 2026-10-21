package io.floci.az.core.arm;

import io.floci.az.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The gate rule for resource name syntax ({@link NameRule}): under {@link EmulatorConfig#strictNames()} a PUT of a
 * resource whose type declares a rule, with a name outside it, is refused with the rule's {@code 400} before its
 * handler runs. Without the switch every name passes, as floci-az has always accepted them.
 */
@ApplicationScoped
public class ResourceNameRule implements ArmRequestRule {

    /** Position in the gate: after the location rule, before the global name is reserved. */
    public static final int ORDER = 6;

    private final ArmTypeRegistry types;
    private final boolean strict;

    @Inject
    public ResourceNameRule(ArmTypeRegistry types, EmulatorConfig config) {
        this(types, config.strictNames());
    }

    ResourceNameRule(ArmTypeRegistry types, boolean strict) {
        this.types = types;
        this.strict = strict;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public ArmVerdict enter(ArmCall call) {
        if (!strict || !"PUT".equals(call.method()) || call.type() == null || call.id() == null
                || call.id().name() == null || call.id().isCollection()) {
            return ArmVerdict.proceed();
        }
        return types.type(call.type())
            .filter(type -> type.typePath().equalsIgnoreCase(String.join("/", call.id().types())))
            .map(ArmResourceType::name)
            .filter(rule -> rule != NameRule.NONE && !rule.accepts(call.id().name()))
            .map(rule -> ArmVerdict.respond(ArmErrors.error(400, rule.code(), rule.message(call.id().name()))))
            .orElse(ArmVerdict.proceed());
    }
}
