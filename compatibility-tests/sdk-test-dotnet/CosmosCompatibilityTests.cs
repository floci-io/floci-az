using System.Collections.ObjectModel;
using Microsoft.Azure.Cosmos;
using Newtonsoft.Json;

namespace FlociAz.Compatibility;

[NotInParallel]
public sealed class CosmosCompatibilityTests
{
    private const string CosmosKey =
        "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==";
    private static readonly string EmulatorEndpoint =
        Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577";

    [Test]
    [Timeout(60_000)]
    public async Task DotnetSdkExecutesQueries(CancellationToken cancellationToken)
    {
        using var client = new CosmosClient(
            $"{EmulatorEndpoint}/devstoreaccount1-cosmos/",
            CosmosKey,
            new CosmosClientOptions
            {
                ConnectionMode = ConnectionMode.Gateway,
                LimitToEndpoint = true
            });

        string databaseId = $"dotnet-query-{Guid.NewGuid():N}";
        Database database = await client.CreateDatabaseAsync(databaseId, cancellationToken: cancellationToken);

        try
        {
            var properties = new ContainerProperties("items", "/pk");
            properties.IndexingPolicy.CompositeIndexes.Add(
                new Collection<CompositePath>
                {
                    new() { Path = "/rank", Order = CompositePathSortOrder.Ascending },
                    new() { Path = "/id", Order = CompositePathSortOrder.Ascending }
                });
            Container container = await database.CreateContainerAsync(
                properties, cancellationToken: cancellationToken);
            await container.CreateItemAsync(
                new QueryItem("one", "a", 3),
                new PartitionKey("a"),
                cancellationToken: cancellationToken);
            await container.CreateItemAsync(
                new QueryItem("two", "b", 1),
                new PartitionKey("b"),
                cancellationToken: cancellationToken);
            await container.CreateItemAsync(
                new QueryItem("three", "a", 2),
                new PartitionKey("a"),
                cancellationToken: cancellationToken);

            List<QueryItem> selected = await ReadAll(
                container.GetItemQueryIterator<QueryItem>("SELECT * FROM c"), cancellationToken);
            await Assert.That(selected.Select(item => item.Id))
                .IsEquivalentTo(["one", "two", "three"]);

            List<QueryItem> ordered = await ReadAll(
                container.GetItemQueryIterator<QueryItem>("SELECT * FROM c ORDER BY c.rank"),
                cancellationToken);
            await Assert.That(ordered.Count).IsEqualTo(3);
            await Assert.That(ordered[0].Rank).IsEqualTo(1);
            await Assert.That(ordered[1].Rank).IsEqualTo(2);
            await Assert.That(ordered[2].Rank).IsEqualTo(3);

            List<QueryItem> multiOrdered = await ReadAll(
                container.GetItemQueryIterator<QueryItem>(
                    "SELECT * FROM c ORDER BY c.rank, c.id"),
                cancellationToken);
            await Assert.That(multiOrdered.Count).IsEqualTo(3);
            await Assert.That(multiOrdered[0].Id).IsEqualTo("two");
            await Assert.That(multiOrdered[1].Id).IsEqualTo("three");
            await Assert.That(multiOrdered[2].Id).IsEqualTo("one");

            List<int> counts = await ReadAll(
                container.GetItemQueryIterator<int>("SELECT VALUE COUNT(1) FROM c"),
                cancellationToken);
            await Assert.That(counts).IsEquivalentTo([3]);

            List<QueryItem> crossPartition = await ReadAll(
                container.GetItemQueryIterator<QueryItem>(
                    new QueryDefinition("SELECT * FROM c WHERE c.pk = @pk")
                        .WithParameter("@pk", "b")),
                cancellationToken);
            await Assert.That(crossPartition.Select(item => item.Id)).IsEquivalentTo(["two"]);

            var partitionOptions = new QueryRequestOptions
            {
                PartitionKey = new PartitionKey("a"),
                MaxItemCount = 1
            };
            List<QueryItem> scoped = await ReadAll(
                container.GetItemQueryIterator<QueryItem>(
                    "SELECT * FROM c ORDER BY c.rank", requestOptions: partitionOptions),
                cancellationToken);
            await Assert.That(scoped.Select(item => item.Id)).IsEquivalentTo(["one", "three"]);
            await Assert.That(scoped[0].Id).IsEqualTo("three");
            List<int> scopedCounts = await ReadAll(
                container.GetItemQueryIterator<int>(
                    "SELECT VALUE COUNT(1) FROM c", requestOptions: partitionOptions),
                cancellationToken);
            await Assert.That(scopedCounts).IsEquivalentTo([2]);
            List<QueryItem> missingPartition = await ReadAll(
                container.GetItemQueryIterator<QueryItem>("SELECT * FROM c",
                    requestOptions: new QueryRequestOptions { PartitionKey = new PartitionKey("missing") }),
                cancellationToken);
            await Assert.That(missingPartition.Count).IsEqualTo(0);
            try
            {
                await container.CreateItemAsync(new QueryItem("wrong-partition", "b", 4),
                    new PartitionKey("a"), cancellationToken: cancellationToken);
                throw new InvalidOperationException("A mismatched partition header unexpectedly authorized a write");
            }
            catch (CosmosException error)
            {
                await Assert.That(error.StatusCode).IsEqualTo(System.Net.HttpStatusCode.BadRequest);
                await Assert.That(error.SubStatusCode).IsEqualTo(1001);
            }
        }
        finally
        {
            await database.DeleteAsync(cancellationToken: cancellationToken);
        }
    }

    private static async Task<List<T>> ReadAll<T>(
        FeedIterator<T> iterator,
        CancellationToken cancellationToken)
    {
        var results = new List<T>();
        while (iterator.HasMoreResults)
        {
            FeedResponse<T> page = await iterator.ReadNextAsync(cancellationToken);
            results.AddRange(page);
        }

        return results;
    }

    private sealed record QueryItem(
        [property: JsonProperty("id")] string Id,
        [property: JsonProperty("pk")] string PartitionKey,
        [property: JsonProperty("rank")] int Rank);
}
