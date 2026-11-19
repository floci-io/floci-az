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

test("creating a name that is taken is a 400, and a missing index a 404", async () => {
  await client.createIndex(hotels("hotels-twice"));

  // Azure answers 400 ResourceNameAlreadyInUse here, not 409.
  expect(await statusOf(client.createIndex(hotels("hotels-twice")))).toBe(400);
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
  // An update that changes nothing keeps the ETag, as on Azure, so this one adds a field.
  first.fields.push({ name: "stars", type: "Edm.Int32", filterable: true });
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

interface Hotel {
  hotelId: string;
  hotelName?: string | null;
  rating?: number | null;
  tags?: string[];
  address?: { street?: string | null; city?: string | null } | null;
  embedding?: number[];
}

test("documents are uploaded, read back, selected and counted", async () => {
  await client.createIndex(hotels("hotels-docs"));
  const docs = client.getSearchClient<Hotel>("hotels-docs");

  const uploaded = await docs.uploadDocuments([
    { hotelId: "1", hotelName: "Fancy", rating: 4.5, tags: ["pool"], address: { street: "Main", city: "Helsinki" },
      embedding: [0.1, 0.2, 0.3, 0.4] },
    { hotelId: "2", hotelName: "Plain", rating: 2.0 },
  ]);
  expect(uploaded.results.map((r) => [r.key, r.succeeded, r.statusCode])).toEqual([["1", true, 201], ["2", true, 201]]);

  const fetched = await docs.getDocument("1");
  expect(fetched.hotelName).toBe("Fancy");
  expect(fetched.address).toEqual({ street: "Main", city: "Helsinki" });
  // A vector field is not retrievable unless the index says so.
  expect(fetched.embedding).toBeUndefined();
  expect((await docs.getDocument("2")).tags).toEqual([]);
  const selected = await docs.getDocument("1", { selectedFields: ["hotelName"] });
  expect(selected.hotelName).toBe("Fancy");
  expect(Object.keys(selected)).not.toContain("rating");
  expect(await docs.getDocumentsCount()).toBe(2);
  expect(await statusOf(docs.getDocument("missing"))).toBe(404);
});

test("a merge keeps what it does not send, and a missing key fails alone with 207", async () => {
  await client.createIndex(hotels("hotels-merge"));
  const docs = client.getSearchClient<Hotel>("hotels-merge");
  await docs.uploadDocuments([{ hotelId: "1", hotelName: "Fancy", rating: 4.5, address: { street: "Main", city: "Helsinki" } }]);

  await docs.mergeDocuments([{ hotelId: "1", rating: 5.0, address: { city: "Turku" } }]);
  const fetched = await docs.getDocument("1");
  expect(fetched.hotelName).toBe("Fancy");
  expect(fetched.address).toEqual({ street: "Main", city: "Turku" });

  const upserted = await docs.mergeOrUploadDocuments([{ hotelId: "1", hotelName: "Fancier" }, { hotelId: "3", hotelName: "New" }]);
  expect(upserted.results.map((r) => r.statusCode)).toEqual([200, 201]);

  const mixed = await docs.mergeDocuments([{ hotelId: "3", rating: 1.0 }, { hotelId: "missing", rating: 1.0 }]);
  expect(mixed.results.map((r) => [r.key, r.succeeded, r.statusCode])).toEqual([["3", true, 200], ["missing", false, 404]]);
  expect(mixed.results[1].errorMessage).toBe("Document not found.");

  const deleted = await docs.deleteDocuments("hotelId", ["1", "never"]);
  expect(deleted.results.map((r) => r.statusCode)).toEqual([200, 200]);
  expect(await docs.getDocumentsCount()).toBe(1);
});

test("a malformed document refuses the whole batch", async () => {
  await client.createIndex(hotels("hotels-bad"));
  const docs = client.getSearchClient<Record<string, unknown>>("hotels-bad");

  expect(await statusOf(docs.uploadDocuments([{ hotelId: "1" }, { hotelId: "2", rating: "not-a-number" }]))).toBe(400);
  expect(await docs.getDocumentsCount()).toBe(0);
});
