using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Azure;
using Azure.Core.Pipeline;
using Azure.Search.Documents;
using Azure.Search.Documents.Indexes;
using Azure.Search.Documents.Indexes.Models;
using Azure.Search.Documents.Models;

namespace FlociAz.Compatibility;

/// <summary>
/// Index management, documents and queries on the Azure AI Search data plane, through SearchIndexClient and
/// SearchClient. Each test creates its own search service through ARM and signs in with that service's admin key.
/// </summary>
public sealed class SearchCompatibilityTests
{
    private static readonly Uri EmulatorEndpoint = new(
        Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577");

    private const string Subscription = "00000000-0000-0000-0000-000000000001";
    private const string ArmApi = "?api-version=2025-05-01";

    [Test]
    [Timeout(60_000)]
    public async Task CreatesReadsAndListsAnIndex(CancellationToken cancellationToken)
    {
        await using var service = await SearchService.CreateAsync(cancellationToken);
        var client = service.Client;

        SearchIndex created = await client.CreateIndexAsync(Hotels("hotels-create"), cancellationToken);
        await Assert.That(created.Name).IsEqualTo("hotels-create");
        await Assert.That(created.ETag.HasValue).IsTrue();

        SearchIndex fetched = await client.GetIndexAsync("hotels-create", cancellationToken);
        await Assert.That(fetched.Fields.Select(field => field.Name).ToList())
            .IsEquivalentTo(["hotelId", "hotelName", "rating", "tags", "address", "embedding"]);
        await Assert.That(fetched.Fields[0].IsKey).IsTrue();
        await Assert.That(fetched.Fields[1].IsSearchable).IsTrue();
        await Assert.That(fetched.Fields[1].IsSortable).IsTrue();
        await Assert.That(fetched.Fields[2].IsSearchable).IsFalse();
        await Assert.That(fetched.Fields[3].Type.ToString()).IsEqualTo("Collection(Edm.String)");
        await Assert.That(fetched.Fields[4].Fields.Select(field => field.Name).ToList())
            .IsEquivalentTo(["street", "city"]);
        await Assert.That(fetched.Fields[5].VectorSearchDimensions).IsEqualTo(4);
        await Assert.That(fetched.Fields[5].VectorSearchProfileName).IsEqualTo("vector-profile");
        await Assert.That(fetched.CorsOptions.AllowedOrigins.ToList()).IsEquivalentTo(["*"]);
        await Assert.That(fetched.Suggesters[0].SourceFields.ToList()).IsEquivalentTo(["hotelName"]);
        await Assert.That(fetched.VectorSearch.Profiles[0].AlgorithmConfigurationName).IsEqualTo("hnsw");
        await Assert.That(fetched.ETag).IsEqualTo(created.ETag);

        await client.CreateIndexAsync(Hotels("hotels-another"), cancellationToken);
        var names = new List<string>();
        await foreach (var name in client.GetIndexNamesAsync(cancellationToken))
        {
            names.Add(name);
        }
        await Assert.That(names).IsEquivalentTo(["hotels-another", "hotels-create"]);

        var listed = new List<SearchIndex>();
        await foreach (var index in client.GetIndexesAsync(cancellationToken))
        {
            listed.Add(index);
        }
        await Assert.That(listed.Count).IsEqualTo(2);
        await Assert.That(listed[0].Fields.Count).IsEqualTo(6);
    }

    [Test]
    [Timeout(60_000)]
    public async Task AnswersBadRequestForATakenNameAndAnIndexWithoutAKey(CancellationToken cancellationToken)
    {
        await using var service = await SearchService.CreateAsync(cancellationToken);
        var client = service.Client;
        await client.CreateIndexAsync(Hotels("hotels-twice"), cancellationToken);

        // Azure answers 400 ResourceNameAlreadyInUse here, not 409.
        await Assert.That(await StatusOf(() => client.CreateIndexAsync(Hotels("hotels-twice"), cancellationToken)))
            .IsEqualTo(400);

        var withoutKey = new SearchIndex("hotels-no-key")
        {
            Fields = { new SimpleField("hotelId", SearchFieldDataType.String) }
        };
        await Assert.That(await StatusOf(() => client.CreateIndexAsync(withoutKey, cancellationToken)))
            .IsEqualTo(400);
    }

    [Test]
    [Timeout(60_000)]
    public async Task UpdatesAnIndexUnderItsETag(CancellationToken cancellationToken)
    {
        await using var service = await SearchService.CreateAsync(cancellationToken);
        var client = service.Client;

        SearchIndex first = await client.CreateIndexAsync(Hotels("hotels-etag"), cancellationToken);
        first.Fields.Add(new SimpleField("stars", SearchFieldDataType.Int32) { IsFilterable = true });
        SearchIndex second = await client.CreateOrUpdateIndexAsync(
            first, allowIndexDowntime: false, onlyIfUnchanged: true, cancellationToken);
        await Assert.That(second.Fields.Count).IsEqualTo(7);
        await Assert.That(second.ETag).IsNotEqualTo(first.ETag);

        // The first copy is stale now.
        await Assert.That(await StatusOf(() => client.CreateOrUpdateIndexAsync(
            first, allowIndexDowntime: false, onlyIfUnchanged: true, cancellationToken))).IsEqualTo(412);
        await Assert.That(await StatusOf(() => client.DeleteIndexAsync(
            first, onlyIfUnchanged: true, cancellationToken))).IsEqualTo(412);

        // An existing field cannot change its type.
        second.Fields[2] = new SimpleField("rating", SearchFieldDataType.Int32);
        await Assert.That(await StatusOf(() => client.CreateOrUpdateIndexAsync(
            second, allowIndexDowntime: false, onlyIfUnchanged: false, cancellationToken))).IsEqualTo(400);

        SearchIndex current = await client.GetIndexAsync("hotels-etag", cancellationToken);
        await client.DeleteIndexAsync(current, onlyIfUnchanged: true, cancellationToken);
        await Assert.That(await StatusOf(() => client.GetIndexAsync("hotels-etag", cancellationToken)))
            .IsEqualTo(404);
    }

    [Test]
    [Timeout(60_000)]
    public async Task ReadsIndexAndServiceStatistics(CancellationToken cancellationToken)
    {
        await using var service = await SearchService.CreateAsync(cancellationToken);
        var client = service.Client;
        await client.CreateIndexAsync(Hotels("hotels-stats"), cancellationToken);

        SearchIndexStatistics stats = await client.GetIndexStatisticsAsync("hotels-stats", cancellationToken);
        await Assert.That(stats.DocumentCount).IsEqualTo(0);
        await Assert.That(stats.StorageSize).IsEqualTo(0);

        SearchServiceStatistics statistics = await client.GetServiceStatisticsAsync(cancellationToken);
        await Assert.That(statistics.Counters.IndexCounter.Usage).IsEqualTo(1);
        // The index quota of the basic SKU the service was created with.
        await Assert.That(statistics.Counters.IndexCounter.Quota).IsEqualTo(15);
        await Assert.That(statistics.Limits.MaxFieldsPerIndex).IsEqualTo(1000);
    }

    [Test]
    [Timeout(60_000)]
    public async Task UploadsReadsAndCountsDocuments(CancellationToken cancellationToken)
    {
        await using var service = await SearchService.CreateAsync(cancellationToken);
        await service.Client.CreateIndexAsync(Hotels("hotels-docs"), cancellationToken);
        SearchClient docs = service.Client.GetSearchClient("hotels-docs");

        Response<IndexDocumentsResult> uploaded = await docs.UploadDocumentsAsync(new[]
        {
            new SearchDocument
            {
                ["hotelId"] = "1", ["hotelName"] = "Fancy", ["rating"] = 4.5,
                ["address"] = new SearchDocument { ["street"] = "Main", ["city"] = "Helsinki" },
                ["embedding"] = new[] { 0.1f, 0.2f, 0.3f, 0.4f }
            },
            new SearchDocument { ["hotelId"] = "2", ["hotelName"] = "Plain", ["rating"] = double.NaN }
        }, cancellationToken: cancellationToken);
        await Assert.That(uploaded.Value.Results.Select(r => r.Status).ToArray()).IsEquivalentTo(new[] { 201, 201 });

        SearchDocument fetched = await docs.GetDocumentAsync<SearchDocument>("1", cancellationToken: cancellationToken);
        await Assert.That(fetched.GetString("hotelName")).IsEqualTo("Fancy");
        await Assert.That(((SearchDocument)fetched["address"]).GetString("city")).IsEqualTo("Helsinki");
        // A vector field is not retrievable unless the index says so.
        await Assert.That(fetched.ContainsKey("embedding")).IsFalse();
        // Azure keeps NaN as the string "NaN", which the SDK reads back as a double.
        SearchDocument plain = await docs.GetDocumentAsync<SearchDocument>("2", cancellationToken: cancellationToken);
        await Assert.That(double.IsNaN(plain.GetDouble("rating")!.Value)).IsTrue();

        var select = new GetDocumentOptions { SelectedFields = { "hotelName" } };
        SearchDocument selected = await docs.GetDocumentAsync<SearchDocument>("1", select, cancellationToken);
        await Assert.That(selected.ContainsKey("rating")).IsFalse();
        await Assert.That((long)await docs.GetDocumentCountAsync(cancellationToken)).IsEqualTo(2L);
        await Assert.That(await StatusOf(() => docs.GetDocumentAsync<SearchDocument>(
            "missing", cancellationToken: cancellationToken))).IsEqualTo(404);
    }

    [Test]
    [Timeout(60_000)]
    public async Task MergesAndDeletesDocumentsAndAnswersAMissingKeyWithMultiStatus(
        CancellationToken cancellationToken)
    {
        await using var service = await SearchService.CreateAsync(cancellationToken);
        await service.Client.CreateIndexAsync(Hotels("hotels-merge"), cancellationToken);
        SearchClient docs = service.Client.GetSearchClient("hotels-merge");
        await docs.UploadDocumentsAsync(new[]
        {
            new SearchDocument
            {
                ["hotelId"] = "1", ["hotelName"] = "Fancy",
                ["address"] = new SearchDocument { ["street"] = "Main", ["city"] = "Helsinki" }
            }
        }, cancellationToken: cancellationToken);

        await docs.MergeDocumentsAsync(new[]
        {
            new SearchDocument { ["hotelId"] = "1", ["address"] = new SearchDocument { ["city"] = "Turku" } }
        }, cancellationToken: cancellationToken);
        SearchDocument fetched = await docs.GetDocumentAsync<SearchDocument>("1", cancellationToken: cancellationToken);
        await Assert.That(fetched.GetString("hotelName")).IsEqualTo("Fancy");
        await Assert.That(((SearchDocument)fetched["address"]).GetString("street")).IsEqualTo("Main");
        await Assert.That(((SearchDocument)fetched["address"]).GetString("city")).IsEqualTo("Turku");

        var batch = IndexDocumentsBatch.Create(
            IndexDocumentsAction.MergeOrUpload(new SearchDocument { ["hotelId"] = "3", ["hotelName"] = "New" }),
            IndexDocumentsAction.Merge(new SearchDocument { ["hotelId"] = "missing", ["hotelName"] = "Never" }));
        Response<IndexDocumentsResult> mixed = await docs.IndexDocumentsAsync(batch,
            new IndexDocumentsOptions { ThrowOnAnyError = false }, cancellationToken);
        await Assert.That(mixed.GetRawResponse().Status).IsEqualTo(207);
        await Assert.That(mixed.Value.Results.Select(r => r.Status).ToArray()).IsEquivalentTo(new[] { 201, 404 });
        await Assert.That(mixed.Value.Results[1].ErrorMessage).IsEqualTo("Document not found.");

        await docs.DeleteDocumentsAsync("hotelId", new[] { "1", "never" }, cancellationToken: cancellationToken);
        await Assert.That((long)await docs.GetDocumentCountAsync(cancellationToken)).IsEqualTo(1L);
    }

    [Test]
    [Timeout(180_000)]
    public async Task SearchesRankCountSelectHighlightAndPage(CancellationToken cancellationToken)
    {
        await using var service = await SearchService.CreateAsync(cancellationToken);
        SearchIndex index = Hotels("hotels-query");
        index.Fields.Add(new SimpleField("opened", SearchFieldDataType.DateTimeOffset)
        {
            IsFilterable = true, IsSortable = true, IsFacetable = true
        });
        index.Fields.Add(new SimpleField("location", SearchFieldDataType.GeographyPoint)
        {
            IsFilterable = true, IsSortable = true
        });
        await service.Client.CreateIndexAsync(index, cancellationToken);
        SearchClient docs = service.Client.GetSearchClient("hotels-query");
        var documents = new List<SearchDocument>
        {
            new()
            {
                ["hotelId"] = "1", ["hotelName"] = "Fancy seaside hotel", ["rating"] = 4.5,
                ["tags"] = new[] { "pool", "wifi" }, ["opened"] = DateTimeOffset.Parse("2020-05-01T00:00:00Z"),
                ["location"] = new Dictionary<string, object> { ["type"] = "Point", ["coordinates"] = new[] { 24.94, 60.17 } }
            },
            new()
            {
                ["hotelId"] = "2", ["hotelName"] = "Plain hotel", ["rating"] = 2.0, ["tags"] = new[] { "wifi" },
                ["opened"] = DateTimeOffset.Parse("2021-06-01T00:00:00Z")
            },
            new()
            {
                ["hotelId"] = "3", ["hotelName"] = "Fancy resort", ["rating"] = 5.0, ["tags"] = new[] { "spa" },
                ["opened"] = DateTimeOffset.Parse("2022-07-01T00:00:00Z"),
                ["location"] = new Dictionary<string, object> { ["type"] = "Point", ["coordinates"] = new[] { 2.35, 48.85 } }
            }
        };
        documents.AddRange(Enumerable.Range(0, 60)
            .Select(i => new SearchDocument { ["hotelId"] = $"p{i}", ["hotelName"] = "Paged inn" }));
        await docs.UploadDocumentsAsync(documents, cancellationToken: cancellationToken);

        // The first query waits for the Solr sidecar, which the service create started.
        var options = new SearchOptions { IncludeTotalCount = true, Select = { "hotelId", "hotelName" } };
        SearchResults<SearchDocument> fancy = await docs.SearchAsync<SearchDocument>("fancy", options,
            cancellationToken);
        var hits = new List<SearchResult<SearchDocument>>();
        await foreach (var hit in fancy.GetResultsAsync())
        {
            hits.Add(hit);
        }
        await Assert.That(fancy.TotalCount).IsEqualTo(2L);
        await Assert.That(hits.Select(hit => hit.Document.GetString("hotelId")).ToList()).IsEquivalentTo(["1", "3"]);
        await Assert.That(hits.All(hit => hit.Score > 0)).IsTrue();
        await Assert.That(hits[0].Document.ContainsKey("rating")).IsFalse();

        var all = new SearchOptions { SearchMode = SearchMode.All };
        await Assert.That(await Ids(docs, "fancy hotel", all, cancellationToken)).IsEquivalentTo(["1"]);
        var full = new SearchOptions { QueryType = SearchQueryType.Full };
        await Assert.That(await Ids(docs, "hotelName:fanci~1", full, cancellationToken))
            .IsEquivalentTo(["1", "3"]);

        var highlight = new SearchOptions { HighlightFields = { "hotelName" } };
        SearchResults<SearchDocument> marked = await docs.SearchAsync<SearchDocument>("seaside", highlight,
            cancellationToken);
        await foreach (var hit in marked.GetResultsAsync())
        {
            await Assert.That(hit.Highlights["hotelName"].ToList()).IsEquivalentTo(["Fancy <em>seaside</em> hotel"]);
        }

        // Sixty results take two pages: the SDK follows the next page parameters.
        var paged = new SearchOptions { IncludeTotalCount = true };
        await Assert.That((await Ids(docs, "paged", paged, cancellationToken)).Count).IsEqualTo(60);

        var pool = new SearchOptions { Filter = "rating ge 4 and tags/any(t: t eq 'pool')" };
        await Assert.That(await Ids(docs, "*", pool, cancellationToken)).IsEquivalentTo(["1"]);
        var ordered = new SearchOptions { Filter = "opened ne null", OrderBy = { "opened desc" } };
        await Assert.That(string.Join(",", await Ids(docs, "*", ordered, cancellationToken))).IsEqualTo("3,2,1");
        var near = new SearchOptions { Filter = "geo.distance(location, geography'POINT(24.9 60.2)') lt 10" };
        await Assert.That(await Ids(docs, "*", near, cancellationToken)).IsEquivalentTo(["1"]);
        var faceting = new SearchOptions { Size = 0, Facets = { "rating,values:3", "opened,interval:year" } };
        SearchResults<SearchDocument> faceted = await docs.SearchAsync<SearchDocument>("*", faceting,
            cancellationToken);
        await Assert.That(string.Join(",", faceted.Facets["rating"].Select(bucket => bucket.Count))).IsEqualTo("1,2");
        await Assert.That(faceted.Facets["opened"].Count).IsEqualTo(3);
    }

    private static async Task<List<string>> Ids(SearchClient docs, string text, SearchOptions options,
        CancellationToken cancellationToken)
    {
        SearchResults<SearchDocument> results = await docs.SearchAsync<SearchDocument>(text, options,
            cancellationToken);
        var ids = new List<string>();
        await foreach (var hit in results.GetResultsAsync())
        {
            ids.Add(hit.Document.GetString("hotelId"));
        }
        return ids;
    }

    private static SearchIndex Hotels(string name) => new(name)
    {
        Fields =
        {
            new SimpleField("hotelId", SearchFieldDataType.String) { IsKey = true },
            new SearchableField("hotelName") { IsSortable = true },
            new SimpleField("rating", SearchFieldDataType.Double) { IsFilterable = true, IsFacetable = true },
            new SearchableField("tags", collection: true) { IsFilterable = true },
            new ComplexField("address")
            {
                Fields =
                {
                    new SearchableField("street"),
                    new SimpleField("city", SearchFieldDataType.String) { IsFilterable = true }
                }
            },
            new SearchField("embedding", SearchFieldDataType.Collection(SearchFieldDataType.Single))
            {
                IsSearchable = true,
                VectorSearchDimensions = 4,
                VectorSearchProfileName = "vector-profile"
            }
        },
        CorsOptions = new CorsOptions(["*"]) { MaxAgeInSeconds = 60 },
        Suggesters = { new SearchSuggester("sg", "hotelName") },
        VectorSearch = new VectorSearch
        {
            Profiles = { new VectorSearchProfile("vector-profile", "hnsw") },
            Algorithms = { new HnswAlgorithmConfiguration("hnsw") }
        }
    };

    private static async Task<int> StatusOf(Func<Task> call)
    {
        try
        {
            await call();
        }
        catch (RequestFailedException refused)
        {
            return refused.Status;
        }
        return 0;
    }

    private sealed class EmulatorHandler() : DelegatingHandler(new HttpClientHandler())
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            request.RequestUri = new UriBuilder(request.RequestUri!)
            {
                Scheme = EmulatorEndpoint.Scheme,
                Port = EmulatorEndpoint.Port
            }.Uri;
            return base.SendAsync(request, cancellationToken);
        }
    }

    /// <summary>A search service created through ARM, deleted again when the test is done.</summary>
    private sealed class SearchService : IAsyncDisposable
    {
        private static readonly HttpClient Http = new();
        private readonly string armPath;

        private SearchService(string armPath, SearchIndexClient client)
        {
            this.armPath = armPath;
            Client = client;
        }

        public SearchIndexClient Client { get; }

        public static async Task<SearchService> CreateAsync(CancellationToken cancellationToken)
        {
            string name = $"sdknet{Guid.NewGuid():N}"[..24];
            string armPath = $"/subscriptions/{Subscription}/resourceGroups/sdk-test-rg-search-dotnet"
                + $"/providers/Microsoft.Search/searchServices/{name}";

            using var created = await SendAsync(HttpMethod.Put, armPath,
                "{\"location\": \"eastus\", \"sku\": {\"name\": \"basic\"}}", cancellationToken);
            created.EnsureSuccessStatusCode();
            using var keys = await SendAsync(HttpMethod.Post, armPath + "/listAdminKeys", "", cancellationToken);
            keys.EnsureSuccessStatusCode();
            using var document = JsonDocument.Parse(await keys.Content.ReadAsStringAsync(cancellationToken));
            string adminKey = document.RootElement.GetProperty("primaryKey").GetString()!;

            // The SDK accepts an https endpoint only, so the client is given one and the transport sends each
            // request to the emulator's own scheme and port.
            var endpoint = new UriBuilder(EmulatorEndpoint) { Scheme = "https", Path = $"/{name}-search" }.Uri;
            var options = new SearchClientOptions
            {
                Transport = new HttpClientTransport(new HttpClient(new EmulatorHandler()))
            };
            return new SearchService(armPath,
                new SearchIndexClient(endpoint, new AzureKeyCredential(adminKey), options));
        }

        public async ValueTask DisposeAsync()
        {
            // A failed delete would leave the service behind for the next run, so it fails the suite.
            using var deleted = await SendAsync(HttpMethod.Delete, armPath, "", CancellationToken.None);
            deleted.EnsureSuccessStatusCode();
        }

        private static Task<HttpResponseMessage> SendAsync(
            HttpMethod method, string path, string body, CancellationToken cancellationToken)
        {
            var request = new HttpRequestMessage(method, new Uri(EmulatorEndpoint, path + ArmApi))
            {
                Content = new StringContent(body, Encoding.UTF8, "application/json")
            };
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", "fake");
            return Http.SendAsync(request, cancellationToken);
        }
    }
}
