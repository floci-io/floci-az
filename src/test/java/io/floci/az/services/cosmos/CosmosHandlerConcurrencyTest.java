package io.floci.az.services.cosmos;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CosmosHandlerConcurrencyTest {

    @Test
    @SuppressWarnings("unchecked")
    void clearAllWaitsForInFlightDocumentOperation() throws Exception {
        StorageBackend<String, StoredObject> store = mock(StorageBackend.class);
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create("cosmos")).thenReturn(store);
        CosmosHandler handler = new CosmosHandler(factory, mock(EmulatorConfig.class));

        CountDownLatch clearStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> clear;
            // routeDocs uses this monitor for point mutations and transactional batches.
            synchronized (handler) {
                clear = executor.submit(() -> {
                    clearStarted.countDown();
                    handler.clear();
                });

                assertTrue(clearStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> clear.get(200, TimeUnit.MILLISECONDS));
            }

            clear.get(5, TimeUnit.SECONDS);
            verify(store).clear();
        } finally {
            executor.shutdownNow();
        }
    }
}
