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
    OPTIONAL
}
