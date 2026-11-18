using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Azure;
using Azure.Core.Pipeline;
using Azure.Search.Documents;
using Azure.Search.Documents.Indexes;
using Azure.Search.Documents.Indexes.Models;

namespace FlociAz.Compatibility;

/// <summary>
/// Index management on the Azure AI Search data plane, through SearchIndexClient. Each test creates its own
/// search service through ARM and signs in with that service's admin key.
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
    public async Task AnswersConflictNotFoundAndBadRequest(CancellationToken cancellationToken)
    {
        await using var service = await SearchService.CreateAsync(cancellationToken);
        var client = service.Client;
        await client.CreateIndexAsync(Hotels("hotels-twice"), cancellationToken);

        await Assert.That(await StatusOf(() => client.CreateIndexAsync(Hotels("hotels-twice"), cancellationToken)))
            .IsEqualTo(409);
        await Assert.That(await StatusOf(() => client.GetIndexAsync("hotels-missing", cancellationToken)))
            .IsEqualTo(404);

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
