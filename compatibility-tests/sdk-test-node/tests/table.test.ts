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
