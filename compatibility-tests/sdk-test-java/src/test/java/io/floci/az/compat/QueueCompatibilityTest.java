package io.floci.az.compat;

import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.QueueServiceClient;
import com.azure.storage.queue.QueueServiceClientBuilder;
import com.azure.storage.queue.models.PeekedMessageItem;
import com.azure.storage.queue.models.QueueItem;
import com.azure.storage.queue.models.QueueMessageItem;
import com.azure.storage.queue.models.QueueStorageException;
import com.azure.storage.queue.models.QueuesSegmentOptions;
import com.azure.storage.queue.models.UpdateMessageResult;
import com.azure.core.util.Context;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Queue Storage Compatibility")
class QueueCompatibilityTest {

    private QueueServiceClient client;

    @BeforeAll
    void setup() {
        EmulatorConfig.assumeEmulatorRunning();
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

    @Test
    @DisplayName("queue metadata: create, get properties, and list with metadata")
    void queueMetadataRoundTrip() {
        String name = queueName();
        Map<String, String> metadata = Map.of("owner", "compat", "purpose", "queue-parity");

        QueueClient queue = client.createQueueWithResponse(name, metadata, null, Context.NONE).getValue();

        assertEquals(metadata, queue.getProperties().getMetadata());

        QueuesSegmentOptions options = new QueuesSegmentOptions()
            .setPrefix(name)
            .setIncludeMetadata(true);
        List<QueueItem> queues = client.listQueues(options, null, Context.NONE).stream().toList();
        assertEquals(1, queues.size());
        assertEquals(metadata, queues.get(0).getMetadata());

        client.deleteQueue(name);
    }

    @Test
    @DisplayName("send message visibility timeout: hidden first, visible later")
    void sendMessageVisibilityTimeout() throws Exception {
        String name = queueName();
        QueueClient queue = client.createQueue(name);

        queue.sendMessageWithResponse("delayed", Duration.ofSeconds(1), Duration.ofSeconds(5), null, Context.NONE);

        assertEquals(0, queue.peekMessages(1, null, null).stream().count());
        Thread.sleep(2000);

        List<PeekedMessageItem> peeked = queue.peekMessages(1, null, null).stream().toList();
        assertEquals(1, peeked.size());
        assertEquals("delayed", peeked.get(0).getMessageText());

        client.deleteQueue(name);
    }

    @Test
    @DisplayName("message TTL: expired messages are not visible")
    void messageTtlExpiration() throws Exception {
        String name = queueName();
        QueueClient queue = client.createQueue(name);

        queue.sendMessageWithResponse("short-lived", null, Duration.ofSeconds(1), null, Context.NONE);

        Thread.sleep(2000);
        assertEquals(0, queue.peekMessages(1, null, null).stream().count());

        client.deleteQueue(name);
    }

    @Test
    @DisplayName("wrong pop receipt: delete is rejected")
    void wrongPopReceiptRejected() {
        String name = queueName();
        QueueClient queue = client.createQueue(name);

        queue.sendMessage("guarded");
        QueueMessageItem message = queue.receiveMessages(1).stream().toList().get(0);

        QueueStorageException ex = assertThrows(QueueStorageException.class,
            () -> queue.deleteMessage(message.getMessageId(), "wrong-receipt"));
        assertEquals(400, ex.getStatusCode());

        queue.deleteMessage(message.getMessageId(), message.getPopReceipt());
        client.deleteQueue(name);
    }

    @Test
    @DisplayName("update message: replaces content and rotates pop receipt")
    void updateMessageReplacesContentAndRotatesPopReceipt() {
        String name = queueName();
        QueueClient queue = client.createQueue(name);

        queue.sendMessage("before");
        QueueMessageItem message = queue.receiveMessages(1).stream().toList().get(0);
        UpdateMessageResult updated = queue.updateMessage(
            message.getMessageId(), message.getPopReceipt(), "after", Duration.ZERO);

        QueueStorageException ex = assertThrows(QueueStorageException.class,
            () -> queue.deleteMessage(message.getMessageId(), message.getPopReceipt()));
        assertEquals(400, ex.getStatusCode());

        List<PeekedMessageItem> peeked = queue.peekMessages(1, null, null).stream().toList();
        assertEquals(1, peeked.size());
        assertEquals("after", peeked.get(0).getMessageText());

        queue.deleteMessage(message.getMessageId(), updated.getPopReceipt());
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
