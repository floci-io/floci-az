// Azure AI Search compatibility test: index management, documents and queries.
//
// Creates a search service through the ARM management plane, reads its admin key,
// manages indexes on the service's data plane with SearchIndexClient, and searches them
// with SearchClient; the emulator's Solr sidecar runs the queries.

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

test("an index is created, read back with its fields and sections, listed and deleted", async () => {
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

  await client.createIndex(hotels("hotels-another"));
  const names = await indexNames();
  expect(names).toEqual(expect.arrayContaining(["hotels-another", "hotels-create"]));
  expect(names.indexOf("hotels-another")).toBeLessThan(names.indexOf("hotels-create"));
  const listed: SearchIndex[] = [];
  for await (const index of client.listIndexes()) listed.push(index);
  expect(listed.find((index) => index.name === "hotels-another")?.fields).toHaveLength(6);

  await client.deleteIndex("hotels-another");
  expect(await indexNames()).not.toContain("hotels-another");
  expect(await statusOf(client.getIndex("hotels-another"))).toBe(404);
});

test("creating a name that is taken is a 400", async () => {
  await client.createIndex(hotels("hotels-twice"));

  // Azure answers 400 ResourceNameAlreadyInUse here, not 409.
  expect(await statusOf(client.createIndex(hotels("hotels-twice")))).toBe(400);
});

test("createOrUpdate adds a field under onlyIfUnchanged, refuses to change one, and the ETag guards a delete", async () => {
  const first = await client.createIndex(hotels("hotels-etag"));
  // An update that changes nothing keeps the ETag, as on Azure, so this one adds a field.
  first.fields.push({ name: "stars", type: "Edm.Int32", filterable: true });
  const second = await client.createOrUpdateIndex(first, { onlyIfUnchanged: true });
  expect(second.fields).toHaveLength(7);
  expect(second.etag).not.toBe(first.etag);

  // The first copy is stale now.
  expect(await statusOf(client.createOrUpdateIndex(first, { onlyIfUnchanged: true }))).toBe(412);
  expect(await statusOf(client.deleteIndex(first, { onlyIfUnchanged: true }))).toBe(412);

  // An existing field cannot change its type.
  const retyped = { ...second, fields: [...second.fields] };
  retyped.fields[2] = { name: "rating", type: "Edm.Int32" };
  expect(await statusOf(client.createOrUpdateIndex(retyped))).toBe(400);

  await client.deleteIndex(second, { onlyIfUnchanged: true });
  expect(await statusOf(client.getIndex("hotels-etag"))).toBe(404);
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

interface Hotel {
  hotelId: string;
  hotelName?: string | null;
  rating?: number | null;
  tags?: string[];
  address?: { street?: string | null; city?: string | null } | null;
  embedding?: number[];
  opened?: string | Date | null;
  location?: { type: string; coordinates: number[] } | null;
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

describe("queries", () => {
  // One index for every query test, loaded once.
  beforeAll(async () => {
    const index = hotels("hotels-query");
    index.fields.push(
      { name: "opened", type: "Edm.DateTimeOffset", filterable: true, sortable: true, facetable: true },
      { name: "location", type: "Edm.GeographyPoint", filterable: true, sortable: true },
    );
    await client.createIndex(index);
    const docs = client.getSearchClient<Hotel>("hotels-query");
    const paged: Hotel[] = Array.from({ length: 60 }, (_, i) => ({ hotelId: `p${i}`, hotelName: "Paged inn" }));
    await docs.uploadDocuments([
      { hotelId: "1", hotelName: "Fancy seaside hotel", rating: 4.5, tags: ["pool", "wifi"],
        address: { street: "Harbour road", city: "Helsinki" }, opened: "2020-05-01T00:00:00Z",
        location: { type: "Point", coordinates: [24.94, 60.17] } },
      { hotelId: "2", hotelName: "Plain hotel", rating: 2.0, tags: ["wifi"], opened: "2021-06-01T00:00:00Z" },
      { hotelId: "3", hotelName: "Fancy resort", rating: 5.0, tags: ["spa"], opened: "2022-07-01T00:00:00Z",
        location: { type: "Point", coordinates: [2.35, 48.85] } },
      ...paged,
    ]);
    // The first query waits for the Solr sidecar, which a cold Docker may still be pulling.
    for await (const result of (await docs.search("*", { top: 1 })).results) expect(result).toBeTruthy();
  }, 180_000);

  async function ids(results: AsyncIterable<{ document: Hotel }>): Promise<string[]> {
    const found: string[] = [];
    for await (const result of results) found.push(result.document.hotelId);
    return found;
  }

  test("a search ranks, counts, selects, highlights and pages", async () => {
    const docs = client.getSearchClient<Hotel>("hotels-query");
    const answer = await docs.search("fancy", { includeTotalCount: true, select: ["hotelId", "hotelName"] });
    const hits: Array<{ score: number; document: Record<string, unknown> }> = [];
    for await (const result of answer.results) hits.push(result);
    expect(answer.count).toBe(2);
    expect(hits.map((hit) => hit.document.hotelId).sort()).toEqual(["1", "3"]);
    expect(hits.every((hit) => hit.score > 0)).toBe(true);
    expect(Object.keys(hits[0].document)).not.toContain("rating");
    const street = await docs.search("harbour", { searchFields: ["address/street"] });
    expect(await ids(street.results)).toEqual(["1"]);

    expect(await ids((await docs.search("fancy hotel", { searchMode: "all" })).results)).toEqual(["1"]);
    const fuzzy = await docs.search("hotelName:fanci~1", { queryType: "full" });
    expect((await ids(fuzzy.results)).sort()).toEqual(["1", "3"]);
    const highlighted = await docs.search("seaside", { highlightFields: "hotelName" });
    for await (const result of highlighted.results) {
      expect(result.highlights?.hotelName).toEqual(["Fancy <em>seaside</em> hotel"]);
    }

    // Sixty results take two pages: the SDK follows the next page parameters.
    const paged = await docs.search("paged", { includeTotalCount: true });
    expect(paged.count).toBe(60);
    expect(await ids(paged.results)).toHaveLength(60);
  });

  test("a search filters, sorts and facets", async () => {
    const docs = client.getSearchClient<Hotel>("hotels-query");
    const pool = await docs.search("*", { filter: "rating ge 4 and tags/any(t: t eq 'pool')" });
    expect(await ids(pool.results)).toEqual(["1"]);
    const ordered = await docs.search("*", { filter: "opened ne null", orderBy: ["opened desc"] });
    expect(await ids(ordered.results)).toEqual(["3", "2", "1"]);
    const near = await docs.search("*", { filter: "geo.distance(location, geography'POINT(24.9 60.2)') lt 10" });
    expect(await ids(near.results)).toEqual(["1"]);

    const faceted = await docs.search("*", { facets: ["rating,values:3", "opened,interval:year"], top: 0 });
    expect(faceted.facets?.rating.map((b) => [b.from, b.to, b.count])).toEqual([[undefined, 3, 1], [3, undefined, 2]]);
    expect(faceted.facets?.opened.map((b) => b.count)).toEqual([1, 1, 1]);
  });
});
