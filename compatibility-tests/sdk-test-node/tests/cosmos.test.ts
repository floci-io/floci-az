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

test("aggregate SUM/AVG/MIN/MAX return correct scalar values", async () => {
  const id = dbName();
  const { database } = await client.databases.create({ id });
  const { container } = await database.containers.create({
    id: "items",
    partitionKey: { paths: ["/category"] },
  });

  const prices = [10, 20, 30, 40]; // sum=100, avg=25, min=10, max=40
  for (let i = 0; i < prices.length; i++) {
    await container.items.create({ id: `item-${i}`, category: "agg", price: prices[i] });
  }

  const scalar = async (sql: string) => {
    const { resources } = await container.items.query(sql).fetchAll();
    return resources[0];
  };

  expect(await scalar("SELECT VALUE SUM(c.price) FROM c")).toBe(100);
  expect(await scalar("SELECT VALUE AVG(c.price) FROM c")).toBe(25);
  expect(await scalar("SELECT VALUE MIN(c.price) FROM c")).toBe(10);
  expect(await scalar("SELECT VALUE MAX(c.price) FROM c")).toBe(40);

  await database.delete();
});

test("SELECT DISTINCT returns unique documents", async () => {
  const id = dbName();
  const { database } = await client.databases.create({ id });
  const { container } = await database.containers.create({
    id: "items",
    partitionKey: { paths: ["/category"] },
  });

  for (let i = 0; i < 3; i++) await container.items.create({ id: `food-${i}`, category: "food" });
  for (let i = 0; i < 2; i++) await container.items.create({ id: `book-${i}`, category: "books" });

  const { resources } = await container.items
    .query("SELECT DISTINCT c.category FROM c")
    .fetchAll();

  const categories = resources.map((r: any) => r.category).sort();
  expect(categories).toEqual(["books", "food"]);

  await database.delete();
});

test("GROUP BY with COUNT(1) groups and aggregates correctly", async () => {
  const id = dbName();
  const { database } = await client.databases.create({ id });
  const { container } = await database.containers.create({
    id: "items",
    partitionKey: { paths: ["/category"] },
  });

  for (let i = 0; i < 3; i++) await container.items.create({ id: `food-${i}`, category: "food" });
  for (let i = 0; i < 2; i++) await container.items.create({ id: `book-${i}`, category: "books" });

  const { resources } = await container.items
    .query("SELECT c.category, COUNT(1) as count FROM c GROUP BY c.category")
    .fetchAll();

  const counts: Record<string, number> = {};
  resources.forEach((r: any) => { counts[r.category] = r.count; });
  expect(counts).toEqual({ food: 3, books: 2 });

  await database.delete();
});

test("string functions LOWER/UPPER/LENGTH/CONCAT in WHERE and SELECT", async () => {
  const id = dbName();
  const { database } = await client.databases.create({ id });
  const { container } = await database.containers.create({
    id: "items",
    partitionKey: { paths: ["/category"] },
  });

  await container.items.create({ id: "item-1", category: "Electronics", name: "Laptop Pro", first: "John", last: "Doe" });
  await container.items.create({ id: "item-2", category: "books",       name: "Guide",      first: "Jane", last: "Smith" });

  // WHERE LOWER
  const { resources: r1 } = await container.items
    .query("SELECT * FROM c WHERE LOWER(c.category) = 'electronics'").fetchAll();
  expect(r1).toHaveLength(1);
  expect(r1[0].id).toBe("item-1");

  // WHERE UPPER
  const { resources: r2 } = await container.items
    .query("SELECT * FROM c WHERE UPPER(c.category) = 'BOOKS'").fetchAll();
  expect(r2).toHaveLength(1);
  expect(r2[0].id).toBe("item-2");

  // WHERE LENGTH  ("Laptop Pro"=10 > 5; "Guide"=5 is NOT > 5)
  const { resources: r3 } = await container.items
    .query("SELECT * FROM c WHERE LENGTH(c.name) > 5").fetchAll();
  expect(r3).toHaveLength(1);
  expect(r3[0].id).toBe("item-1");

  // SELECT LOWER + LENGTH
  const { resources: r4 } = await container.items
    .query("SELECT LOWER(c.category) AS cat, LENGTH(c.name) AS nlen FROM c WHERE c.id = 'item-1'").fetchAll();
  expect(r4[0].cat).toBe("electronics");
  expect(r4[0].nlen).toBe(10);

  // SELECT CONCAT
  const { resources: r5 } = await container.items
    .query("SELECT CONCAT(c.first, ' ', c.last) AS full_name FROM c WHERE c.id = 'item-1'").fetchAll();
  expect(r5[0].full_name).toBe("John Doe");

  await database.delete();
});

test("PATCH applies partial updates (add, set, replace, remove, incr)", async () => {
  const id = dbName();
  const { database } = await client.databases.create({ id });
  const { container } = await database.containers.create({
    id: "items",
    partitionKey: { paths: ["/category"] },
  });

  await container.items.create({
    id: "patch-1", category: "misc",
    name: "Original", counter: 10, status: "draft", removable: true,
  });

  const { resource } = await container.item("patch-1", "misc").patch([
    { op: "add",     path: "/newField", value: "added"  },
    { op: "set",     path: "/name",     value: "Patched" },
    { op: "replace", path: "/status",   value: "active"  },
    { op: "remove",  path: "/removable"                  },
    { op: "incr",    path: "/counter",  value: 5         },
  ]);

  expect(resource!.newField).toBe("added");
  expect(resource!.name).toBe("Patched");
  expect(resource!.status).toBe("active");
  expect(resource!.removable).toBeUndefined();
  expect(resource!.counter).toBe(15);

  await database.delete();
});

test("pagination: x-ms-max-item-count splits results into pages", async () => {
  const id = dbName();
  const { database } = await client.databases.create({ id });
  const { container } = await database.containers.create({
    id: "items",
    partitionKey: { paths: ["/category"] },
  });

  const total = 10;
  for (let i = 0; i < total; i++) {
    await container.items.create({ id: `item-${String(i).padStart(2, "0")}`, category: "page-test", rank: i });
  }

  const PAGE = 3;
  const allIds: string[] = [];
  let pageCount = 0;
  const iterator = container.items.query("SELECT * FROM c", { maxItemCount: PAGE });

  while (iterator.hasMoreResults()) {
    const page = await iterator.fetchNext();
    expect(page.resources.length).toBeLessThanOrEqual(PAGE);
    allIds.push(...page.resources.map((r: any) => r.id));
    pageCount++;
  }

  expect(pageCount).toBeGreaterThanOrEqual(2);
  expect(allIds).toHaveLength(total);
  expect(new Set(allIds).size).toBe(total);

  await database.delete();
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
