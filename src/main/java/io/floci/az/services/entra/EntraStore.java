package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.Resettable;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.entra.EntraModels.AuthorizationCode;
import io.floci.az.services.entra.EntraModels.Tenant;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link StorageFactory}-backed state of the token service itself: tenants and single-use
 * authorization codes. Directory objects (users, groups, applications, service principals) live in
 * {@link GraphDirectoryStore}, which shares this store and seeds the dev fixtures named here.
 */
@ApplicationScoped
public class EntraStore implements Resettable {

    private static final String TENANT_PREFIX     = "tenant:";
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
    }

    /**
     * Wipes tenant and authorization-code state, then reseeds the default tenant. The directory
     * entries sharing this store are {@link GraphDirectoryStore}'s to clear: resets run in no
     * particular order, so each store removes only its own keys.
     */
    @Override
    public synchronized void clear() {
        Set<String> doomed = new HashSet<>();
        for (String key : Set.copyOf(store.keys())) {
            if (key.startsWith(TENANT_PREFIX) || key.startsWith(AUTHCODE_PREFIX)) {
                doomed.add(key);
            }
        }
        store.applyBatch(Map.of(), doomed);
        seed();
    }

    public Optional<Tenant> getTenant(String id) {
        return read(TENANT_PREFIX + id, Tenant.class);
    }

    public void putTenant(Tenant tenant) {
        write(TENANT_PREFIX + tenant.id(), tenant);
    }

    /** Stores a single-use authorization code; the token endpoint deletes it on redemption. */
    public void putAuthorizationCode(AuthorizationCode code) {
        write(AUTHCODE_PREFIX + code.code(), code);
    }

    /** Atomically removes the code, enforcing single use even under concurrent redemption attempts. */
    public Optional<AuthorizationCode> consumeAuthorizationCode(String code) {
        return store.remove(AUTHCODE_PREFIX + code).map(obj -> parse(obj.data(), AuthorizationCode.class, obj.key()));
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
            store.put(key, new StoredObject(key, data, Map.of(), Instant.now(),
                    Integer.toHexString(new String(data, StandardCharsets.UTF_8).hashCode())));
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("Failed to write entra record: " + key, e));
        }
    }
}
