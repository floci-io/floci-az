package io.floci.az.services.entra;

import java.util.List;

/**
 * The Graph entity sets {@link GraphDirectoryStore} holds, with each one's alternate keys and the
 * flat key prefix the pre-tenant layout stored it under. Legacy entries are keyed by object id
 * for users and groups, and by {@code appId} for applications and service principals.
 */
public enum DirectoryEntitySet {

    USERS("users", "user:", false, List.of("userPrincipalName")),
    GROUPS("groups", "group:", false, List.of()),
    APPLICATIONS("applications", "app:", true, List.of("appId")),
    SERVICE_PRINCIPALS("servicePrincipals", "sp:", true, List.of("appId"));

    private final String setName;
    private final String legacyPrefix;
    private final boolean legacyKeyedByAppId;
    private final List<String> alternateKeys;

    DirectoryEntitySet(String setName, String legacyPrefix, boolean legacyKeyedByAppId, List<String> alternateKeys) {
        this.setName = setName;
        this.legacyPrefix = legacyPrefix;
        this.legacyKeyedByAppId = legacyKeyedByAppId;
        this.alternateKeys = alternateKeys;
    }

    /** The Graph entity set name, as it appears in {@code /v1.0/{set}}. */
    public String setName() {
        return setName;
    }

    /** The key prefix of this set's entries in the pre-tenant layout. */
    public String legacyPrefix() {
        return legacyPrefix;
    }

    /** Whether the pre-tenant layout keyed this set by {@code appId} rather than by object id. */
    public boolean legacyKeyedByAppId() {
        return legacyKeyedByAppId;
    }

    /** Properties that identify an object as uniquely as its id, matched case-insensitively. */
    public List<String> alternateKeys() {
        return alternateKeys;
    }
}
