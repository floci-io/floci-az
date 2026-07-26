package io.floci.az.services.entra;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Set;

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

    /** A directory user — the subject of ROPC/authorization_code tokens and Graph user lookups. */
    public record User(
        String objectId,
        String upn,
        String displayName,
        String email,
        String tenantId
    ) {}

    /**
     * A directory group. {@code securityEnabled} is kept so Graph's
     * {@code getMemberGroups} can honour {@code securityEnabledOnly}.
     */
    public record Group(
        String objectId,
        String displayName,
        String tenantId,
        boolean securityEnabled
    ) {}

    /** Direct membership of {@code groupId}. No nested-group transitivity yet. */
    public record GroupMembership(String groupId, Set<String> memberObjectIds) {}

    /**
     * A single-use authorization code minted by {@code /authorize} and redeemed by the
     * {@code authorization_code} token grant. {@code codeChallenge}/{@code codeChallengeMethod}
     * carry the PKCE challenge to verify against the token request's {@code code_verifier}.
     */
    public record AuthorizationCode(
        String code,
        String clientId,
        String redirectUri,
        String codeChallenge,
        String codeChallengeMethod,
        String scope,
        String userObjectId,
        String nonce,
        String state,
        Instant expiresAt
    ) {}
}
