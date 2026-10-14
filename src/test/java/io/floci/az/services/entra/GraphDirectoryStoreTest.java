package io.floci.az.services.entra;

import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageFactory;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class GraphDirectoryStoreTest {

    @Inject GraphDirectoryStore store;
    @Inject DirectoryReader reader;
    @Inject StorageFactory storageFactory;

    private String tenant() {
        return store.defaultTenantId();
    }

    @Test
    void seedsDevUserAsMemberOfDevGroup() {
        assertTrue(reader.user(tenant(), EntraStore.DEV_USER_OBJECT_ID).isPresent());
        assertTrue(reader.group(tenant(), EntraStore.DEV_GROUP_OBJECT_ID).isPresent());
        assertEquals(Set.of(EntraStore.DEV_GROUP_OBJECT_ID),
            reader.memberGroupIds(tenant(), EntraStore.DEV_USER_OBJECT_ID));
    }

    @Test
    void seedsTheDevAppWithAHashedSecretThatValidates() {
        DirectoryReader.DirectoryApplication app =
            reader.applicationByAppId(tenant(), EntraStore.DEV_CLIENT_ID).orElseThrow();
        assertEquals(EntraStore.DEV_APP_OBJECT_ID, app.id());
        assertTrue(app.passwordCredentials().getFirst().matches(EntraStore.DEV_CLIENT_SECRET));
        assertFalse(app.passwordCredentials().getFirst().matches("fake-secret"));
        assertEquals(EntraStore.DEV_SP_OBJECT_ID,
            reader.servicePrincipalByAppId(tenant(), EntraStore.DEV_CLIENT_ID).orElseThrow().id());

        for (StoredObject stored : storageFactory.create("entra").scan(key -> true)) {
            assertFalse(new String(stored.data(), StandardCharsets.UTF_8).contains(EntraStore.DEV_CLIENT_SECRET),
                "no entry may hold the plaintext dev secret: " + stored.key());
        }
    }

    @Test
    void findUserByPrincipalNameIsCaseInsensitive() {
        assertTrue(reader.userByPrincipalName(tenant(), EntraStore.DEV_USER_UPN.toUpperCase()).isPresent());
    }

    @Test
    void objectsAreKeptPerTenant() {
        String otherTenant = UUID.randomUUID().toString();
        String userId = "user-" + UUID.randomUUID();
        store.put(GraphDirectoryStore.user(otherTenant, userId, userId + "@other.local", "other", null));

        assertTrue(reader.user(otherTenant, userId).isPresent());
        assertTrue(reader.user(tenant(), userId).isEmpty(), "another tenant's object must not leak");
        assertTrue(reader.userByPrincipalName(otherTenant, userId + "@OTHER.local").isPresent());
        assertTrue(reader.user(otherTenant, EntraStore.DEV_USER_OBJECT_ID).isEmpty(),
            "the dev fixtures belong to the default tenant");
    }

    @Test
    void addAndRemoveMemberUpdatesMemberGroups() {
        String userId = "user-" + UUID.randomUUID();
        String groupId = "group-" + UUID.randomUUID();
        store.put(GraphDirectoryStore.user(tenant(), userId, userId + "@floci-az.local", "test user", null));
        store.put(GraphDirectoryStore.group(tenant(), groupId, "test group", true));

        assertTrue(reader.memberGroupIds(tenant(), userId).isEmpty());

        store.addLink(tenant(), groupId, GraphDirectoryStore.MEMBERS, userId);
        assertEquals(Set.of(groupId), reader.memberGroupIds(tenant(), userId));

        store.removeLink(tenant(), groupId, GraphDirectoryStore.MEMBERS, userId);
        assertTrue(reader.memberGroupIds(tenant(), userId).isEmpty());
    }

    @Test
    void clearWipesMembershipStateAndReseedsDevFixtures() {
        String userId = "user-" + UUID.randomUUID();
        store.put(GraphDirectoryStore.user(tenant(), userId, userId + "@floci-az.local", "test user", null));
        store.addLink(tenant(), EntraStore.DEV_GROUP_OBJECT_ID, GraphDirectoryStore.MEMBERS, userId);
        assertTrue(reader.memberGroupIds(tenant(), userId).contains(EntraStore.DEV_GROUP_OBJECT_ID));

        store.clear();

        assertTrue(reader.user(tenant(), userId).isEmpty(), "clear() must drop state created after seeding");
        assertTrue(reader.memberGroupIds(tenant(), userId).isEmpty(),
            "clear() must drop membership rows created after seeding");
        assertTrue(reader.user(tenant(), EntraStore.DEV_USER_OBJECT_ID).isPresent(),
            "clear() must reseed the zero-setup dev user");
        assertEquals(Set.of(EntraStore.DEV_GROUP_OBJECT_ID),
            reader.memberGroupIds(tenant(), EntraStore.DEV_USER_OBJECT_ID),
            "clear() must reseed the dev user's group membership, not just the bare records");
        assertTrue(reader.applicationByAppId(tenant(), EntraStore.DEV_CLIENT_ID).isPresent(),
            "clear() must reseed the zero-setup dev app registration");
    }

    @Test
    void concurrentMembershipMutationsAreNotLost() throws Exception {
        String groupId = "group-" + UUID.randomUUID();
        store.put(GraphDirectoryStore.group(tenant(), groupId, "concurrent group", true));

        int memberCount = 16;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(memberCount);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            List<String> memberIds = new ArrayList<>();
            for (int i = 0; i < memberCount; i++) {
                String memberId = "member-" + i + "-" + UUID.randomUUID();
                memberIds.add(memberId);
                tasks.add(executor.submit(() -> {
                    go.await();
                    store.addLink(tenant(), groupId, GraphDirectoryStore.MEMBERS, memberId);
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> task : tasks) {
                task.get();
            }
            for (String memberId : memberIds) {
                assertTrue(reader.memberGroupIds(tenant(), memberId).contains(groupId),
                    "concurrent addLink must not lose a membership write");
            }
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void deletingAnObjectKeepsAnAlternateKeyAnotherObjectTookOver() {
        String otherTenant = UUID.randomUUID().toString();
        String principalName = "shared-" + UUID.randomUUID() + "@other.local";
        store.put(GraphDirectoryStore.user(otherTenant, "first", principalName, "first", null));
        store.put(GraphDirectoryStore.user(otherTenant, "second", principalName, "second", null));

        assertTrue(store.delete(otherTenant, DirectoryEntitySet.USERS, "first"));

        assertEquals("second", reader.userByPrincipalName(otherTenant, principalName).orElseThrow().id());
    }

    @Test
    void aMalformedStoredHashMatchesNothing() {
        SecretHasher.SecretHash valid = GraphDirectoryStore.DEV_SECRET_HASH;

        assertFalse(SecretHasher.matches(new SecretHasher.SecretHash(
            valid.algorithm(), valid.iterations(), valid.salt(), "not*base64"), EntraStore.DEV_CLIENT_SECRET));
        assertFalse(SecretHasher.matches(new SecretHasher.SecretHash(
            valid.algorithm(), valid.iterations(), "not*base64", valid.hash()), EntraStore.DEV_CLIENT_SECRET));
        assertFalse(SecretHasher.matches(new SecretHasher.SecretHash(
            valid.algorithm(), valid.iterations(), "", valid.hash()), EntraStore.DEV_CLIENT_SECRET));
    }

    @Test
    void precomputedDevSecretHashMatchesTheDevSecret() {
        assertTrue(SecretHasher.matches(GraphDirectoryStore.DEV_SECRET_HASH, EntraStore.DEV_CLIENT_SECRET));
    }
}
