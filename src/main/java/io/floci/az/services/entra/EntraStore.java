package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.entra.EntraModels.AppRegistration;
import io.floci.az.services.entra.EntraModels.AuthorizationCode;
import io.floci.az.services.entra.EntraModels.ClientSecret;
import io.floci.az.services.entra.EntraModels.Group;
import io.floci.az.services.entra.EntraModels.GroupMembership;
import io.floci.az.services.entra.EntraModels.ServicePrincipal;
import io.floci.az.services.entra.EntraModels.Tenant;
import io.floci.az.services.entra.EntraModels.User;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link StorageFactory}-backed CRUD for Entra tenants, app registrations and service principals,
 * plus the out-of-the-box dev seed so {@code ClientSecretCredential} works with no setup.
 *
 * <p>PR1 only reads the seed to populate token claims. Full admin CRUD is layered on in PR2.
 */
@ApplicationScoped
public class EntraStore {

    private static final Logger LOG = Logger.getLogger(EntraStore.class);

    private static final String TENANT_PREFIX     = "tenant:";
    private static final String APP_PREFIX        = "app:";
    private static final String SP_PREFIX         = "sp:";
    private static final String USER_PREFIX       = "user:";
    private static final String GROUP_PREFIX      = "group:";
    private static final String MEMBERSHIP_PREFIX = "membership:";
    private static final String AUTHCODE_PREFIX   = "authcode:";

    /** Well-known dev credentials, documented so SDK clients can authenticate with no setup. */
    public static final String DEV_CLIENT_ID     = "11111111-1111-1111-1111-111111111111";
    public static final String DEV_CLIENT_SECRET = "floci-az-dev-secret";
    public static final String DEV_APP_OBJECT_ID = "22222222-2222-2222-2222-222222222222";
    public static final String DEV_SP_OBJECT_ID  = "33333333-3333-3333-3333-333333333333";

    /**
     * Well-known dev user/group, so the interactive (auth-code+PKCE) grant and Graph group-membership
     * lookups also work with zero setup. {@code DEV_USER_OBJECT_ID} is the same deterministic GUID the
     * {@code password} grant already derives from this UPN, so a user minted via ROPC and one resolved
     * through the directory share one {@code oid}.
     */
    public static final String DEV_USER_UPN        = "dev-user@floci-az.local";
    public static final String DEV_USER_OBJECT_ID  = TokenIssuer.deterministicGuid(DEV_USER_UPN);
    public static final String DEV_GROUP_OBJECT_ID = "44444444-4444-4444-4444-444444444444";
    public static final String DEV_GROUP_NAME      = "floci-az dev group";

    private final EmulatorConfig config;
    private final StorageBackend<String, StoredObject> store;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Inject
    public EntraStore(StorageFactory storageFactory, EmulatorConfig config) {
        this.config = config;
        this.store = storageFactory.create("entra");
    }

    @PostConstruct
    void seed() {
        String tenantId = config.services().entra().defaultTenantId();
        if (store.get(TENANT_PREFIX + tenantId).isEmpty()) {
            putTenant(new Tenant(tenantId, "floci-az default tenant"));
        }
        if (findAppByClientId(DEV_CLIENT_ID).isEmpty()) {
            putApp(new AppRegistration(
                DEV_CLIENT_ID, DEV_APP_OBJECT_ID, "floci-az dev app", tenantId,
                List.of(new ClientSecret("dev", DEV_CLIENT_SECRET, "flo"))));
            putServicePrincipal(new ServicePrincipal(
                DEV_SP_OBJECT_ID, DEV_CLIENT_ID, "floci-az dev app", tenantId));
            LOG.infov("Seeded Entra dev app registration (client_id={0})", DEV_CLIENT_ID);
        }
        if (getUser(DEV_USER_OBJECT_ID).isEmpty()) {
            putUser(new User(DEV_USER_OBJECT_ID, DEV_USER_UPN, "floci-az dev user", DEV_USER_UPN, tenantId));
            putGroup(new Group(DEV_GROUP_OBJECT_ID, DEV_GROUP_NAME, tenantId, true));
            addMember(DEV_GROUP_OBJECT_ID, DEV_USER_OBJECT_ID);
            LOG.infov("Seeded Entra dev user/group (upn={0}, group={1})", DEV_USER_UPN, DEV_GROUP_NAME);
        }
    }

    public Optional<Tenant> getTenant(String id) {
        return read(TENANT_PREFIX + id, Tenant.class);
    }

    public void putTenant(Tenant tenant) {
        write(TENANT_PREFIX + tenant.id(), tenant);
    }

    public Optional<AppRegistration> findAppByClientId(String clientId) {
        return read(APP_PREFIX + clientId, AppRegistration.class);
    }

    public void putApp(AppRegistration app) {
        write(APP_PREFIX + app.appId(), app);
    }

    public Optional<ServicePrincipal> findServicePrincipalByAppId(String appId) {
        return read(SP_PREFIX + appId, ServicePrincipal.class);
    }

    public void putServicePrincipal(ServicePrincipal sp) {
        write(SP_PREFIX + sp.appId(), sp);
    }

    public Optional<User> getUser(String objectId) {
        return read(USER_PREFIX + objectId, User.class);
    }

    public Optional<User> findUserByUpn(String upn) {
        return store.scan(key -> key.startsWith(USER_PREFIX)).stream()
                .map(obj -> parse(obj.data(), User.class, obj.key()))
                .filter(user -> user.upn().equalsIgnoreCase(upn))
                .findFirst();
    }

    public void putUser(User user) {
        write(USER_PREFIX + user.objectId(), user);
    }

    public Optional<Group> getGroup(String objectId) {
        return read(GROUP_PREFIX + objectId, Group.class);
    }

    public void putGroup(Group group) {
        write(GROUP_PREFIX + group.objectId(), group);
    }

    public void addMember(String groupId, String memberObjectId) {
        Set<String> members = new LinkedHashSet<>(membership(groupId).memberObjectIds());
        members.add(memberObjectId);
        write(MEMBERSHIP_PREFIX + groupId, new GroupMembership(groupId, members));
    }

    public void removeMember(String groupId, String memberObjectId) {
        Set<String> members = new LinkedHashSet<>(membership(groupId).memberObjectIds());
        members.remove(memberObjectId);
        write(MEMBERSHIP_PREFIX + groupId, new GroupMembership(groupId, members));
    }

    /** Group object ids {@code memberObjectId} directly belongs to. No nested-group transitivity yet. */
    public Set<String> memberGroups(String memberObjectId) {
        Set<String> result = new LinkedHashSet<>();
        for (StoredObject obj : store.scan(key -> key.startsWith(MEMBERSHIP_PREFIX))) {
            GroupMembership membership = parse(obj.data(), GroupMembership.class, obj.key());
            if (membership.memberObjectIds().contains(memberObjectId)) {
                result.add(membership.groupId());
            }
        }
        return result;
    }

    private GroupMembership membership(String groupId) {
        return read(MEMBERSHIP_PREFIX + groupId, GroupMembership.class)
                .orElse(new GroupMembership(groupId, Set.of()));
    }

    /** Stores a single-use authorization code; the token endpoint deletes it on redemption. */
    public void putAuthorizationCode(AuthorizationCode code) {
        write(AUTHCODE_PREFIX + code.code(), code);
    }

    /** Reads and immediately deletes the code, enforcing single use. */
    public Optional<AuthorizationCode> consumeAuthorizationCode(String code) {
        Optional<AuthorizationCode> found = read(AUTHCODE_PREFIX + code, AuthorizationCode.class);
        found.ifPresent(c -> store.delete(AUTHCODE_PREFIX + code));
        return found;
    }

    private <T> Optional<T> read(String key, Class<T> type) {
        return store.get(key).map(obj -> parse(obj.data(), type, key));
    }

    private <T> T parse(byte[] data, Class<T> type, String key) {
        try {
            return mapper.readValue(data, type);
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("Corrupt entra record: " + key, e));
        }
    }

    private void write(String key, Object value) {
        try {
            byte[] data = mapper.writeValueAsBytes(value);
            store.put(key, new StoredObject(key, data, java.util.Map.of(), Instant.now(),
                    Integer.toHexString(new String(data, StandardCharsets.UTF_8).hashCode())));
        } catch (Exception e) {
            throw new UncheckedIOException(new java.io.IOException("Failed to write entra record: " + key, e));
        }
    }
}
