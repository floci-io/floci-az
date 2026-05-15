import { CosmosClient } from "@azure/cosmos";
import { COSMOS_ENDPOINT, COSMOS_KEY, randomSuffix } from "./config";

const client = new CosmosClient({ endpoint: COSMOS_ENDPOINT, key: COSMOS_KEY });

function dbName(): string {
  return `testdb-${randomSuffix()}`;
}

// --- Golden path ---

test("database lifecycle: create → list → delete", async () => {
  const id = dbName();

  await client.databases.create({ id });

  const { resources: dbs } = await client.databases.readAll().fetchAll();
  expect(dbs.map((d) => d.id)).toContain(id);

  await client.database(id).delete();

  const { resources: after } = await client.databases.readAll().fetchAll();
  expect(after.map((d) => d.id)).not.toContain(id);
});

test("container lifecycle: create → list → delete", async () => {
  const id = dbName();
  const { database } = await client.databases.create({ id });

  await database.containers.create({
    id: "items",
    partitionKey: { paths: ["/category"] },
  });

  const { resources: containers } = await database.containers.readAll().fetchAll();
  expect(containers.map((c) => c.id)).toContain("items");

  await database.container("items").delete();

  const { resources: after } = await database.containers.readAll().fetchAll();
  expect(after.map((c) => c.id)).not.toContain("items");

  await database.delete();
});

test("document lifecycle: create → read → replace → delete", async () => {
  const id = dbName();
  const { database } = await client.databases.create({ id });
  const { container } = await database.containers.create({
    id: "items",
    partitionKey: { paths: ["/category"] },
  });

  // Create
  const { resource: created } = await container.items.create({
    id: "laptop-1",
    category: "electronics",
    name: "Laptop Pro",
    price: 1299,
  });
  expect(created!.id).toBe("laptop-1");
  expect(created!._etag).toBeDefined();
  expect(created!._ts).toBeDefined();

  // Read
  const { resource: item } = await container.item("laptop-1", "electronics").read();
  expect(item!.name).toBe("Laptop Pro");
  expect(item!.price).toBe(1299);

  // Replace
  const { resource: replaced } = await container.item("laptop-1", "electronics").replace({
    ...item!,
    price: 999,
  });
  expect(replaced!.price).toBe(999);

  // Verify
  const { resource: refreshed } = await container.item("laptop-1", "electronics").read();
  expect(refreshed!.price).toBe(999);

  // Delete
  await container.item("laptop-1", "electronics").delete();

  const { resource: gone } = await container.item("laptop-1", "electronics").read();
  expect(gone).toBeUndefined();

  await database.delete();
});

test("document upsert: create then overwrite", async () => {
  const id = dbName();
  const { database } = await client.databases.create({ id });
  const { container } = await database.containers.create({
    id: "items",
    partitionKey: { paths: ["/category"] },
  });

  await container.items.upsert({ id: "item-1", category: "tools", stock: 10 });
  const { resource: v1 } = await container.item("item-1", "tools").read();
  expect(v1!.stock).toBe(10);

  await container.items.upsert({ id: "item-1", category: "tools", stock: 5 });
  const { resource: v2 } = await container.item("item-1", "tools").read();
  expect(v2!.stock).toBe(5);

  await database.delete();
});

test("document list: create 3 → readAll → count", async () => {
  const id = dbName();
  const { database } = await client.databases.create({ id });
  const { container } = await database.containers.create({
    id: "items",
    partitionKey: { paths: ["/category"] },
  });

  for (let i = 0; i < 3; i++) {
    await container.items.create({ id: `item-${i}`, category: "books", title: `Book ${i}` });
  }

  const { resources: items } = await container.items.readAll().fetchAll();
  expect(items).toHaveLength(3);
  expect(items.map((i) => i.id)).toEqual(expect.arrayContaining(["item-0", "item-1", "item-2"]));

  await database.delete();
});

test("query SELECT * returns all documents", async () => {
  const id = dbName();
  const { database } = await client.databases.create({ id });
  const { container } = await database.containers.create({
    id: "items",
    partitionKey: { paths: ["/category"] },
  });

  await container.items.create({ id: "a", category: "food", name: "Apple" });
  await container.items.create({ id: "b", category: "food", name: "Banana" });

  const { resources } = await container.items
    .query("SELECT * FROM c")
    .fetchAll();
  expect(resources).toHaveLength(2);

  await database.delete();
});

test("query WHERE with named parameter filters documents", async () => {
  const id = dbName();
  const { database } = await client.databases.create({ id });
  const { container } = await database.containers.create({
    id: "items",
    partitionKey: { paths: ["/category"] },
  });

  await container.items.create({ id: "cheap", category: "misc", price: 10 });
  await container.items.create({ id: "expensive", category: "misc", price: 500 });

  const { resources } = await container.items
    .query({
      query: "SELECT * FROM c WHERE c.price > @minPrice",
      parameters: [{ name: "@minPrice", value: 100 }],
    })
    .fetchAll();

  expect(resources).toHaveLength(1);
  expect(resources[0].id).toBe("expensive");

  await database.delete();
});

test("query ORDER BY ASC sorts documents", async () => {
  const id = dbName();
  const { database } = await client.databases.create({ id });
  const { container } = await database.containers.create({
    id: "items",
    partitionKey: { paths: ["/category"] },
  });

  await container.items.create({ id: "c", category: "sort", rank: 3 });
  await container.items.create({ id: "a", category: "sort", rank: 1 });
  await container.items.create({ id: "b", category: "sort", rank: 2 });

  const { resources } = await container.items
    .query("SELECT * FROM c ORDER BY c.rank ASC")
    .fetchAll();

  expect(resources.map((r) => r.rank)).toEqual([1, 2, 3]);

  await database.delete();
});

test("query SELECT VALUE COUNT(1) returns document count", async () => {
  const id = dbName();
  const { database } = await client.databases.create({ id });
  const { container } = await database.containers.create({
    id: "items",
    partitionKey: { paths: ["/category"] },
  });

  for (let i = 0; i < 4; i++) {
    await container.items.create({ id: `item-${i}`, category: "count-test" });
  }

  const { resources } = await container.items
    .query("SELECT VALUE COUNT(1) FROM c")
    .fetchAll();

  expect(resources[0]).toBe(4);

  await database.delete();
});

test("database delete cascades to containers and documents", async () => {
  const id = dbName();
  const { database } = await client.databases.create({ id });
  const { container } = await database.containers.create({
    id: "items",
    partitionKey: { paths: ["/category"] },
  });
  await container.items.create({ id: "orphan", category: "misc" });

  await database.delete();

  const { resources: after } = await client.databases.readAll().fetchAll();
  expect(after.map((d) => d.id)).not.toContain(id);
});

// --- Error cases ---

test("read missing document → 404", async () => {
  const id = dbName();
  const { database } = await client.databases.create({ id });
  const { container } = await database.containers.create({
    id: "items",
    partitionKey: { paths: ["/category"] },
  });

  // @azure/cosmos v4 resolves with { statusCode: 404, resource: undefined }
  // rather than rejecting — check statusCode on the resolved response.
  const response = await container.item("no-such-doc", "misc").read();
  expect(response.statusCode).toBe(404);
  expect(response.resource).toBeUndefined();

  await database.delete();
});

test("read missing database → 404", async () => {
  await expect(
    client.database("no-such-db-xyz").read()
  ).rejects.toMatchObject({ code: 404 });
});
