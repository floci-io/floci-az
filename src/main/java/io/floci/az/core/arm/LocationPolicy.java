package io.floci.az.core.arm;

/** How a resource type treats the {@code location} of its PUT body. */
public enum LocationPolicy {
    /** A location must be sent; Azure rejects a create without one. */
    REQUIRED,
    /** The resource is global; its location is {@code global} whatever the client sends. */
    GLOBAL,
    /** The location may be omitted and then comes from the resource group. */
    INHERITS_RG,
    /** A location may be sent and is kept as is; child and proxy resources, which carry none, also fall here. */
    OPTIONAL,
    /**
     * The type has no {@code location} property of its own (bicep-types-az declares none): a top-level type such as
     * a policy definition, which the Providers API lists with no locations.
     */
    NONE
}
