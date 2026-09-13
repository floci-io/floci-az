using System.Net;
using Microsoft.Azure.Cosmos;
using Newtonsoft.Json.Linq;

namespace FlociAz.Compatibility;

[NotInParallel]
public sealed class CosmosTransactionalBatchCompatibilityTests
{
    private static readonly string EmulatorEndpoint =
        Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577";

    [Test]
    [Arguments(false)]
    [Arguments(true)]
    [Timeout(60_000)]
    public async Task QueryContinuesAfterDeletingEachPage(bool ordered, CancellationToken cancellationToken)
    {
        using CosmosClient client = CreateClient($"dotnetpurge{Guid.NewGuid():N}");
        Database database = await client.CreateDatabaseAsync(
            $"db-{Guid.NewGuid():N}", cancellationToken: cancellationToken);
        try
        {
            Container container = await database.CreateContainerAsync(
                new ContainerProperties("items", "/tenant"), cancellationToken: cancellationToken);
            string[] expected = Enumerable.Range(0, 7).Select(i => $"item-{i}").ToArray();
            for (int i = 0; i < expected.Length; i++)
            {
                await container.CreateItemAsync(new { id = expected[i], tenant = "target", rank = i / 3 },
                    new PartitionKey("target"), cancellationToken: cancellationToken);
                await container.CreateItemAsync(new { id = expected[i], tenant = "other", rank = i / 3 },
                    new PartitionKey("other"), cancellationToken: cancellationToken);
            }

            string sql = "SELECT c.id FROM c" + (ordered ? " ORDER BY c.rank DESC" : "");
            var options = new QueryRequestOptions { PartitionKey = new PartitionKey("target"), MaxItemCount = 2 };
            using FeedIterator<JObject> iterator = container.GetItemQueryIterator<JObject>(sql, requestOptions: options);
            var visited = new List<string>();
            int pages = 0;
            while (iterator.HasMoreResults)
            {
                FeedResponse<JObject> page = await iterator.ReadNextAsync(cancellationToken);
                if (++pages > 5)
                {
                    throw new InvalidOperationException("Query continuation did not terminate");
                }
                if (page.Count == 0)
                {
                    continue;
                }
                await Assert.That(page.Count <= 2).IsTrue();
                TransactionalBatch batch = container.CreateTransactionalBatch(new PartitionKey("target"));
                foreach (JObject item in page)
                {
                    string id = item.Value<string>("id")!;
                    visited.Add(id);
                    batch.DeleteItem(id);
                }
                using TransactionalBatchResponse deleted = await batch.ExecuteAsync(cancellationToken);
                await Assert.That(deleted.IsSuccessStatusCode).IsTrue();
                await Assert.That(deleted.Count).IsEqualTo(page.Count);
                for (int i = 0; i < deleted.Count; i++)
                {
                    await Assert.That(deleted[i].StatusCode).IsEqualTo(HttpStatusCode.NoContent);
                }
            }

            await Assert.That(visited).IsEquivalentTo(expected);
            using FeedIterator<JObject> remaining = container.GetItemQueryIterator<JObject>(
                "SELECT c.id FROM c", requestOptions: options);
            while (remaining.HasMoreResults)
            {
                await Assert.That((await remaining.ReadNextAsync(cancellationToken)).Count).IsEqualTo(0);
            }
            using FeedIterator<JObject> other = container.GetItemQueryIterator<JObject>(
                "SELECT c.id FROM c",
                requestOptions: new QueryRequestOptions { PartitionKey = new PartitionKey("other") });
            var preserved = new List<string>();
            while (other.HasMoreResults)
            {
                preserved.AddRange((await other.ReadNextAsync(cancellationToken)).Select(item => item.Value<string>("id")!));
            }
            await Assert.That(preserved).IsEquivalentTo(expected);
        }
        finally
        {
            await database.DeleteAsync(cancellationToken: cancellationToken);
        }
    }

    [Test]
    [Timeout(60_000)]
    public async Task DotnetSdkExecutesTransactionalBatches(CancellationToken cancellationToken)
    {
        string account = $"dotnetbatch{Guid.NewGuid():N}";
        string databaseName = $"db-{Guid.NewGuid():N}";
        string containerName = $"items-{Guid.NewGuid():N}";
        using CosmosClient client = CreateClient(account);
        Database database = await client.CreateDatabaseAsync(databaseName, cancellationToken: cancellationToken);
        Container container = await database.CreateContainerAsync(
            new ContainerProperties(containerName, "/tenant"),
            cancellationToken: cancellationToken);

        try
        {
            await container.CreateItemAsync(
                new { id = "one", tenant = "u1", kind = "a", count = 1 },
                new PartitionKey("u1"),
                cancellationToken: cancellationToken);

            using TransactionalBatchResponse patch = await container
                .CreateTransactionalBatch(new PartitionKey("u1"))
                .PatchItem("one",
                [
                    PatchOperation.Set("/kind", "z"),
                    PatchOperation.Increment("/count", 10)
                ])
                .ExecuteAsync(cancellationToken);

            await Assert.That(patch.StatusCode).IsEqualTo(HttpStatusCode.OK);
            await Assert.That(patch.Count).IsEqualTo(1);
            await Assert.That(patch[0].StatusCode).IsEqualTo(HttpStatusCode.OK);

            ItemResponse<JObject> patched = await container.ReadItemAsync<JObject>(
                "one", new PartitionKey("u1"), cancellationToken: cancellationToken);
            await Assert.That(patched.Resource.Value<string>("kind")).IsEqualTo("z");
            await Assert.That(patched.Resource.Value<int>("count")).IsEqualTo(11);

            using TransactionalBatchResponse opcodeBatch = await container
                .CreateTransactionalBatch(new PartitionKey("u1"))
                .CreateItem(new { id = "created", tenant = "u1", kind = "create" })
                .ReadItem("one")
                .UpsertItem(new { id = "upserted", tenant = "u1", kind = "upsert" })
                .ExecuteAsync(cancellationToken);

            await Assert.That(opcodeBatch.StatusCode).IsEqualTo(HttpStatusCode.OK);
            await Assert.That(opcodeBatch.Count).IsEqualTo(3);
            await Assert.That(opcodeBatch[0].StatusCode).IsEqualTo(HttpStatusCode.Created);
            await Assert.That(opcodeBatch[1].StatusCode).IsEqualTo(HttpStatusCode.OK);
            await Assert.That(opcodeBatch[2].StatusCode).IsEqualTo(HttpStatusCode.Created);

            ItemResponse<JObject> created = await container.ReadItemAsync<JObject>(
                "created", new PartitionKey("u1"), cancellationToken: cancellationToken);
            ItemResponse<JObject> upserted = await container.ReadItemAsync<JObject>(
                "upserted", new PartitionKey("u1"), cancellationToken: cancellationToken);
            await Assert.That(created.Resource.Value<string>("kind")).IsEqualTo("create");
            await Assert.That(upserted.Resource.Value<string>("kind")).IsEqualTo("upsert");

            using TransactionalBatchResponse filteredPatch = await container
                .CreateTransactionalBatch(new PartitionKey("u1"))
                .PatchItem("one", [PatchOperation.Set("/kind", "ignored")],
                    new TransactionalBatchPatchItemRequestOptions
                    {
                        FilterPredicate = "FROM c WHERE c.kind = 'no-match'"
                    })
                .ExecuteAsync(cancellationToken);

            await Assert.That(filteredPatch.StatusCode).IsEqualTo(HttpStatusCode.PreconditionFailed);
            await Assert.That(filteredPatch[0].StatusCode).IsEqualTo(HttpStatusCode.PreconditionFailed);

            using TransactionalBatchResponse rolledBack = await container
                .CreateTransactionalBatch(new PartitionKey("u1"))
                .ReplaceItem("one", new { id = "one", tenant = "u1", kind = "replacement", count = 99 })
                .DeleteItem("missing")
                .ExecuteAsync(cancellationToken);

            await Assert.That(rolledBack.StatusCode).IsEqualTo(HttpStatusCode.NotFound);
            await Assert.That(rolledBack[0].StatusCode).IsEqualTo(HttpStatusCode.FailedDependency);
            await Assert.That(rolledBack[1].StatusCode).IsEqualTo(HttpStatusCode.NotFound);

            ItemResponse<JObject> afterRollback = await container.ReadItemAsync<JObject>(
                "one", new PartitionKey("u1"), cancellationToken: cancellationToken);
            await Assert.That(afterRollback.Resource.Value<string>("kind")).IsEqualTo("z");
            await Assert.That(afterRollback.Resource.Value<int>("count")).IsEqualTo(11);

            using TransactionalBatchResponse staleEtag = await container
                .CreateTransactionalBatch(new PartitionKey("u1"))
                .DeleteItem("one", new TransactionalBatchItemRequestOptions { IfMatchEtag = "stale" })
                .ExecuteAsync(cancellationToken);

            await Assert.That(staleEtag.StatusCode).IsEqualTo(HttpStatusCode.PreconditionFailed);
            await Assert.That(staleEtag[0].StatusCode).IsEqualTo(HttpStatusCode.PreconditionFailed);

            ItemResponse<JObject> afterStaleEtag = await container.ReadItemAsync<JObject>(
                "one", new PartitionKey("u1"), cancellationToken: cancellationToken);
            await Assert.That(afterStaleEtag.Resource.Value<string>("kind")).IsEqualTo("z");
            await Assert.That(afterStaleEtag.Resource.Value<int>("count")).IsEqualTo(11);
        }
        finally
        {
            await database.DeleteAsync(cancellationToken: cancellationToken);
        }
    }

    private static CosmosClient CreateClient(string account)
    {
        string endpoint = $"{EmulatorEndpoint.TrimEnd('/')}/{account}-cosmos/";
        return new CosmosClient(endpoint, Convert.ToBase64String(new byte[64]), new CosmosClientOptions
        {
            ConnectionMode = ConnectionMode.Gateway,
            LimitToEndpoint = true,
            RequestTimeout = TimeSpan.FromSeconds(10)
        });
    }
}
