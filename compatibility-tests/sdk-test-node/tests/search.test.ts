// Azure AI Search compatibility test: index management.
//
// Creates a search service through the ARM management plane, reads its admin key,
// and manages indexes on the service's data plane with SearchIndexClient.

import { AzureKeyCredential, SearchIndex, SearchIndexClient } from "@azure/search-documents";

const BASE = process.env.FLOCI_AZ_ENDPOINT ?? "http://localhost:4577";
const SUB = process.env.FLOCI_AZ_SUBSCRIPTION ?? "00000000-0000-0000-0000-000000000001";
const SERVICE = "sdk-test-search-node";
const ARM_API = "2025-05-01";
const ARM_SERVICE =
  `${BASE}/subscriptions/${SUB}/resourceGroups/sdk-test-rg-search-node` +
  `/providers/Microsoft.Search/searchServices/${SERVICE}`;
const HEADERS = { Authorization: "Bearer fake", "Content-Type": "application/json" };

let client: SearchIndexClient;

function hotels(name: string): SearchIndex {
  return {
    name,
    fields: [
      { name: "hotelId", type: "Edm.String", key: true },
      { name: "hotelName", type: "Edm.String", searchable: true, sortable: true },
      { name: "rating", type: "Edm.Double", searchable: false, filterable: true, facetable: true },
      { name: "tags", type: "Collection(Edm.String)", searchable: true, filterable: true },
      {
        name: "address",
        type: "Edm.ComplexType",
        fields: [
          { name: "street", type: "Edm.String", searchable: true },
          { name: "city", type: "Edm.String", filterable: true },
        ],
      },
      {
        name: "embedding",
        type: "Collection(Edm.Single)",
        searchable: true,
        vectorSearchDimensions: 4,
        vectorSearchProfileName: "vector-profile",
      },
    ],
    corsOptions: { allowedOrigins: ["*"], maxAgeInSeconds: 60 },
    suggesters: [{ name: "sg", searchMode: "analyzingInfixMatching", sourceFields: ["hotelName"] }],
    vectorSearch: {
      profiles: [{ name: "vector-profile", algorithmConfigurationName: "hnsw" }],
      algorithms: [{ name: "hnsw", kind: "hnsw" }],
    },
  };
}

async function statusOf(call: Promise<unknown>): Promise<number | undefined> {
  try {
    await call;
  } catch (error) {
    return (error as { statusCode?: number }).statusCode;
  }
  return undefined;
}

async function indexNames(): Promise<string[]> {
  const names: string[] = [];
  for await (const name of client.listIndexesNames()) names.push(name);
  return names;
}

beforeAll(async () => {
  const created = await fetch(`${ARM_SERVICE}?api-version=${ARM_API}`, {
    method: "PUT",
    headers: HEADERS,
    body: JSON.stringify({ location: "eastus", sku: { name: "basic" } }),
  });
  expect([200, 201]).toContain(created.status);
  const keys = await fetch(`${ARM_SERVICE}/listAdminKeys?api-version=${ARM_API}`, {
    method: "POST",
    headers: HEADERS,
  });
  expect(keys.status).toBe(200);
  const { primaryKey } = (await keys.json()) as { primaryKey: string };

  client = new SearchIndexClient(`${BASE}/${SERVICE}-search`, new AzureKeyCredential(primaryKey), {
    allowInsecureConnection: true,
  });
  for (const name of await indexNames()) await client.deleteIndex(name);
});

afterAll(async () => {
  // A failed delete would leave the service behind for the next run, so it fails the suite.
  const deleted = await fetch(`${ARM_SERVICE}?api-version=${ARM_API}`, { method: "DELETE", headers: HEADERS });
  expect([200, 204]).toContain(deleted.status);
});

test("an index is created and read back with its fields and sections", async () => {
  const created = await client.createIndex(hotels("hotels-create"));
  expect(created.name).toBe("hotels-create");
  expect(created.etag).toBeTruthy();

  const fetched = await client.getIndex("hotels-create");
  const fields = fetched.fields as Array<Record<string, any>>;
  expect(fields.map((field) => field.name)).toEqual([
    "hotelId", "hotelName", "rating", "tags", "address", "embedding",
  ]);
  expect(fields[0].key).toBe(true);
  expect(fields[1].searchable).toBe(true);
  expect(fields[1].sortable).toBe(true);
  expect(fields[2].searchable).toBe(false);
  expect(fields[3].type).toBe("Collection(Edm.String)");
  expect(fields[4].fields.map((sub: { name: string }) => sub.name)).toEqual(["street", "city"]);
  expect(fields[5].vectorSearchDimensions).toBe(4);
  expect(fields[5].vectorSearchProfileName).toBe("vector-profile");
  expect(fetched.corsOptions?.allowedOrigins).toEqual(["*"]);
  expect(fetched.suggesters?.[0].sourceFields).toEqual(["hotelName"]);
  expect(fetched.vectorSearch?.profiles?.[0].algorithmConfigurationName).toBe("hnsw");
  expect(fetched.etag).toBe(created.etag);
});

test("creating a name that is taken is a 409, and a missing index a 404", async () => {
  await client.createIndex(hotels("hotels-twice"));

  expect(await statusOf(client.createIndex(hotels("hotels-twice")))).toBe(409);
  expect(await statusOf(client.getIndex("hotels-missing"))).toBe(404);
});

test("indexes are listed by name", async () => {
  await client.createIndex(hotels("hotels-list-b"));
  await client.createIndex(hotels("hotels-list-a"));

  const names = await indexNames();
  expect(names).toEqual(expect.arrayContaining(["hotels-list-a", "hotels-list-b"]));
  expect(names.indexOf("hotels-list-a")).toBeLessThan(names.indexOf("hotels-list-b"));

  const listed: SearchIndex[] = [];
  for await (const index of client.listIndexes()) listed.push(index);
  expect(listed.find((index) => index.name === "hotels-list-a")?.fields).toHaveLength(6);
});

test("createOrUpdate adds a field, and refuses to change an existing one", async () => {
  const index = await client.createOrUpdateIndex(hotels("hotels-update"));

  index.fields.push({ name: "stars", type: "Edm.Int32", filterable: true });
  const updated = await client.createOrUpdateIndex(index);
  expect(updated.fields).toHaveLength(7);
  expect(updated.etag).not.toBe(index.etag);

  updated.fields[2] = { name: "rating", type: "Edm.Int32" };
  expect(await statusOf(client.createOrUpdateIndex(updated))).toBe(400);
});

test("onlyIfUnchanged guards an update and a delete with the index's ETag", async () => {
  const first = await client.createIndex(hotels("hotels-etag"));
  const second = await client.createOrUpdateIndex(first, { onlyIfUnchanged: true });
  expect(second.etag).not.toBe(first.etag);

  // The first copy is stale now.
  expect(await statusOf(client.createOrUpdateIndex(first, { onlyIfUnchanged: true }))).toBe(412);
  expect(await statusOf(client.deleteIndex(first, { onlyIfUnchanged: true }))).toBe(412);

  await client.deleteIndex(second, { onlyIfUnchanged: true });
  expect(await statusOf(client.getIndex("hotels-etag"))).toBe(404);
});

test("an index without a key field is a 400", async () => {
  const withoutKey: SearchIndex = {
    name: "hotels-no-key",
    fields: [{ name: "hotelId", type: "Edm.String" }],
  };

  expect(await statusOf(client.createIndex(withoutKey))).toBe(400);
});

test("index and service statistics deserialize", async () => {
  await client.createIndex(hotels("hotels-stats"));

  const stats = await client.getIndexStatistics("hotels-stats");
  expect(stats.documentCount).toBe(0);
  expect(stats.storageSize).toBe(0);

  const service = await client.getServiceStatistics();
  expect(service.counters.indexCounter.usage).toBeGreaterThanOrEqual(1);
  // The index quota of the basic SKU the service was created with.
  expect(service.counters.indexCounter.quota).toBe(15);
  expect(service.limits.maxFieldsPerIndex).toBe(1000);
});

test("a deleted index is gone", async () => {
  await client.createIndex(hotels("hotels-delete"));

  await client.deleteIndex("hotels-delete");

  expect(await indexNames()).not.toContain("hotels-delete");
  expect(await statusOf(client.getIndex("hotels-delete"))).toBe(404);
});
