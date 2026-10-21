package io.floci.az.core.arm;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.directory.ProviderRegistrations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The gate rule for a resource provider namespace: under {@link EmulatorConfig#strictProviders()} the
 * subscription must be registered with the namespace of the resource type a request addresses, or the request
 * is refused before its handler runs with {@code 409 MissingSubscriptionRegistration}, ARM's answer. The Go,
 * Java and Python SDKs then register the namespace and retry on their own.
 *
 * <p>Only a request for a declared type counts ({@link ArmCall#type()}). The Providers API itself addresses no
 * type, so a client can always read and register a namespace. A {@code DELETE} always passes: the refusals Azure
 * records are writes, and a namespace unregistered after its resources were created must not strand them, least of
 * all when a resource group delete removes the group's resources one by one. Without the switch every request
 * passes, since every namespace starts registered.
 */
@ApplicationScoped
public class ProviderRule implements ArmRequestRule {

    private static final Logger LOG = Logger.getLogger(ProviderRule.class);

    /** Position in the gate: after the subscription rule and the tenant rule's slot, before resource groups. */
    public static final int ORDER = 3;

    private final ProviderRegistrations registrations;
    private final boolean strict;

    @Inject
    public ProviderRule(ProviderRegistrations registrations, EmulatorConfig config) {
        this(registrations, config.strictProviders());
    }

    ProviderRule(ProviderRegistrations registrations, boolean strict) {
        this.registrations = registrations;
        this.strict = strict;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public ArmVerdict enter(ArmCall call) {
        if (!strict || call.type() == null || call.id().subscriptionId() == null || "DELETE".equals(call.method())) {
            return ArmVerdict.proceed();
        }
        // The namespace as the request spells it: the Go policy reads it from the URL, the others from the body.
        String namespace = call.id().providerNamespace();
        if (namespace == null || registrations.isRegistered(call.id().subscriptionId(), namespace)) {
            return ArmVerdict.proceed();
        }
        LOG.infov("Refused {0}: subscription {1} is not registered with {2} and "
                + "floci-az.parity-overrides.strict-providers is on (register the namespace, or turn the override "
                + "off)", call.method(), call.id().subscriptionId(), namespace);
        return ArmVerdict.respond(ArmErrors.missingSubscriptionRegistration(namespace));
    }
}
