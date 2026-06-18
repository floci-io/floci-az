package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.entra.EntraModels.AppRegistration;
import io.floci.az.services.entra.EntraModels.ClientSecret;
import io.floci.az.services.entra.EntraModels.ServicePrincipal;
import io.floci.az.services.entra.EntraModels.Tenant;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link StorageFactory}-backed CRUD for Entra tenants, app registrations and service principals,
 * plus the out-of-the-box dev seed so {@code ClientSecretCredential} works with no setup.
 *
 * <p>PR1 only reads the seed to populate token claims. Full admin CRUD is layered on in PR2.
 */
@ApplicationScoped
public class EntraStore {

    private static final Logger LOG = Logger.getLogger(EntraStore.class);

    private static final String TENANT_PREFIX = "tenant:";
    private static final String APP_PREFIX     = "app:";
    private static final String SP_PREFIX      = "sp:";

    /** Well-known dev credentials, documented so SDK clients can authenticate with no setup. */
    public static final String DEV_CLIENT_ID     = "11111111-1111-1111-1111-111111111111";
    public static final String DEV_CLIENT_SECRET = "floci-az-dev-secret";
    public static final String DEV_APP_OBJECT_ID = "22222222-2222-2222-2222-222222222222";
    public static final String DEV_SP_OBJECT_ID  = "33333333-3333-3333-3333-333333333333";

    private final EmulatorConfig config;
    private final StorageBackend<String, StoredObject> store;
    private final ObjectMapper mapper = new ObjectMapper();

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

    private <T> Optional<T> read(String key, Class<T> type) {
        return store.get(key).map(obj -> {
            try {
                return mapper.readValue(obj.data(), type);
            } catch (Exception e) {
                throw new UncheckedIOException(new java.io.IOException("Corrupt entra record: " + key, e));
            }
        });
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
