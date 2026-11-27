package io.floci.az.services.managedidentity;

import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.cloud.ScopeContext;
import io.floci.az.services.entra.TokenIssuer;
import io.floci.az.services.managedidentity.ManagedIdentityModels.UserAssignedIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The {@code identity} block of an ARM resource, as Azure stores and answers it: the managed service identity shape
 * ({@code type}, {@code principalId}, {@code tenantId}, {@code userAssignedIdentities}) that every resource provider
 * taking a managed identity shares. Every id in it is read-only, so none is taken from the caller.
 *
 * <ul>
 *   <li>{@code None}, or no type at all, is no identity: {@link #resolve} answers {@code null}.</li>
 *   <li>A system-assigned identity gets a principal, kept across every later PUT or PATCH that keeps it, and the
 *       tenant of the request.</li>
 *   <li>Each user-assigned identity gets its principal and client ids from the Managed Identity store. The
 *       {@link UserAssignedIdentityRule} refuses an identity the store does not hold before the request reaches a
 *       handler, so the deterministic ids serve only when the Managed Identity service is disabled.</li>
 * </ul>
 */
@ApplicationScoped
public class ArmIdentities {

    public static final String NONE = "None";
    public static final String SYSTEM_ASSIGNED = "SystemAssigned";
    public static final String USER_ASSIGNED = "UserAssigned";
    public static final String SYSTEM_AND_USER_ASSIGNED = SYSTEM_ASSIGNED + ", " + USER_ASSIGNED;

    /** The {@code ManagedServiceIdentityType} values, in the spec's spelling. */
    public static final List<String> TYPES = List.of(NONE, SYSTEM_ASSIGNED, USER_ASSIGNED, SYSTEM_AND_USER_ASSIGNED);

    private static final String USER_ASSIGNED_IDENTITIES = "userAssignedIdentities";
    private static final String PRINCIPAL_ID = "principalId";

    private final Function<String, Optional<UserAssignedIdentity>> userAssigned;

    @Inject
    public ArmIdentities(ManagedIdentityStore store) {
        this(store::findByResourceId);
    }

    /** For tests: the user-assigned identities are looked up by ARM id through {@code userAssigned}. */
    ArmIdentities(Function<String, Optional<UserAssignedIdentity>> userAssigned) {
        this.userAssigned = userAssigned;
    }

    /**
     * The identity to store for a write that sent {@code sent}, over the resource's {@code stored} identity (null for
     * a new resource or one without an identity).
     *
     * @param sent   the request's {@code identity} value, as parsed from the body; may be null
     * @param stored the identity the resource holds, as this method returned it before; may be null
     * @param scope  the request's resolved scope, for the tenant; may be null
     * @return the identity to store and answer with, or {@code null} for none
     */
    public Map<String, Object> resolve(Object sent, Map<String, Object> stored, ScopeContext scope) {
        Map<String, Object> body = ArmJson.cast(sent);
        String type = type(body.get("type")).orElse(NONE);
        if (NONE.equals(type)) {
            return null;
        }
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("type", type);
        if (type.startsWith(SYSTEM_ASSIGNED)) {
            identity.put(PRINCIPAL_ID, stored != null && stored.get(PRINCIPAL_ID) instanceof String principal
                    ? principal : UUID.randomUUID().toString());
            String tenant = scope != null && scope.tenantId() != null ? scope.tenantId()
                    : stored != null && stored.get("tenantId") instanceof String kept ? kept : null;
            if (tenant != null) {
                identity.put("tenantId", tenant);
            }
        }
        if (type.endsWith(USER_ASSIGNED)) {
            identity.put(USER_ASSIGNED_IDENTITIES, userAssignedIdentities(body, stored));
        }
        return identity;
    }

    /**
     * Why {@code sent} is not an identity block: it is not an object, or its {@code type} is not a
     * {@code ManagedServiceIdentityType}. Only a missing identity or type means {@code None}: a misspelt type would
     * otherwise remove the resource's identity and its principal with it.
     *
     * @param resourceKind how the message names the resource ({@code "an API Management service"})
     */
    public static Optional<String> malformed(Object sent, String resourceKind) {
        if (sent == null) {
            return Optional.empty();
        }
        if (!(sent instanceof Map<?, ?>)) {
            return Optional.of("The 'identity' of " + resourceKind + " must be an object.");
        }
        Object type = ArmJson.cast(sent).get("type");
        if (type != null && type(type).isEmpty()) {
            return Optional.of("The identity type '" + type + "' is invalid: it must be one of "
                    + String.join(", ", TYPES) + ".");
        }
        return Optional.empty();
    }

    /** The spec's spelling of an identity type, matched ignoring case and the space after the comma. */
    public static Optional<String> type(Object sent) {
        if (!(sent instanceof String text)) {
            return Optional.empty();
        }
        String compact = text.replace(" ", "");
        return TYPES.stream().filter(known -> known.replace(" ", "").equalsIgnoreCase(compact)).findFirst();
    }

    /**
     * The user-assigned identities the write names. A null entry removes that identity, as
     * {@link UserAssignedIdentityRule} reads it. A write that names none keeps the stored ones, so a PATCH that only
     * changes the type to add a system-assigned identity does not drop them.
     */
    private Map<String, Object> userAssignedIdentities(Map<String, Object> body, Map<String, Object> stored) {
        Map<String, Object> identities = new LinkedHashMap<>();
        if (!body.containsKey(USER_ASSIGNED_IDENTITIES)) {
            if (stored != null) {
                identities.putAll(ArmJson.cast(stored.get(USER_ASSIGNED_IDENTITIES)));
            }
            return identities;
        }
        ArmJson.cast(body.get(USER_ASSIGNED_IDENTITIES)).forEach((resourceId, value) -> {
            if (value != null) {
                identities.put(resourceId, userAssignedIds(resourceId));
            }
        });
        return identities;
    }

    private Map<String, Object> userAssignedIds(String resourceId) {
        Optional<UserAssignedIdentity> held = userAssigned.apply(resourceId);
        String seed = resourceId.toLowerCase(Locale.ROOT);
        Map<String, Object> ids = new LinkedHashMap<>();
        ids.put(PRINCIPAL_ID, held.map(UserAssignedIdentity::principalId)
                .orElseGet(() -> TokenIssuer.deterministicGuid("arm-uai-principal:" + seed)));
        ids.put("clientId", held.map(UserAssignedIdentity::clientId)
                .orElseGet(() -> TokenIssuer.deterministicGuid("arm-uai-client:" + seed)));
        return ids;
    }
}
