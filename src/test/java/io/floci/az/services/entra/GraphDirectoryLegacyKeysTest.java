package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.services.entra.DirectoryReader.DirectoryApplication;
import io.floci.az.services.entra.DirectoryReader.DirectoryUser;
import io.floci.az.services.entra.EntraModels.AppRegistration;
import io.floci.az.services.entra.EntraModels.AuthorizationCode;
import io.floci.az.services.entra.EntraModels.ClientSecret;
import io.floci.az.services.entra.EntraModels.Group;
import io.floci.az.services.entra.EntraModels.GroupMembership;
import io.floci.az.services.entra.EntraModels.ServicePrincipal;
import io.floci.az.services.entra.EntraModels.Tenant;
import io.floci.az.services.entra.EntraModels.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A store written before the tenant-keyed layout holds flat {@code user:}, {@code group:},
 * {@code app:}, {@code sp:} and {@code membership:} entries. They must be read in place as the
 * default tenant's objects, and must move (one at a time) only when that object is written.
 */
class GraphDirectoryLegacyKeysTest {

    private static final String DEFAULT_TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String OTHER_TENANT = "9f1c2a2e-1111-4c1e-9a55-0123456789ab";
    private static final String ALICE_ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String ALICE_UPN = "alice@floci-az.local";
    private static final String TEAM_ID = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String APP_ID = "cccccccc-0000-0000-0000-000000000003";
    private static final String APP_OBJECT_ID = "dddddddd-0000-0000-0000-000000000004";
    private static final String APP_SECRET = "legacy-app-secret";

    private final ObjectMapper legacyMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private StorageBackend<String, StoredObject> backend;
    private GraphDirectoryStore store;
    private DirectoryReader reader;

    @BeforeEach
    void seedLegacyStore() throws Exception {
        backend = new InMemoryStorage<>();
        // Exactly what the pre-tenant EntraStore wrote for its dev seed...
        legacyWrite("tenant:" + DEFAULT_TENANT, new Tenant(DEFAULT_TENANT, "floci-az default tenant"));
        legacyWrite("app:" + EntraStore.DEV_CLIENT_ID, new AppRegistration(EntraStore.DEV_CLIENT_ID,
                EntraStore.DEV_APP_OBJECT_ID, "floci-az dev app", DEFAULT_TENANT,
                List.of(new ClientSecret("dev", EntraStore.DEV_CLIENT_SECRET, "flo"))));
        legacyWrite("sp:" + EntraStore.DEV_CLIENT_ID, new ServicePrincipal(EntraStore.DEV_SP_OBJECT_ID,
                EntraStore.DEV_CLIENT_ID, "floci-az dev app", DEFAULT_TENANT));
        legacyWrite("user:" + EntraStore.DEV_USER_OBJECT_ID, new User(EntraStore.DEV_USER_OBJECT_ID,
                EntraStore.DEV_USER_UPN, "floci-az dev user", EntraStore.DEV_USER_UPN, DEFAULT_TENANT));
        legacyWrite("group:" + EntraStore.DEV_GROUP_OBJECT_ID, new Group(EntraStore.DEV_GROUP_OBJECT_ID,
                EntraStore.DEV_GROUP_NAME, DEFAULT_TENANT, true));
        legacyWrite("membership:" + EntraStore.DEV_GROUP_OBJECT_ID,
                new GroupMembership(EntraStore.DEV_GROUP_OBJECT_ID, Set.of(EntraStore.DEV_USER_OBJECT_ID)));
        // ...plus data a client added through it, and an authorization code in flight.
        legacyWrite("user:" + ALICE_ID, new User(ALICE_ID, ALICE_UPN, "Alice", null, DEFAULT_TENANT));
        legacyWrite("group:" + TEAM_ID, new Group(TEAM_ID, "team", DEFAULT_TENANT, false));
        legacyWrite("membership:" + TEAM_ID, new GroupMembership(TEAM_ID, new LinkedHashSet<>(List.of(ALICE_ID))));
        legacyWrite("app:" + APP_ID, new AppRegistration(APP_ID, APP_OBJECT_ID, "legacy app", DEFAULT_TENANT,
                List.of(new ClientSecret("k1", APP_SECRET, "leg"))));
        legacyWrite("authcode:abc", new AuthorizationCode("abc", DEFAULT_TENANT, APP_ID, "https://app/cb",
                null, "plain", "openid", ALICE_ID, null, null, Instant.parse("2030-01-01T00:00:00Z")));

        store = new GraphDirectoryStore(backend, DEFAULT_TENANT);
        reader = new DirectoryReader(store);
    }

    @Test
    void seedingOverALegacyStoreWritesNothing() {
        Map<String, String> before = snapshot();

        store.seed();

        assertEquals(before, snapshot(), "no startup migration: a seeded legacy store must stay byte-identical");
    }

    @Test
    void legacyEntriesReadInPlaceAsTheDefaultTenantsObjects() {
        Map<String, String> before = snapshot();

        DirectoryUser alice = reader.user(DEFAULT_TENANT, ALICE_ID).orElseThrow();
        assertEquals(ALICE_UPN, alice.userPrincipalName());
        assertEquals(DEFAULT_TENANT, alice.tenantId());
        assertEquals(ALICE_ID, reader.userByPrincipalName(DEFAULT_TENANT, ALICE_UPN.toUpperCase()).orElseThrow().id());
        assertFalse(reader.group(DEFAULT_TENANT, TEAM_ID).orElseThrow().securityEnabled());
        assertEquals(Set.of(TEAM_ID), reader.memberGroupIds(DEFAULT_TENANT, ALICE_ID));
        assertEquals(Set.of(EntraStore.DEV_GROUP_OBJECT_ID),
                reader.memberGroupIds(DEFAULT_TENANT, EntraStore.DEV_USER_OBJECT_ID));
        assertEquals(EntraStore.DEV_SP_OBJECT_ID,
                reader.servicePrincipalByAppId(DEFAULT_TENANT, EntraStore.DEV_CLIENT_ID).orElseThrow().id());

        DirectoryApplication app = reader.applicationByAppId(DEFAULT_TENANT, APP_ID).orElseThrow();
        assertEquals(APP_OBJECT_ID, app.id());
        assertTrue(app.passwordCredentials().getFirst().matches(APP_SECRET));
        assertFalse(app.passwordCredentials().getFirst().matches("wrong"));
        assertEquals(2, store.list(DEFAULT_TENANT, DirectoryEntitySet.USERS).size(),
                "both legacy users list as the default tenant's users");

        assertTrue(reader.user(OTHER_TENANT, ALICE_ID).isEmpty(), "legacy entries belong to the default tenant only");
        assertTrue(reader.applicationByAppId(OTHER_TENANT, APP_ID).isEmpty());
        assertTrue(reader.memberGroupIds(OTHER_TENANT, ALICE_ID).isEmpty());

        assertEquals(before, snapshot(), "reads must never rewrite a legacy entry");
    }

    @Test
    void writingAMembershipMovesOnlyThatGroupsEntry() {
        Map<String, String> before = snapshot();

        store.addLink(DEFAULT_TENANT, TEAM_ID, GraphDirectoryStore.MEMBERS, EntraStore.DEV_USER_OBJECT_ID);

        Map<String, String> after = snapshot();
        assertFalse(after.containsKey("membership:" + TEAM_ID), "the written membership leaves the flat layout");
        assertTrue(after.containsKey("link:" + DEFAULT_TENANT + ":" + TEAM_ID + ":members"));
        assertEquals(List.of(ALICE_ID, EntraStore.DEV_USER_OBJECT_ID),
                List.copyOf(store.links(DEFAULT_TENANT, TEAM_ID, GraphDirectoryStore.MEMBERS)));
        assertEquals(Set.of(TEAM_ID, EntraStore.DEV_GROUP_OBJECT_ID),
                reader.memberGroupIds(DEFAULT_TENANT, EntraStore.DEV_USER_OBJECT_ID));
        assertUntouchedExcept(before, after, Set.of("membership:" + TEAM_ID));
    }

    @Test
    void writingAUserMovesItAndIndexesItsPrincipalName() {
        Map<String, String> before = snapshot();
        DirectoryObject alice = store.get(DEFAULT_TENANT, DirectoryEntitySet.USERS, ALICE_ID).orElseThrow();
        ObjectNode renamed = alice.props().deepCopy();
        renamed.put("displayName", "Alice Renamed");

        store.put(new DirectoryObject(ALICE_ID, DEFAULT_TENANT, "users", renamed, null));

        Map<String, String> after = snapshot();
        assertFalse(after.containsKey("user:" + ALICE_ID));
        assertTrue(after.containsKey("obj:" + DEFAULT_TENANT + ":users:" + ALICE_ID));
        assertTrue(after.get("alt:" + DEFAULT_TENANT + ":users:userPrincipalName:" + ALICE_UPN).startsWith(ALICE_ID + "|"));
        assertEquals("Alice Renamed", reader.userByPrincipalName(DEFAULT_TENANT, ALICE_UPN).orElseThrow().displayName());
        assertEquals(alice.created(), store.get(DEFAULT_TENANT, DirectoryEntitySet.USERS, ALICE_ID).orElseThrow().created(),
                "a moved object keeps its original creation time");
        assertEquals(Set.of(TEAM_ID), reader.memberGroupIds(DEFAULT_TENANT, ALICE_ID),
                "the user's untouched legacy membership still resolves");
        assertUntouchedExcept(before, after, Set.of("user:" + ALICE_ID));
    }

    @Test
    void writingAnApplicationHashesItsLegacyPlaintextSecret() {
        Map<String, String> before = snapshot();
        DirectoryObject app = store.findByAlternateKey(DEFAULT_TENANT, DirectoryEntitySet.APPLICATIONS, "appId", APP_ID)
                .orElseThrow();

        store.put(app);

        Map<String, String> after = snapshot();
        assertFalse(after.containsKey("app:" + APP_ID));
        String moved = after.get("obj:" + DEFAULT_TENANT + ":applications:" + APP_OBJECT_ID);
        assertNotNull(moved);
        assertFalse(moved.contains(APP_SECRET), "a written application must not persist its plaintext secret");
        assertTrue(moved.contains(GraphDirectoryStore.SECRET_HASH));
        assertTrue(reader.applicationByAppId(DEFAULT_TENANT, APP_ID).orElseThrow()
                .passwordCredentials().getFirst().matches(APP_SECRET));
        assertUntouchedExcept(before, after, Set.of("app:" + APP_ID));
    }

    @Test
    void deletingALegacyGroupRemovesItsFlatEntries() {
        Map<String, String> before = snapshot();

        assertTrue(store.delete(DEFAULT_TENANT, DirectoryEntitySet.GROUPS, TEAM_ID));

        Map<String, String> after = snapshot();
        assertTrue(reader.group(DEFAULT_TENANT, TEAM_ID).isEmpty());
        assertTrue(reader.memberGroupIds(DEFAULT_TENANT, ALICE_ID).isEmpty());
        assertUntouchedExcept(before, after, Set.of("group:" + TEAM_ID, "membership:" + TEAM_ID));
    }

    @Test
    void clearDropsBothLayoutsButNotTheTokenServicesKeys() {
        store.addLink(DEFAULT_TENANT, TEAM_ID, GraphDirectoryStore.MEMBERS, EntraStore.DEV_USER_OBJECT_ID);

        store.clear();

        Map<String, String> after = snapshot();
        assertTrue(after.containsKey("tenant:" + DEFAULT_TENANT), "tenants are EntraStore's to clear");
        assertTrue(after.containsKey("authcode:abc"), "authorization codes are EntraStore's to clear");
        assertTrue(reader.user(DEFAULT_TENANT, ALICE_ID).isEmpty());
        assertTrue(after.keySet().stream().noneMatch(key -> key.startsWith("user:") || key.startsWith("membership:")
                || key.startsWith("app:") || key.startsWith("sp:") || key.startsWith("group:")));
        assertTrue(after.containsKey("obj:" + DEFAULT_TENANT + ":users:" + EntraStore.DEV_USER_OBJECT_ID),
                "the reseed writes the tenant-keyed layout");
        assertTrue(reader.applicationByAppId(DEFAULT_TENANT, EntraStore.DEV_CLIENT_ID).orElseThrow()
                .passwordCredentials().getFirst().matches(EntraStore.DEV_CLIENT_SECRET));
    }

    private void assertUntouchedExcept(Map<String, String> before, Map<String, String> after, Set<String> moved) {
        for (Map.Entry<String, String> entry : before.entrySet()) {
            if (moved.contains(entry.getKey())) {
                continue;
            }
            assertEquals(entry.getValue(), after.get(entry.getKey()), "legacy entry changed: " + entry.getKey());
        }
    }

    /** Every key with its value bytes, so a test can tell an untouched entry from a rewritten one. */
    private Map<String, String> snapshot() {
        Map<String, String> snapshot = new TreeMap<>();
        for (String key : backend.keys()) {
            StoredObject stored = backend.get(key).orElseThrow();
            snapshot.put(key, new String(stored.data(), StandardCharsets.UTF_8)
                    + "|" + stored.lastModified() + "|" + stored.etag() + "|" + Arrays.hashCode(stored.data()));
        }
        return snapshot;
    }

    private void legacyWrite(String key, Object value) throws Exception {
        byte[] data = legacyMapper.writeValueAsBytes(value);
        backend.put(key, new StoredObject(key, data, Map.of(), Instant.now(),
                Integer.toHexString(new String(data, StandardCharsets.UTF_8).hashCode())));
    }
}
