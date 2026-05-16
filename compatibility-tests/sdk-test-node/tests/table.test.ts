import { TableServiceClient, TableClient } from "@azure/data-tables";
import { TABLE_CONN, randomSuffix } from "./config";

const OPTIONS = { allowInsecureConnection: true };

const client = TableServiceClient.fromConnectionString(TABLE_CONN, OPTIONS);

function tableName(): string {
  return `test${randomSuffix()}`;
}

// --- Golden path ---

test("table lifecycle: create → list → entity CRUD → delete", async () => {
  const name = tableName();
  await client.createTable(name);

  const tables: string[] = [];
  for await (const t of client.listTables()) tables.push(t.name!);
  expect(tables).toContain(name);

  const table = TableClient.fromConnectionString(TABLE_CONN, name, OPTIONS);
  await table.createEntity({ partitionKey: "p1", rowKey: "r1", value: "hello" });

  const received = await table.getEntity("p1", "r1");
  expect(received.value).toBe("hello");

  const entities: unknown[] = [];
  for await (const e of table.listEntities()) entities.push(e);
  expect(entities).toHaveLength(1);

  await table.deleteEntity("p1", "r1");
  await client.deleteTable(name);

  const after: string[] = [];
  for await (const t of client.listTables()) after.push(t.name!);
  expect(after).not.toContain(name);
});

test("entity upsert: second upsert updates value", async () => {
  const name = tableName();
  await client.createTable(name);
  const table = TableClient.fromConnectionString(TABLE_CONN, name, OPTIONS);

  await table.createEntity({ partitionKey: "p1", rowKey: "r1", value: "original" });
  await table.upsertEntity({ partitionKey: "p1", rowKey: "r1", value: "updated" });

  const received = await table.getEntity("p1", "r1");
  expect(received.value).toBe("updated");

  await client.deleteTable(name);
});

test("multiple entities: insert 5 → list → count matches", async () => {
  const name = tableName();
  await client.createTable(name);
  const table = TableClient.fromConnectionString(TABLE_CONN, name, OPTIONS);

  for (let i = 0; i < 5; i++) {
    await table.createEntity({ partitionKey: "p1", rowKey: `r${i}`, index: i });
  }

  let count = 0;
  for await (const _ of table.listEntities()) count++;
  expect(count).toBe(5);

  await client.deleteTable(name);
});

// --- Error cases ---

test("get missing entity → 404", async () => {
  const name = tableName();
  await client.createTable(name);
  const table = TableClient.fromConnectionString(TABLE_CONN, name, OPTIONS);

  await expect(table.getEntity("no-pk", "no-rk")).rejects.toMatchObject({ statusCode: 404 });

  await client.deleteTable(name);
});

test("create duplicate table → 409", async () => {
  const name = tableName();
  await client.createTable(name);

  await expect(client.createTable(name)).rejects.toMatchObject({ statusCode: 409 });

  await client.deleteTable(name);
});

// --- Query / filter tests ---

test("filter by partition key: only returns matching entities", async () => {
  const name = tableName();
  await client.createTable(name);
  const table = TableClient.fromConnectionString(TABLE_CONN, name, OPTIONS);

  await table.createEntity({ partitionKey: "p1", rowKey: "r1", value: "alpha" });
  await table.createEntity({ partitionKey: "p1", rowKey: "r2", value: "beta" });
  await table.createEntity({ partitionKey: "p2", rowKey: "r1", value: "gamma" });

  const results: { partitionKey: string }[] = [];
  for await (const e of table.listEntities({ queryOptions: { filter: "PartitionKey eq 'p1'" } })) {
    results.push(e as { partitionKey: string });
  }

  expect(results).toHaveLength(2);
  for (const e of results) {
    expect(e.partitionKey).toBe("p1");
  }

  await client.deleteTable(name);
});

test("filter by numeric field: returns only entities matching gt condition", async () => {
  const name = tableName();
  await client.createTable(name);
  const table = TableClient.fromConnectionString(TABLE_CONN, name, OPTIONS);

  await table.createEntity({ partitionKey: "p1", rowKey: "r1", score: 10 });
  await table.createEntity({ partitionKey: "p1", rowKey: "r2", score: 50 });
  await table.createEntity({ partitionKey: "p1", rowKey: "r3", score: 80 });

  const results: { score: number }[] = [];
  for await (const e of table.listEntities({ queryOptions: { filter: "score gt 20" } })) {
    results.push(e as unknown as { score: number });
  }

  expect(results).toHaveLength(2);
  const scores = results.map((e) => e.score).sort((a, b) => a - b);
  expect(scores).toEqual([50, 80]);

  await client.deleteTable(name);
});

test("select fields: only requested fields are returned", async () => {
  const name = tableName();
  await client.createTable(name);
  const table = TableClient.fromConnectionString(TABLE_CONN, name, OPTIONS);

  await table.createEntity({ partitionKey: "p1", rowKey: "r1", name: "Alice", age: 30, city: "NYC" });

  const results: Record<string, unknown>[] = [];
  for await (const e of table.listEntities({
    queryOptions: { filter: "PartitionKey eq 'p1'", select: ["name", "age"] },
  })) {
    results.push(e as Record<string, unknown>);
  }

  expect(results).toHaveLength(1);
  const result = results[0];
  expect(result.name).toBe("Alice");
  expect(result.age).toBe(30);
  expect(result.city == null).toBe(true);
  expect(result.partitionKey).toBeDefined();

  await client.deleteTable(name);
});

test("pagination: listEntities byPage respects maxPageSize", async () => {
  const name = tableName();
  await client.createTable(name);
  const table = TableClient.fromConnectionString(TABLE_CONN, name, OPTIONS);

  for (let i = 0; i < 10; i++) {
    await table.createEntity({ partitionKey: "p1", rowKey: `r${i.toString().padStart(2, "0")}`, index: i });
  }

  const pager = table.listEntities({ queryOptions: { filter: "PartitionKey eq 'p1'" } }).byPage({ maxPageSize: 3 });

  let totalItems = 0;
  let pageCount = 0;
  for await (const page of pager) {
    totalItems += page.length;
    pageCount++;
  }

  expect(totalItems).toBe(10);
  expect(pageCount).toBeGreaterThanOrEqual(2);

  await client.deleteTable(name);
});

test("etag optimistic concurrency: stale etag update → 412", async () => {
  const name = tableName();
  await client.createTable(name);
  const table = TableClient.fromConnectionString(TABLE_CONN, name, OPTIONS);

  await table.createEntity({ partitionKey: "p1", rowKey: "r1", value: "v1" });

  const entity = await table.getEntity("p1", "r1");
  const oldEtag = entity.etag as string;
  expect(oldEtag).toBeDefined();

  // First update succeeds and invalidates the old etag
  await table.updateEntity({ partitionKey: "p1", rowKey: "r1", value: "v2" }, "Replace", { etag: oldEtag });

  // Second update with the now-stale etag should fail with 412
  await expect(
    table.updateEntity({ partitionKey: "p1", rowKey: "r1", value: "v3" }, "Replace", { etag: oldEtag })
  ).rejects.toMatchObject({ statusCode: 412 });

  await client.deleteTable(name);
});

test("batch transaction: create, upsert, and delete in transactions", async () => {
  const name = tableName();
  await client.createTable(name);
  const table = TableClient.fromConnectionString(TABLE_CONN, name, OPTIONS);

  // First batch: create two entities
  await table.submitTransaction([
    ["create", { partitionKey: "p1", rowKey: "r1", value: "hello" }],
    ["create", { partitionKey: "p1", rowKey: "r2", value: "world" }],
  ]);

  const e1 = await table.getEntity("p1", "r1");
  expect(e1.value).toBe("hello");
  const e2 = await table.getEntity("p1", "r2");
  expect(e2.value).toBe("world");

  // Second batch: upsert r1 and delete r2
  await table.submitTransaction([
    ["upsert", { partitionKey: "p1", rowKey: "r1", value: "updated" }],
    ["delete", { partitionKey: "p1", rowKey: "r2" }],
  ]);

  const e1Updated = await table.getEntity("p1", "r1");
  expect(e1Updated.value).toBe("updated");

  await expect(table.getEntity("p1", "r2")).rejects.toMatchObject({ statusCode: 404 });

  await client.deleteTable(name);
});
