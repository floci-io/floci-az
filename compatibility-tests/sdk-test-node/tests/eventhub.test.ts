/**
 * Event Hubs AMQP 1.0 compatibility tests against the Artemis sidecar.
 *
 * Uses rhea-promise (pure JS AMQP 1.0) to validate send/receive and multicast
 * delivery semantics. Each receiver connects to the event hub address and
 * Artemis assigns it an independent subscription queue — this mirrors the
 * consumer-group isolation model used by the Python/uamqp tests.
 */
import { Connection, AwaitableSender, Receiver, ReceiverEvents } from "rhea-promise";

const HOST     = process.env.EVENTHUB_HOST ?? "localhost";
const PORT     = parseInt(process.env.EVENTHUB_AMQP_PORT ?? "5672", 10);
const TLS_PORT = parseInt(process.env.EVENTHUB_AMQPS_PORT ?? "5671", 10);
const NS       = process.env.EVENTHUB_NAMESPACE ?? "emulatorNs1";
const HUB      = process.env.EVENTHUB_NAME ?? "eh1";
const BASE     = process.env.FLOCI_AZ_ENDPOINT ?? "http://localhost:4577";
const ACCOUNT  = process.env.FLOCI_AZ_ACCOUNT ?? "devstoreaccount1";
const SEND_ADDR = `${NS}/${HUB}`;

// ---- setup ------------------------------------------------------------------

beforeAll(async () => {
  const url  = `${BASE}/${ACCOUNT}-eventhub/namespaces/${NS}`;
  const body = JSON.stringify({ amqpPort: PORT, amqpTlsPort: TLS_PORT });
  const res  = await fetch(url, {
    method:  "PUT",
    headers: { "Content-Type": "application/json" },
    body,
    signal: AbortSignal.timeout(120_000),
  });
  if (!res.ok && res.status !== 409) {
    throw new Error(`Failed to start Event Hubs namespace: HTTP ${res.status}`);
  }
}, 130_000);

// ---- helpers ----------------------------------------------------------------

async function openConn(): Promise<Connection> {
  // `host` is the TCP address; `hostname` is the AMQP virtual host in the OPEN frame.
  const c = new Connection({ host: HOST, hostname: HOST, port: PORT, transport: "tcp", reconnect: false });
  await c.open();
  return c;
}

async function sendMessages(texts: string[]): Promise<void> {
  const conn = await openConn();
  const sender: AwaitableSender = await conn.createAwaitableSender({
    target: { address: SEND_ADDR },
  });
  for (const t of texts) {
    await sender.send({ body: t });
  }
  await conn.close();
}

function collectMessages(receiver: Receiver): string[] {
  const msgs: string[] = [];
  receiver.on(ReceiverEvents.message, (ctx) => {
    const body = ctx.message?.body;
    if (body != null) msgs.push(String(body));
  });
  return msgs;
}

// ---- tests ------------------------------------------------------------------

test("send and receive messages", async () => {
  const payloads = ["alpha", "beta", "gamma"].map((p) => `${p}-${Date.now()}`);

  const conn = await openConn();
  const receiver: Receiver = await conn.createReceiver({
    source: { address: SEND_ADDR },
    credit_window: 20,
  });
  const received = collectMessages(receiver);

  // Small delay so the receiver link is fully attached before sending
  await new Promise((r) => setTimeout(r, 300));
  await sendMessages(payloads);
  await new Promise((r) => setTimeout(r, 1_000));
  await conn.close();

  for (const p of payloads) {
    expect(received).toContain(p);
  }
}, 30_000);

test("high-throughput: 50 messages delivered", async () => {
  const payloads = Array.from({ length: 50 }, (_, i) => `bulk-${i}-${Date.now()}`);

  const conn = await openConn();
  const receiver: Receiver = await conn.createReceiver({
    source: { address: SEND_ADDR },
    credit_window: 60,
  });
  const received = collectMessages(receiver);

  await new Promise((r) => setTimeout(r, 300));
  await sendMessages(payloads);
  await new Promise((r) => setTimeout(r, 2_000));
  await conn.close();

  expect(received.length).toBeGreaterThanOrEqual(payloads.length);
  for (const p of payloads) {
    expect(received).toContain(p);
  }
}, 30_000);

test("two independent receivers both get all messages (multicast)", async () => {
  const payloads = ["mc-1", "mc-2", "mc-3"].map((p) => `${p}-${Date.now()}`);

  // Each receiver gets its own Artemis subscription queue — multicast delivers
  // an independent copy to each.
  const connA = await openConn();
  const connB = await openConn();

  const recvA: Receiver = await connA.createReceiver({
    source: { address: SEND_ADDR },
    credit_window: 20,
  });
  const recvB: Receiver = await connB.createReceiver({
    source: { address: SEND_ADDR },
    credit_window: 20,
  });

  const receivedA = collectMessages(recvA);
  const receivedB = collectMessages(recvB);

  await new Promise((r) => setTimeout(r, 300));
  await sendMessages(payloads);
  await new Promise((r) => setTimeout(r, 1_500));

  await connA.close();
  await connB.close();

  for (const p of payloads) {
    expect(receivedA).toContain(p);
    expect(receivedB).toContain(p);
  }
}, 30_000);

test("receiver offsets are independent across connections", async () => {
  const payloads = ["off-1", "off-2", "off-3"].map((p) => `${p}-${Date.now()}`);

  const connA = await openConn();
  const connB = await openConn();

  const recvA: Receiver = await connA.createReceiver({
    source: { address: SEND_ADDR },
    credit_window: 20,
  });
  const recvB: Receiver = await connB.createReceiver({
    source: { address: SEND_ADDR },
    credit_window: 20,
  });

  const receivedA = collectMessages(recvA);
  const receivedB = collectMessages(recvB);

  await new Promise((r) => setTimeout(r, 300));
  await sendMessages(payloads);
  await new Promise((r) => setTimeout(r, 1_000));

  // Close A first; B continues to hold its own cursor
  await connA.close();
  await new Promise((r) => setTimeout(r, 500));
  await connB.close();

  for (const p of payloads) {
    expect(receivedA).toContain(p);
    expect(receivedB).toContain(p);
  }
}, 30_000);
