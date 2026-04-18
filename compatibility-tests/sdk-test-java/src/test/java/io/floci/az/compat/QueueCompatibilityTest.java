package io.floci.az.compat;

import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.QueueServiceClient;
import com.azure.storage.queue.QueueServiceClientBuilder;
import com.azure.storage.queue.models.PeekedMessageItem;
import com.azure.storage.queue.models.QueueMessageItem;
import com.azure.storage.queue.models.QueueStorageException;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Queue Storage Compatibility")
class QueueCompatibilityTest {

    private QueueServiceClient client;

    @BeforeAll
    void setup() {
        client = new QueueServiceClientBuilder()
            .connectionString(EmulatorConfig.QUEUE_CONN)
            .buildClient();
    }

    private String queueName() {
        return "test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    // --- Golden path ---

    @Test
    @DisplayName("queue lifecycle: create → list → send → receive → delete")
    void queueLifecycle() {
        String name = queueName();
        client.createQueue(name);

        List<String> queues = client.listQueues().stream().map(q -> q.getName()).toList();
        assertTrue(queues.contains(name));

        QueueClient queue = client.getQueueClient(name);
        queue.sendMessage("Hello Queue!");

        List<QueueMessageItem> messages = queue.receiveMessages(1).stream().toList();
        assertEquals(1, messages.size());
        assertEquals("Hello Queue!", messages.get(0).getMessageText());

        queue.clearMessages();

        List<PeekedMessageItem> peeked = queue.peekMessages(10, null, null).stream().toList();
        assertEquals(0, peeked.size());

        client.deleteQueue(name);

        List<String> after = client.listQueues().stream().map(q -> q.getName()).toList();
        assertFalse(after.contains(name));
    }

    @Test
    @DisplayName("multiple messages: send 5 → peek 5 → all present")
    void multipleMessages() {
        String name = queueName();
        QueueClient queue = client.createQueue(name);

        for (int i = 0; i < 5; i++) {
            queue.sendMessage("msg-" + i);
        }

        List<PeekedMessageItem> peeked = queue.peekMessages(5, null, null).stream().toList();
        assertEquals(5, peeked.size());

        client.deleteQueue(name);
    }

    @Test
    @DisplayName("peek does not consume: message still receivable after peek")
    void peekDoesNotConsume() {
        String name = queueName();
        QueueClient queue = client.createQueue(name);
        queue.sendMessage("persistent");

        queue.peekMessages(1, null, null);
        queue.peekMessages(1, null, null);

        List<QueueMessageItem> received = queue.receiveMessages(1).stream().toList();
        assertEquals(1, received.size());
        assertEquals("persistent", received.get(0).getMessageText());

        client.deleteQueue(name);
    }

    @Test
    @DisplayName("delete message: received message removed from queue")
    void deleteMessage() {
        String name = queueName();
        QueueClient queue = client.createQueue(name);
        queue.sendMessage("to-delete");

        List<QueueMessageItem> messages = queue.receiveMessages(1).stream().toList();
        assertEquals(1, messages.size());

        QueueMessageItem msg = messages.get(0);
        queue.deleteMessage(msg.getMessageId(), msg.getPopReceipt());

        assertEquals(0, queue.peekMessages(10, null, null).stream().count());

        client.deleteQueue(name);
    }

    // --- Error cases ---

    @Test
    @DisplayName("receive from non-existent queue → QueueStorageException (404)")
    void queueNotFound() {
        QueueClient queue = client.getQueueClient("nonexistent-queue-xyz-abc");
        QueueStorageException ex = assertThrows(QueueStorageException.class,
            () -> queue.receiveMessages(1).stream().toList());
        assertEquals(404, ex.getStatusCode());
    }
}
