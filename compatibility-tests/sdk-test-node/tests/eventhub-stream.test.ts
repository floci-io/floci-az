/**
 * Event Hubs reads, through rhea against the partition addresses the Azure SDKs use.
 *
 * In Event Hubs a read never removes an event. Every receiver reads a partition from its own start
 * position, independently of every other receiver, and an event stays until its hub's retention
 * ends. These read the same events several times over, and check that an event leaves only when
 * its retention has passed.
 */
import { Connection, Receiver, ReceiverEvents, types } from "rhea-promise";

const HOST    = process.env.EVENTHUB_HOST ?? "localhost";
const PORT    = parseInt(process.env.EVENTHUB_AMQP_PORT ?? "5672", 10);
const NS      = process.env.EVENTHUB_NAMESPACE ?? "emulatorNs1";
const HUB     = process.env.EVENTHUB_NAME ?? "eh1";
const BASE    = process.env.FLOCI_AZ_ENDPOINT ?? "http://localhost:4577";
const ACCOUNT = process.env.FLOCI_AZ_ACCOUNT ?? "devstoreaccount1";

const PARTITION = "2";
const SEND_ADDR = `${HUB}/Partitions/${PARTITION}`;
const QUEUE     = `${HUB}/ConsumerGroups/$Default/Partitions/${PARTITION}`;
/**
 * A partition of a consumer group no other test reads, so that only these receivers count toward its
 * limits. Each test of the limits reads a partition of its own, so that the receivers one test closes
 * cannot count toward the next one's.
 */
const limitsQueue = (partition: string) => `${HUB}/ConsumerGroups/my-consumer-group/Partitions/${partition}`;
/** The descriptor of the selector filter the Azure SDKs send a start position as. */
const SELECTOR_FILTER = 0x468c00000004;
/** How long a receiver gets to attach before the events it must see are sent. */
const ATTACH_WAIT_MS = 1_000;
/** How long a receiver gets to take every event it can. */
const READ_WAIT_MS = 2_000;

let eventHubMocked = false;

beforeAll(async () => {
  const res = await putNamespace(NS, { amqpPort: PORT, amqpTlsPort: 5671 });
  eventHubMocked = (await res.json() as Record<string, unknown>)["mocked"] === true;
}, 130_000);

// ---- helpers ----------------------------------------------------------------

/** A received event: its body, application properties and annotations. */
type ReceivedEvent = {
  body?: unknown;
  application_properties?: Record<string, unknown>;
  message_annotations?: Record<string, unknown>;
};

function newTestId(): string {
  return `${Date.now()}${Math.floor(Math.random() * 1e9)}`;
}

async function putNamespace(namespace: string, body: Record<string, unknown>): Promise<Response> {
  const res = await fetch(`${BASE}/${ACCOUNT}-eventhub/namespaces/${namespace}`, {
    method:  "PUT",
    headers: { "Content-Type": "application/json" },
    body:    JSON.stringify(body),
    signal:  AbortSignal.timeout(120_000),
  });
  if (!res.ok && res.status !== 409) {
    throw new Error(`Failed to start Event Hubs namespace ${namespace}: HTTP ${res.status}`);
  }
  return res;
}

async function openConn(host = HOST, port = PORT): Promise<Connection> {
  const c = new Connection({ host, hostname: host, port, transport: "tcp", reconnect: false });
  await c.open();
  return c;
}

async function send(testId: string, count: number, conn: Connection, address = SEND_ADDR): Promise<void> {
  const sender = await conn.createAwaitableSender({ target: { address } });
  for (let i = 0; i < count; i++) {
    await sender.send({ body: `event-${i}`, application_properties: { testId } });
  }
  await sender.close();
}

/** A receiver on the partition that takes only this test's events at or after the position. */
async function attach(conn: Connection, testId: string, position?: string, queue = QUEUE): Promise<Receiver> {
  const expression = position ? `${position} AND testId = '${testId}'` : `testId = '${testId}'`;
  return conn.createReceiver({
    source: {
      address: queue,
      filter: { "apache.org:selector-filter:string": types.wrap_described(expression, SELECTOR_FILTER) },
    },
    credit_window: 50,
  });
}

function collect(receiver: Receiver): ReceivedEvent[] {
  const received: ReceivedEvent[] = [];
  receiver.on(ReceiverEvents.message, (ctx) => {
    if (ctx.message) received.push(ctx.message);
  });
  return received;
}

/** The bodies a new receiver on the queue reads, from the position or from the start. */
async function readAll(testId: string, position?: string, host = HOST, port = PORT, queue = QUEUE): Promise<string[]> {
  const conn = await openConn(host, port);
  const received = collect(await attach(conn, testId, position, queue));
  await new Promise((r) => setTimeout(r, READ_WAIT_MS));
  await conn.close();
  return received.map((m) => String(m.body));
}

/** The AMQP error condition an error carries, if any. */
function conditionOf(error: unknown): string | undefined {
  return error && typeof error === "object" && "condition" in error ? String(error.condition) : undefined;
}

/** A receiver on the queue, with an epoch (the SDKs' owner level) if one is given. */
function epochReceiver(conn: Connection, queue: string, epoch?: number): Promise<Receiver> {
  const properties = epoch === undefined ? undefined : { "com.microsoft:epoch": types.wrap_long(epoch) };
  return conn.createReceiver({ source: { address: queue }, properties });
}

/** The error condition a receiver is refused with, or undefined if it is admitted. */
async function refusal(conn: Connection, queue: string, epoch?: number): Promise<string | undefined> {
  try {
    const receiver = await epochReceiver(conn, queue, epoch);
    // A refusal can also come as a detach right after the attach.
    await new Promise((r) => setTimeout(r, READ_WAIT_MS));
    return conditionOf(receiver.error);
  } catch (error) {
    return conditionOf(error);
  }
}

const bodies = (from: number, to: number) => Array.from({ length: to - from }, (_, i) => `event-${from + i}`);

// ---- tests ------------------------------------------------------------------

test("two receivers on one partition and consumer group each get every event, sent after they attached", async () => {
  if (eventHubMocked) return;
  const testId = newTestId();
  const conn = await openConn();
  const first = collect(await attach(conn, testId));
  const second = collect(await attach(conn, testId));
  await new Promise((r) => setTimeout(r, ATTACH_WAIT_MS));

  await send(testId, 5, conn);
  await new Promise((r) => setTimeout(r, READ_WAIT_MS));
  await conn.close();

  expect(first.map((m) => String(m.body))).toEqual(bodies(0, 5));
  expect(second.map((m) => String(m.body))).toEqual(bodies(0, 5));
}, 30_000);

test("after a read, a new receiver gets the same events again", async () => {
  if (eventHubMocked) return;
  const testId = newTestId();
  const conn = await openConn();
  await send(testId, 5, conn);
  await conn.close();

  expect(await readAll(testId)).toEqual(bodies(0, 5));
  expect(await readAll(testId)).toEqual(bodies(0, 5));
}, 30_000);

test("a receiver from a sequence number re-reads events another receiver read", async () => {
  if (eventHubMocked) return;
  const testId = newTestId();
  const conn = await openConn();
  await send(testId, 5, conn);
  const received = collect(await attach(conn, testId));
  await new Promise((r) => setTimeout(r, READ_WAIT_MS));
  await conn.close();
  expect(received.map((m) => String(m.body))).toEqual(bodies(0, 5));
  const middle = Number(received[2].message_annotations?.["x-opt-sequence-number"]);

  expect(await readAll(testId, `amqp.annotation.x-opt-sequence-number >= ${middle}`)).toEqual(bodies(2, 5));
}, 30_000);

test("an event leaves its partition when its hub's retention ends, and not before", async () => {
  if (eventHubMocked) return;
  const testId = newTestId();
  const namespace = `retain${testId.slice(-8)}`;
  const created = await (await putNamespace(namespace, {
    entities: "eh1:1:PT3S", consumerGroups: "$Default", amqpPort: 0, amqpTlsPort: 0,
  })).json() as Record<string, unknown>;
  // The new namespace's broker is named like the default one's, with its own namespace name.
  const host = HOST.endsWith(NS) ? HOST.slice(0, HOST.length - NS.length) + namespace : HOST;
  const port = Number(created["amqpPort"]);
  const queue = "eh1/ConsumerGroups/$Default/Partitions/0";
  try {
    const conn = await openConn(host, port);
    await send(testId, 1, conn, "eh1");
    await conn.close();

    expect(await readAll(testId, undefined, host, port, queue)).toEqual(["event-0"]);
    expect(await readAll(testId, undefined, host, port, queue)).toEqual(["event-0"]);
    await new Promise((r) => setTimeout(r, 6_000));
    expect(await readAll(testId, undefined, host, port, queue)).toEqual([]);
  } finally {
    await fetch(`${BASE}/${ACCOUNT}-eventhub/namespaces/${namespace}`, { method: "DELETE" });
  }
}, 150_000);

test("a partition of a consumer group takes five receivers and refuses the sixth", async () => {
  if (eventHubMocked) return;
  const conn = await openConn();
  try {
    for (let i = 0; i < 5; i++) {
      expect(await refusal(conn, limitsQueue(PARTITION))).toBeUndefined();
    }
    expect(await refusal(conn, limitsQueue(PARTITION))).toBe("amqp:resource-limit-exceeded");
  } finally {
    await conn.close();
  }
}, 60_000);

test("a receiver with a higher epoch disconnects the current owner", async () => {
  if (eventHubMocked) return;
  const conn = await openConn();
  try {
    const owner = await epochReceiver(conn, limitsQueue("3"), 1);

    expect(await refusal(conn, limitsQueue("3"), 2)).toBeUndefined();
    expect(conditionOf(owner.error)).toBe("amqp:link:stolen");
  } finally {
    await conn.close();
  }
}, 30_000);

test("a receiver without an epoch is refused while the partition has an owner", async () => {
  if (eventHubMocked) return;
  const conn = await openConn();
  try {
    await epochReceiver(conn, limitsQueue("0"), 1);

    expect(await refusal(conn, limitsQueue("0"))).toBe("amqp:link:stolen");
  } finally {
    await conn.close();
  }
}, 30_000);
