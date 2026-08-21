import { ServiceBusClient, ServiceBusMessage } from "@azure/service-bus";

const BASE = process.env.FLOCI_AZ_ENDPOINT ?? "http://localhost:4577";
const ACCOUNT = process.env.FLOCI_AZ_ACCOUNT ?? "devstoreaccount1";
const HOST = process.env.SERVICEBUS_HOST ?? "localhost";
const PORT = parseInt(process.env.SERVICEBUS_AMQP_PORT ?? "5673", 10);
const NAMESPACE = process.env.SERVICEBUS_NAMESPACE ?? "default";
const CONNECTION_STRING =
  `Endpoint=sb://${HOST}:${PORT};` +
  "SharedAccessKeyName=RootManageSharedAccessKey;" +
  "SharedAccessKey=devkey;UseDevelopmentEmulator=true;";

beforeAll(async () => {
  const response = await fetch(
    `${BASE}/${ACCOUNT}-servicebus/namespaces/${NAMESPACE}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: "{}",
      signal: AbortSignal.timeout(120_000),
    },
  );
  if (!response.ok) {
    throw new Error(`Failed to start Service Bus namespace: HTTP ${response.status}`);
  }
  const namespace = (await response.json()) as Record<string, unknown>;
  if (namespace["mocked"] !== false) {
    throw new Error("Service Bus compatibility tests require mocked=false");
  }
}, 130_000);

function uniqueName(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

async function createEntity(path: string): Promise<void> {
  const response = await fetch(
    `${BASE}/${ACCOUNT}-servicebus/${NAMESPACE}/${path}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/atom+xml;charset=utf-8" },
      body: "",
      signal: AbortSignal.timeout(30_000),
    },
  );
  if (!response.ok) {
    throw new Error(`Failed to create Service Bus entity '${path}': HTTP ${response.status}`);
  }
}

function testMessage(body: string, rank: number): ServiceBusMessage {
  return {
    body,
    messageId: `id-${body}`,
    correlationId: `correlation-${rank}`,
    subject: "peek-subject",
    applicationProperties: { rank, color: "blue" },
  };
}

test("queue peek is bounded, repeatable, and non-destructive", async () => {
  const queue = uniqueName("peek-queue");
  await createEntity(`queues/${queue}`);
  const client = new ServiceBusClient(CONNECTION_STRING);
  const sender = client.createSender(queue);
  const receiver = client.createReceiver(queue);

  try {
    const sent = [testMessage("first", 1), testMessage("second", 2), testMessage("third", 3)];
    for (const message of sent) {
      await sender.sendMessages(message);
    }

    const firstPeek = await receiver.peekMessages(2);
    expect(firstPeek).toHaveLength(2);
    expect(firstPeek.map((message) => message.body)).toEqual(["first", "second"]);
    expect(firstPeek[0].messageId).toBe("id-first");
    expect(firstPeek[0].correlationId).toBe("correlation-1");
    expect(firstPeek[0].subject).toBe("peek-subject");
    expect(firstPeek[0].applicationProperties).toEqual({ rank: 1, color: "blue" });
    expect(firstPeek[0].sequenceNumber).toBeDefined();
    expect(firstPeek[0].enqueuedTimeUtc).toBeInstanceOf(Date);
    expect(firstPeek[0].deliveryCount).toBe(0);

    const repeatedPeek = await receiver.peekMessages(2, {
      fromSequenceNumber: firstPeek[0].sequenceNumber!,
    });
    expect(repeatedPeek.map((message) => message.body)).toEqual(["first", "second"]);
    expect(repeatedPeek.map((message) => message.deliveryCount)).toEqual(
      firstPeek.map((message) => message.deliveryCount),
    );
    expect(repeatedPeek.map((message) => message.enqueuedTimeUtc?.getTime())).toEqual(
      firstPeek.map((message) => message.enqueuedTimeUtc?.getTime()),
    );

    const fromSecond = await receiver.peekMessages(2, {
      fromSequenceNumber: firstPeek[1].sequenceNumber!,
    });
    expect(fromSecond.map((message) => message.body)).toEqual(["second", "third"]);

    const received = await receiver.receiveMessages(3, { maxWaitTimeInMs: 5_000 });
    expect(received.map((message) => message.body)).toEqual(["first", "second", "third"]);
    await Promise.all(received.map((message) => receiver.completeMessage(message)));
    expect(await receiver.peekMessages(1)).toEqual([]);
  } finally {
    await receiver.close();
    await sender.close();
    await client.close();
  }
}, 30_000);

test("subscription peek leaves message available for receive", async () => {
  const topic = uniqueName("peek-topic");
  const subscription = "messages";
  await createEntity(`topics/${topic}`);
  await createEntity(`topics/${topic}/subscriptions/${subscription}`);
  const client = new ServiceBusClient(CONNECTION_STRING);
  const sender = client.createSender(topic);
  const receiver = client.createReceiver(topic, subscription);

  try {
    await sender.sendMessages(testMessage("subscription-body", 7));
    const peeked = await receiver.peekMessages(20);
    expect(peeked).toHaveLength(1);
    expect(peeked[0].body).toBe("subscription-body");
    expect(peeked[0].applicationProperties).toEqual({ rank: 7, color: "blue" });

    const received = await receiver.receiveMessages(1, { maxWaitTimeInMs: 5_000 });
    expect(received).toHaveLength(1);
    expect(received[0].body).toBe("subscription-body");
    await receiver.completeMessage(received[0]);
  } finally {
    await receiver.close();
    await sender.close();
    await client.close();
  }
}, 30_000);

test("empty queue peek returns no messages", async () => {
  const queue = uniqueName("peek-empty");
  await createEntity(`queues/${queue}`);
  const client = new ServiceBusClient(CONNECTION_STRING);
  const receiver = client.createReceiver(queue);
  try {
    expect(await receiver.peekMessages(20)).toEqual([]);
  } finally {
    await receiver.close();
    await client.close();
  }
}, 30_000);

test("concurrent clients receive their correlated peek responses", async () => {
  const queue = uniqueName("peek-concurrent");
  await createEntity(`queues/${queue}`);

  const senderClient = new ServiceBusClient(CONNECTION_STRING);
  const sender = senderClient.createSender(queue);
  const receiverClients = Array.from(
    { length: 4 },
    () => new ServiceBusClient(CONNECTION_STRING),
  );
  const receivers = receiverClients.map((client) => client.createReceiver(queue));

  try {
    await sender.sendMessages(testMessage("shared", 1));
    const [firstPeek] = await receivers[0].peekMessages(1);
    expect(firstPeek?.body).toBe("shared");
    const sequenceNumber = firstPeek.sequenceNumber!;
    for (const receiver of receivers.slice(1)) {
      const messages = await receiver.peekMessages(1, { fromSequenceNumber: sequenceNumber });
      expect(messages[0]?.body).toBe("shared");
    }

    const results = await Promise.all(
      receivers.map((receiver) =>
        receiver.peekMessages(1, { fromSequenceNumber: sequenceNumber! }),
      ),
    );
    expect(results.map((messages) => messages[0]?.body)).toEqual([
      "shared",
      "shared",
      "shared",
      "shared",
    ]);
  } finally {
    await Promise.all(receivers.map((receiver) => receiver.close()));
    await sender.close();
    await Promise.all(receiverClients.map((client) => client.close()));
    await senderClient.close();
  }
}, 30_000);
