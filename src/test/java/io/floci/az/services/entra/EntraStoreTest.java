package io.floci.az.services.entra;

import io.floci.az.services.entra.EntraModels.AuthorizationCode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class EntraStoreTest {

    @Inject EntraStore store;

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
}
