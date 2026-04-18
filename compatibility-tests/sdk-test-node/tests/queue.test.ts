import { QueueServiceClient } from "@azure/storage-queue";
import { QUEUE_CONN, randomSuffix } from "./config";

const client = QueueServiceClient.fromConnectionString(QUEUE_CONN);

function queueName(): string {
  return `test-${randomSuffix()}`;
}

// --- Golden path ---

test("queue lifecycle: create → list → send → receive → delete", async () => {
  const name = queueName();
  await client.createQueue(name);

  const queues: string[] = [];
  for await (const q of client.listQueues()) queues.push(q.name);
  expect(queues).toContain(name);

  const queue = client.getQueueClient(name);
  await queue.sendMessage("Hello Queue!");

  const recv = await queue.receiveMessages({ numberOfMessages: 1 });
  expect(recv.receivedMessageItems).toHaveLength(1);
  expect(recv.receivedMessageItems[0].messageText).toBe("Hello Queue!");

  await queue.clearMessages();

  const peeked = await queue.peekMessages({ numberOfMessages: 10 });
  expect(peeked.peekedMessageItems).toHaveLength(0);

  await client.deleteQueue(name);

  const after: string[] = [];
  for await (const q of client.listQueues()) after.push(q.name);
  expect(after).not.toContain(name);
});

test("multiple messages: send 5 → peek 5 → all present", async () => {
  const name = queueName();
  await client.createQueue(name);
  const queue = client.getQueueClient(name);

  for (let i = 0; i < 5; i++) {
    await queue.sendMessage(`msg-${i}`);
  }

  const peeked = await queue.peekMessages({ numberOfMessages: 5 });
  expect(peeked.peekedMessageItems).toHaveLength(5);

  await client.deleteQueue(name);
});

test("peek does not consume: message still receivable after peek", async () => {
  const name = queueName();
  await client.createQueue(name);
  const queue = client.getQueueClient(name);

  await queue.sendMessage("persistent");
  await queue.peekMessages({ numberOfMessages: 1 });
  await queue.peekMessages({ numberOfMessages: 1 });

  const recv = await queue.receiveMessages({ numberOfMessages: 1 });
  expect(recv.receivedMessageItems).toHaveLength(1);
  expect(recv.receivedMessageItems[0].messageText).toBe("persistent");

  await client.deleteQueue(name);
});

test("delete message: removed from queue after explicit delete", async () => {
  const name = queueName();
  await client.createQueue(name);
  const queue = client.getQueueClient(name);

  await queue.sendMessage("to-delete");
  const recv = await queue.receiveMessages({ numberOfMessages: 1 });
  const msg = recv.receivedMessageItems[0];
  await queue.deleteMessage(msg.messageId, msg.popReceipt);

  const peeked = await queue.peekMessages({ numberOfMessages: 10 });
  expect(peeked.peekedMessageItems).toHaveLength(0);

  await client.deleteQueue(name);
});

// --- Error cases ---

test("receive from non-existent queue → 404", async () => {
  const queue = client.getQueueClient("nonexistent-queue-xyz-abc");
  await expect(queue.receiveMessages()).rejects.toMatchObject({ statusCode: 404 });
});
