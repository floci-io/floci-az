package io.floci.az.services.entra;

import java.util.List;

/**
 * The targets of one navigation from one directory object, for example a group's
 * {@code members}, persisted by {@link GraphDirectoryStore} at {@code link:{tenant}:{source}:{nav}}.
 *
 * @param tenantId   the tenant whose directory holds the source object
 * @param sourceId   the source object id
 * @param navigation the navigation property name
 * @param targetIds  the linked object ids, in insertion order
 */
public record DirectoryLinks(String tenantId, String sourceId, String navigation, List<String> targetIds) {
}
