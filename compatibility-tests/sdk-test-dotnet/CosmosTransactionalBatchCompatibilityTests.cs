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
