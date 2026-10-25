package io.floci.az.services.deployments;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the ARM id of a template resource from its {@code type} and {@code name}, by interleaving the type
 * segments below the namespace with the name segments:
 * {@code Microsoft.Storage/storageAccounts/blobServices/containers} named {@code sa/default/c1} under a resource
 * group is {@code .../providers/Microsoft.Storage/storageAccounts/sa/blobServices/default/containers/c1}.
 *
 * <p>A type has one more segment than its name, the namespace (Azure's "Resolve errors for name and type
 * mismatch" article); any other count is an {@code InvalidTemplate}. Leading and trailing slashes are ignored on
 * both, since templates write types such as {@code 'Microsoft.Network/networkSecurityGroups/'}.
 */
public final class ArmResourcePath {

    private ArmResourcePath() {
    }

    /**
     * The id of a resource of {@code type} named {@code name} under {@code scopeId} (a resource group's or a
     * subscription's id, no trailing slash).
     */
    public static String id(String scopeId, String type, String name) {
        List<String> types = segments(type);
        List<String> names = segments(name);
        if (types.size() < 2 || types.size() != names.size() + 1) {
            throw DeploymentTemplate.invalid("The template resource '" + name + "' for type '" + type
                + "' has incorrect segment lengths: a type has one more segment than its name.");
        }
        StringBuilder id = new StringBuilder(scopeId).append("/providers/").append(types.getFirst());
        for (int i = 0; i < names.size(); i++) {
            id.append('/').append(types.get(i + 1)).append('/').append(names.get(i));
        }
        return id.toString();
    }

    /** {@code value} split on slashes, empty segments dropped. */
    static List<String> segments(String value) {
        List<String> segments = new ArrayList<>();
        for (String segment : value.split("/")) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    /** {@code type} without leading or trailing slashes, for comparing two spellings of one type. */
    static String normalizedType(String type) {
        return String.join("/", segments(type));
    }
}
