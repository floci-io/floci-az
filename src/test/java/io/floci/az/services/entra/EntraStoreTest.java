package io.floci.az.services.entra;

import io.floci.az.services.entra.EntraModels.AuthorizationCode;
import io.floci.az.services.entra.EntraModels.Group;
import io.floci.az.services.entra.EntraModels.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
        var code = new AuthorizationCode("code-" + java.util.UUID.randomUUID(), "client-id",
            "https://app.local/callback", "challenge", "S256", "openid", EntraStore.DEV_USER_OBJECT_ID,
            "nonce-1", "state-1", Instant.now().plusSeconds(300));
        store.putAuthorizationCode(code);

        assertTrue(store.consumeAuthorizationCode(code.code()).isPresent());
        assertTrue(store.consumeAuthorizationCode(code.code()).isEmpty(), "a redeemed code must not be reusable");
    }
}
