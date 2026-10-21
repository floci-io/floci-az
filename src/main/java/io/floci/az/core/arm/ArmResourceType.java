package io.floci.az.core.arm;

import java.util.Locale;

/**
 * One ARM resource type floci-az serves, as data: what it is called, where it lives and how it treats its
 * location. Services declare these in an {@link ArmSurface}; the {@link ArmTypeRegistry} matches requests
 * against them, and the platform rules read them instead of each handler re-deriving the same facts.
 *
 * @param namespace the provider namespace, such as {@code Microsoft.Storage}
 * @param typePath  the type below the namespace, slash-separated for children, such as
 *                  {@code storageAccounts/blobServices/containers}; spelled as Azure spells it
 * @param scope     where the type lives in the id hierarchy
 * @param location  how a PUT treats the body's location
 */
public record ArmResourceType(String namespace, String typePath, ArmScopeKind scope, LocationPolicy location) {

    public ArmResourceType {
        if (namespace == null || namespace.isBlank() || typePath == null || typePath.isBlank()) {
            throw new IllegalArgumentException("an ARM resource type needs a namespace and a type path");
        }
        if (typePath.startsWith("/") || typePath.endsWith("/")) {
            throw new IllegalArgumentException("type path must not start or end with a slash: " + typePath);
        }
    }

    /** A resource-group scoped type. */
    public static ArmResourceType resourceGroup(String namespace, String typePath, LocationPolicy location) {
        return new ArmResourceType(namespace, typePath, ArmScopeKind.RESOURCE_GROUP, location);
    }

    /** A child (or proxy) type of a resource-group scoped resource; children carry no location of their own. */
    public static ArmResourceType child(String namespace, String typePath) {
        return new ArmResourceType(namespace, typePath, ArmScopeKind.RESOURCE_GROUP, LocationPolicy.OPTIONAL);
    }

    /** The full type, such as {@code Microsoft.Storage/storageAccounts}, as it appears in a resource body. */
    public String type() {
        return namespace + "/" + typePath;
    }

    /** {@link #type()} lowercased: ARM types compare case-insensitively. */
    public String key() {
        return type().toLowerCase(Locale.ROOT);
    }

    /** True for a nested type ({@code servers/databases}). */
    public boolean isChild() {
        return typePath.indexOf('/') >= 0;
    }

    /** The full type of the parent of a nested type, or null for a top-level type. */
    public String parentType() {
        int slash = typePath.lastIndexOf('/');
        return slash < 0 ? null : namespace + "/" + typePath.substring(0, slash);
    }
}
