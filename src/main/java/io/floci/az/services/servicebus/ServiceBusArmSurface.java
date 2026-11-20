package io.floci.az.services.servicebus;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.GlobalName;
import io.floci.az.core.arm.LocationPolicy;
import io.floci.az.core.arm.NameRule;
import io.floci.az.core.cloud.CloudSuffix;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

/** The ARM resource types {@link ServiceBusHandler} serves through {@link ServiceBusArmService}. */
@ApplicationScoped
public class ServiceBusArmSurface implements ArmSurfaceProvider {

    static final String NAMESPACE = "Microsoft.ServiceBus";

    static final String NAMESPACES = "namespaces";
    static final String QUEUES = "namespaces/queues";
    static final String TOPICS = "namespaces/topics";
    static final String SUBSCRIPTIONS = "namespaces/topics/subscriptions";
    static final String RULES = "namespaces/topics/subscriptions/rules";
    static final String NAMESPACE_RULES = "namespaces/AuthorizationRules";
    static final String QUEUE_RULES = "namespaces/queues/authorizationRules";
    static final String TOPIC_RULES = "namespaces/topics/authorizationRules";
    static final String NETWORK_RULE_SETS = "namespaces/networkRuleSets";
    static final String DISASTER_RECOVERY_CONFIGS = "namespaces/disasterRecoveryConfigs";

    /**
     * 6 to 50 letters, digits and hyphens, starting with a letter, ending with a letter or digit, and not ending in
     * {@code -sb} or {@code -mgmt}: the rule azurerm validates {@code azurerm_servicebus_namespace.name} with
     * ({@code validate/namespace_name.go}). The 2024-01-01 spec declares no {@code name} pattern.
     */
    static final NameRule NAMESPACE_NAME = new NameRule(6, 50,
        Pattern.compile("(?!.*-(?i:sb|mgmt)$)[a-zA-Z][-a-zA-Z0-9]{4,48}[a-zA-Z0-9]"),
        "InvalidResourceName",
        "The resource name '%s' is invalid: a Service Bus namespace name is 6 to 50 letters, digits and hyphens, "
            + "starts with a letter, ends with a letter or digit, and does not end in '-sb' or '-mgmt'.");

    /**
     * A namespace name is a global DNS label under {@code servicebus.windows.net}. No public source gives Azure's
     * create-conflict answer, so ARM's generic {@code Conflict} stands in.
     */
    static final GlobalName NAMESPACE_DNS_NAME = new GlobalName(CloudSuffix.SERVICEBUS, 409, "Conflict",
        "The namespace name '%s' is already in use.");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, NAMESPACES, LocationPolicy.REQUIRED)
                .withName(NAMESPACE_NAME)
                .withGlobalName(NAMESPACE_DNS_NAME),
            ArmResourceType.child(NAMESPACE, QUEUES),
            ArmResourceType.child(NAMESPACE, TOPICS),
            ArmResourceType.child(NAMESPACE, SUBSCRIPTIONS),
            ArmResourceType.child(NAMESPACE, RULES),
            ArmResourceType.child(NAMESPACE, NAMESPACE_RULES),
            ArmResourceType.child(NAMESPACE, QUEUE_RULES),
            ArmResourceType.child(NAMESPACE, TOPIC_RULES),
            ArmResourceType.child(NAMESPACE, NETWORK_RULE_SETS),
            ArmResourceType.child(NAMESPACE, DISASTER_RECOVERY_CONFIGS))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
