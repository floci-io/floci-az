package io.floci.az.services.managedidentity;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/** The persisted Microsoft.ManagedIdentity resources. */
public final class ManagedIdentityModels {

    private ManagedIdentityModels() {
    }

    /**
     * A user-assigned identity. {@code tags} is whatever the last PUT or PATCH sent, echoed as it came.
     *
     * @param id the ARM id, in the casing of the request that created it
     */
    @RegisterForReflection
    public record UserAssignedIdentity(String id, String name, String location, Object tags, String tenantId,
                                       String principalId, String clientId) {

        public UserAssignedIdentity withTags(Object newTags) {
            return new UserAssignedIdentity(id, name, location, newTags, tenantId, principalId, clientId);
        }
    }

    /**
     * A federated identity credential of a user-assigned identity.
     *
     * @param id the ARM id, in the casing of the request that created it
     */
    @RegisterForReflection
    public record FederatedIdentityCredential(String id, String name, String issuer, String subject,
                                              List<Object> audiences) {
    }
}
