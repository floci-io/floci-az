package io.floci.az.services.entra;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Domain objects for Microsoft Entra ID emulation. Kept as immutable records and serialised
 * to the {@code entra} storage backend as JSON.
 */
@RegisterForReflection
public final class EntraModels {

    private EntraModels() {}

    public record Tenant(String id, String displayName) {}

    /** A client secret. For dev seeding the plaintext value is kept; PR2 will hash on registration. */
    public record ClientSecret(String keyId, String value, String hint) {}

    public record AppRegistration(
        String appId,            // client_id
        String objectId,         // directory object id
        String displayName,
        String tenantId,
        List<ClientSecret> secrets
    ) {}

    public record ServicePrincipal(
        String objectId,
        String appId,
        String displayName,
        String tenantId
    ) {}
}
