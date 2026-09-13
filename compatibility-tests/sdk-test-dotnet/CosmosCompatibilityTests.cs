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

            List<QueryItem> included = await ReadAll(
                container.GetItemQueryIterator<QueryItem>(new QueryDefinition(
                    "SELECT * FROM c WHERE ARRAY_CONTAINS(@ids, c.id)")
                    .WithParameter("@ids", new[] { "one", "three" })), cancellationToken);
            await Assert.That(included.Select(item => item.Id)).IsEquivalentTo(["one", "three"]);
            List<QueryItem> excluded = await ReadAll(
                container.GetItemQueryIterator<QueryItem>(new QueryDefinition(
                    "SELECT * FROM c WHERE NOT ARRAY_CONTAINS(@ids, c.id)")
                    .WithParameter("@ids", new[] { "one", "three" })), cancellationToken);
            await Assert.That(excluded.Select(item => item.Id)).IsEquivalentTo(["two"]);
            List<QueryItem> empty = await ReadAll(
                container.GetItemQueryIterator<QueryItem>(new QueryDefinition(
                    "SELECT * FROM c WHERE ARRAY_CONTAINS(@ids, c.id)")
                    .WithParameter("@ids", Array.Empty<string>())), cancellationToken);
            await Assert.That(empty.Count).IsEqualTo(0);

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

    [Test]
    [Arguments("NOT IS_DEFINED(c.isDeleted) OR c.isDeleted = false", "absent,available,inactive,claimed")]
    [Arguments("NOT IS_DEFINED(c.isDeleted) AND c.isActive = true", "absent")]
    [Arguments("((NOT IS_DEFINED(c.isDeleted)) OR c.isDeleted = false)", "absent,available,inactive,claimed")]
    [Arguments("NOT (IS_DEFINED(c.isDeleted) OR c.isActive = true)", "")]
    [Arguments("((NOT IS_DEFINED(c.isDeleted) OR c.isDeleted = false) AND c.isActive = true)", "absent,available,claimed")]
    [Arguments("NOT IS_DEFINED(c.claimedUntil) OR IS_NULL(c.claimedUntil)", "absent,available,deleted,inactive")]
    [Arguments("NOT c.score BETWEEN 1 AND 2", "absent,inactive,claimed")]
    [Arguments("NOT (c.score BETWEEN 1 AND 2) AND c.isActive = true", "absent,claimed")]
    [Arguments("(c.score BETWEEN 1 AND 2) OR NOT c.isActive = true", "available,deleted,inactive")]
    [Timeout(60_000)]
    public async Task DotnetSdkRespectsNotPrecedence(
        string predicate, string expectedIds, CancellationToken cancellationToken)
    {
        using var client = new CosmosClient(
            $"{EmulatorEndpoint}/devstoreaccount1-cosmos/",
            CosmosKey,
            new CosmosClientOptions
            {
                ConnectionMode = ConnectionMode.Gateway,
                LimitToEndpoint = true
            });
        Database database = await client.CreateDatabaseAsync(
            $"dotnet-not-{Guid.NewGuid():N}", cancellationToken: cancellationToken);

        try
        {
            Container container = await database.CreateContainerAsync(
                new ContainerProperties("items", "/userId"), cancellationToken: cancellationToken);
            Dictionary<string, object?>[] documents =
            [
                new() { ["id"] = "absent", ["userId"] = "user-1", ["isActive"] = true },
                new() { ["id"] = "available", ["userId"] = "user-1", ["isActive"] = true,
                    ["isDeleted"] = false, ["claimedUntil"] = null },
                new() { ["id"] = "deleted", ["userId"] = "user-1", ["isActive"] = true,
                    ["isDeleted"] = true, ["claimedUntil"] = null },
                new() { ["id"] = "inactive", ["userId"] = "user-1", ["isActive"] = false,
                    ["isDeleted"] = false, ["claimedUntil"] = null },
                new() { ["id"] = "claimed", ["userId"] = "user-1", ["isActive"] = true,
                    ["isDeleted"] = false, ["claimedUntil"] = "2026-09-12T12:00:00Z" }
            ];
            for (int i = 0; i < documents.Length; i++)
            {
                var document = documents[i];
                document["score"] = i;
                await container.CreateItemAsync(document, new PartitionKey("user-1"),
                    cancellationToken: cancellationToken);
            }

            List<string> ids = await ReadAll(container.GetItemQueryIterator<string>(
                $"SELECT VALUE c.id FROM c WHERE ({predicate})",
                requestOptions: new QueryRequestOptions
                {
                    PartitionKey = new PartitionKey("user-1"),
                    MaxItemCount = 1
                }), cancellationToken);
            await Assert.That(ids).IsEquivalentTo(
                expectedIds.Split(',', StringSplitOptions.RemoveEmptyEntries));
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
