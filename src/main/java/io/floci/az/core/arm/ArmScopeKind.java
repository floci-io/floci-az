package io.floci.az.core.arm;

/** Where an ARM resource type lives in the id hierarchy. */
public enum ArmScopeKind {
    /** {@code /subscriptions/{sub}/resourceGroups/{rg}/providers/...}: most resources. */
    RESOURCE_GROUP,
    /** {@code /subscriptions/{sub}/providers/...}, and resource groups themselves. */
    SUBSCRIPTION,
    /** {@code /providers/...} with no subscription. */
    TENANT,
    /** Under another resource or at any scope ({@code .../providers/Microsoft.Insights/diagnosticSettings/x}). */
    EXTENSION
}
