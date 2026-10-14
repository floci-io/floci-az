package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;

/**
 * One directory object (a user, group, application or service principal) as
 * {@link GraphDirectoryStore} persists it: the Graph properties as JSON, keyed by tenant, entity
 * set and object id. {@code props} never carries {@code id}; the record component is the one
 * source of it.
 *
 * @param id        the directory object id
 * @param tenantId  the tenant whose directory holds the object
 * @param entitySet the Graph entity set, for example {@code users}
 * @param props     the Graph properties, plus internal ones such as a secret hash
 * @param created   when the object was first written
 */
public record DirectoryObject(String id, String tenantId, String entitySet, ObjectNode props, Instant created) {

    /** A text property, or null when it is absent or JSON null. */
    public String text(String property) {
        return props.hasNonNull(property) ? props.get(property).asText() : null;
    }
}
