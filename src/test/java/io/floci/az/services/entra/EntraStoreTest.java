package io.floci.az.services.entra;

import io.floci.az.services.entra.EntraModels.AuthorizationCode;
import io.floci.az.services.entra.EntraModels.Group;
import io.floci.az.services.entra.EntraModels.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class EntraStoreTest {

    @Inject EntraStore store;

    @Test
    void seedsDevUserAsMemberOfDevGroup() {
        assertTrue(store.getUser(EntraStore.DEV_USER_OBJECT_ID).isPresent());
        assertTrue(store.getGroup(EntraStore.DEV_GROUP_OBJECT_ID).isPresent());
        assertEquals(Set.of(EntraStore.DEV_GROUP_OBJECT_ID),
            store.memberGroups(EntraStore.DEV_USER_OBJECT_ID));
    }

    @Test
    void findUserByUpnIsCaseInsensitive() {
        assertTrue(store.findUserByUpn(EntraStore.DEV_USER_UPN.toUpperCase()).isPresent());
    }

    @Test
    void addAndRemoveMemberUpdatesMemberGroups() {
        String userId = "user-" + java.util.UUID.randomUUID();
        String groupId = "group-" + java.util.UUID.randomUUID();
        store.putUser(new User(userId, userId + "@floci-az.local", "test user", null, "tenant"));
        store.putGroup(new Group(groupId, "test group", "tenant", true));

        assertTrue(store.memberGroups(userId).isEmpty());

        store.addMember(groupId, userId);
        assertEquals(Set.of(groupId), store.memberGroups(userId));

        store.removeMember(groupId, userId);
        assertTrue(store.memberGroups(userId).isEmpty());
    }

    @Test
    void authorizationCodeIsSingleUse() {
        var code = new AuthorizationCode("code-" + java.util.UUID.randomUUID(), "tenant-a", "client-id",
            "https://app.local/callback", "challenge", "S256", "openid", EntraStore.DEV_USER_OBJECT_ID,
            "nonce-1", "state-1", Instant.now().plusSeconds(300));
        store.putAuthorizationCode(code);

        assertTrue(store.consumeAuthorizationCode(code.code()).isPresent());
        assertTrue(store.consumeAuthorizationCode(code.code()).isEmpty(), "a redeemed code must not be reusable");
    }

    @Test
    void concurrentConsumptionOfSameCodeSucceedsExactlyOnce() throws Exception {
        var code = new AuthorizationCode("code-" + java.util.UUID.randomUUID(), "tenant-a", "client-id",
            "https://app.local/callback", null, "plain", "openid", EntraStore.DEV_USER_OBJECT_ID,
            "nonce-1", "state-1", Instant.now().plusSeconds(300));
        store.putAuthorizationCode(code);

        int attempts = 16;
        var ready = new java.util.concurrent.CountDownLatch(attempts);
        var go = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(attempts);
        try {
            List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return store.consumeAuthorizationCode(code.code()).isPresent();
                }));
            }
            ready.await();
            go.countDown();
            long successes = 0;
            for (var result : results) {
                if (result.get()) successes++;
            }
            assertEquals(1, successes, "exactly one concurrent redemption must succeed");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void concurrentMembershipMutationsAreNotLost() throws Exception {
        String groupId = "group-" + java.util.UUID.randomUUID();
        store.putGroup(new Group(groupId, "concurrent group", "tenant", true));

        int memberCount = 16;
        var go = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(memberCount);
        try {
            List<java.util.concurrent.Future<?>> tasks = new java.util.ArrayList<>();
            List<String> memberIds = new java.util.ArrayList<>();
            for (int i = 0; i < memberCount; i++) {
                String memberId = "member-" + i + "-" + java.util.UUID.randomUUID();
                memberIds.add(memberId);
                store.putUser(new User(memberId, memberId + "@floci-az.local", "member " + i, null, "tenant"));
                tasks.add(executor.submit(() -> {
                    go.await();
                    store.addMember(groupId, memberId);
                    return null;
                }));
            }
            go.countDown();
            for (var task : tasks) {
                task.get();
            }
            for (String memberId : memberIds) {
                assertTrue(store.memberGroups(memberId).contains(groupId),
                    "concurrent addMember must not lose a membership write");
            }
        } finally {
            executor.shutdown();
        }
    }
}
