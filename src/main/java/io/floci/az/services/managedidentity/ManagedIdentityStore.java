package io.floci.az.services.managedidentity;

import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.managedidentity.ManagedIdentityModels.FederatedIdentityCredential;
import io.floci.az.services.managedidentity.ManagedIdentityModels.UserAssignedIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Microsoft.ManagedIdentity resources: user-assigned identities and their federated identity credentials, two
 * {@link ArmResourceStore}s over the {@code managedidentity} backend, keyed by canonical id. Before the stores the
 * identities lived in an in-memory map only, so no older key shape was ever persisted and neither store has legacy
 * entries.
 *
 * <p>Token requests select an identity by client id or principal id. Those ids never change once an identity is
 * created, so this class keeps them in an in-memory index to the identity's id, built from the store on first use
 * and kept current by every write here; a token request then reads one entry instead of decoding every identity.
 *
 * <p>All access goes through methods: callers hold a normal-scoped client proxy, and direct field reads on a proxy
 * would hit the proxy's own fields instead of this contextual instance.
 */
@ApplicationScoped
public class ManagedIdentityStore {

    static final String STORE_NAME = "managedidentity";
    static final String IDENTITY_TYPE = "Microsoft.ManagedIdentity/userAssignedIdentities";
    static final String CREDENTIAL_TYPE = IDENTITY_TYPE + "/federatedIdentityCredentials";

    private final ArmResourceStore<UserAssignedIdentity> identities;
    private final ArmResourceStore<FederatedIdentityCredential> credentials;
    /** Identity ids by lowercased client id, and by lowercased principal id; null until first needed. */
    private Map<String, ArmResourceId> byClientId;
    private Map<String, ArmResourceId> byPrincipalId;

    @Inject
    public ManagedIdentityStore(StorageFactory storageFactory) {
        this.identities = ArmResourceStore.create(storageFactory, STORE_NAME, IDENTITY_TYPE,
                UserAssignedIdentity.class, UserAssignedIdentity::id, key -> false);
        this.credentials = ArmResourceStore.create(storageFactory, STORE_NAME, CREDENTIAL_TYPE,
                FederatedIdentityCredential.class, FederatedIdentityCredential::id, key -> false);
    }

    // ── Identities ──────────────────────────────────────────────────────────────

    public Optional<UserAssignedIdentity> identity(ArmResourceId id) {
        return identities.get(id).map(ArmResourceStore.Stored::value);
    }

    /** Creates or replaces the identity atomically, so concurrent creates of one identity keep one client id. */
    public synchronized ArmResourceStore.Written<UserAssignedIdentity> createOrUpdateIdentity(
            ArmResourceId id, Function<Optional<UserAssignedIdentity>, UserAssignedIdentity> change) {
        ArmResourceStore.Written<UserAssignedIdentity> written = identities.createOrUpdate(id, change);
        index(written.stored().value(), written.stored().id());
        return written;
    }

    public Optional<UserAssignedIdentity> updateIdentity(ArmResourceId id, UnaryOperator<UserAssignedIdentity> change) {
        return identities.update(id, change).map(ArmResourceStore.Stored::value);
    }

    /** Removes the identity and its federated credentials; empty when there was no such identity. */
    public synchronized Optional<UserAssignedIdentity> deleteIdentity(ArmResourceId id) {
        for (ArmResourceStore.Stored<FederatedIdentityCredential> credential : credentialsOf(id)) {
            credentials.delete(credential.id());
        }
        Optional<UserAssignedIdentity> removed = identities.delete(id).map(ArmResourceStore.Stored::value);
        removed.ifPresent(this::unindex);
        return removed;
    }

    /** The identities in {@code scope}: a subscription, or a subscription and resource group. */
    public List<UserAssignedIdentity> identities(ArmScope scope) {
        return identities.list(scope).stream().map(ArmResourceStore.Stored::value).toList();
    }

    public synchronized Optional<UserAssignedIdentity> findByClientId(String clientId) {
        return findIndexed(clientIndex(), clientId, UserAssignedIdentity::clientId);
    }

    public synchronized Optional<UserAssignedIdentity> findByPrincipalId(String principalId) {
        return findIndexed(principalIndex(), principalId, UserAssignedIdentity::principalId);
    }

    /** The identity an ARM id names, compared case-insensitively; empty for anything that is not an ARM id. */
    public Optional<UserAssignedIdentity> findByResourceId(String armId) {
        return ArmResourceId.parse(armId).flatMap(this::identity);
    }

    // ── Federated identity credentials ──────────────────────────────────────────

    public Optional<FederatedIdentityCredential> credential(ArmResourceId id) {
        return credentials.get(id).map(ArmResourceStore.Stored::value);
    }

    /**
     * Creates or replaces a credential of the identity {@code identityId}, in one step with checking that the
     * identity exists, so a credential is never written under an identity a concurrent delete removed; empty when
     * there is no such identity.
     */
    public synchronized Optional<ArmResourceStore.Written<FederatedIdentityCredential>> createOrUpdateCredential(
            ArmResourceId identityId, ArmResourceId id,
            Function<Optional<FederatedIdentityCredential>, FederatedIdentityCredential> change) {
        if (identities.get(identityId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(credentials.createOrUpdate(id, change));
    }

    public Optional<FederatedIdentityCredential> deleteCredential(ArmResourceId id) {
        return credentials.delete(id).map(ArmResourceStore.Stored::value);
    }

    /** The credentials of the identity {@code identityId}. */
    public List<FederatedIdentityCredential> credentials(ArmResourceId identityId) {
        return credentialsOf(identityId).stream().map(ArmResourceStore.Stored::value).toList();
    }

    private List<ArmResourceStore.Stored<FederatedIdentityCredential>> credentialsOf(ArmResourceId identityId) {
        String parent = identityId.canonicalKey();
        return credentials.list(new ArmScope(identityId.subscriptionId(), identityId.resourceGroup())).stream()
                .filter(stored -> {
                    ArmResourceId owner = stored.id().parent();
                    return owner != null && parent.equals(owner.canonicalKey());
                })
                .toList();
    }

    public synchronized void clear() {
        credentials.clear();
        identities.clear();
        byClientId = null;
        byPrincipalId = null;
    }

    // ── Client and principal id index ───────────────────────────────────────────

    private Optional<UserAssignedIdentity> findIndexed(Map<String, ArmResourceId> index, String selector,
                                                       Function<UserAssignedIdentity, String> property) {
        ArmResourceId id = index.get(selector.toLowerCase(Locale.ROOT));
        if (id == null) {
            return Optional.empty();
        }
        return identity(id).filter(found -> selector.equalsIgnoreCase(property.apply(found)));
    }

    private Map<String, ArmResourceId> clientIndex() {
        buildIndex();
        return byClientId;
    }

    private Map<String, ArmResourceId> principalIndex() {
        buildIndex();
        return byPrincipalId;
    }

    private void buildIndex() {
        if (byClientId != null) {
            return;
        }
        byClientId = new HashMap<>();
        byPrincipalId = new HashMap<>();
        for (ArmResourceStore.Stored<UserAssignedIdentity> stored : identities.all()) {
            put(stored.value(), stored.id());
        }
    }

    /** Records a written identity, once the index exists; until then the first lookup reads it from the store. */
    private void index(UserAssignedIdentity identity, ArmResourceId id) {
        if (byClientId != null) {
            put(identity, id);
        }
    }

    private void put(UserAssignedIdentity identity, ArmResourceId id) {
        if (identity.clientId() != null) {
            byClientId.put(identity.clientId().toLowerCase(Locale.ROOT), id);
        }
        if (identity.principalId() != null) {
            byPrincipalId.put(identity.principalId().toLowerCase(Locale.ROOT), id);
        }
    }

    private void unindex(UserAssignedIdentity identity) {
        if (byClientId == null) {
            return;
        }
        if (identity.clientId() != null) {
            byClientId.remove(identity.clientId().toLowerCase(Locale.ROOT));
        }
        if (identity.principalId() != null) {
            byPrincipalId.remove(identity.principalId().toLowerCase(Locale.ROOT));
        }
    }
}
