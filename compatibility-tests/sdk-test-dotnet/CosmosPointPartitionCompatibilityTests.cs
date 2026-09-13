using System.Net;
using Microsoft.Azure.Cosmos;

namespace FlociAz.Compatibility;

[NotInParallel]
public sealed class CosmosPointPartitionCompatibilityTests
{
    [Test]
    [Timeout(60_000)]
    public async Task PointReadsAndDeletesStayWithinTheRequestedPartition(CancellationToken cancellationToken)
    {
        string endpoint = Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577";
        using var client = new CosmosClient($"{endpoint}/devstoreaccount1-cosmos/",
            "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==",
            new CosmosClientOptions { ConnectionMode = ConnectionMode.Gateway, LimitToEndpoint = true });
        Database database = await client.CreateDatabaseAsync(
            $"dotnet-point-partition-{Guid.NewGuid():N}", cancellationToken: cancellationToken);
        try
        {
            Container container = await database.CreateContainerAsync(
                "items", "/pk", cancellationToken: cancellationToken);
            await container.CreateItemAsync(new { id = "same-id", pk = "alice" },
                new PartitionKey("alice"), cancellationToken: cancellationToken);

            foreach (PartitionKey missing in new[] { new PartitionKey("bob"), new PartitionKey(""), PartitionKey.Null, PartitionKey.None })
            {
                using ResponseMessage read = await container.ReadItemStreamAsync("same-id", missing,
                    cancellationToken: cancellationToken);
                await Assert.That(read.StatusCode).IsEqualTo(HttpStatusCode.NotFound);
                using ResponseMessage delete = await container.DeleteItemStreamAsync("same-id", missing,
                    cancellationToken: cancellationToken);
                await Assert.That(delete.StatusCode).IsEqualTo(HttpStatusCode.NotFound);
            }

            await container.CreateItemAsync(new { id = "same-id", pk = "bob" },
                new PartitionKey("bob"), cancellationToken: cancellationToken);
            using ResponseMessage bobRead = await container.ReadItemStreamAsync("same-id", new PartitionKey("bob"),
                cancellationToken: cancellationToken);
            await Assert.That(bobRead.StatusCode).IsEqualTo(HttpStatusCode.OK);
            using ResponseMessage bobDelete = await container.DeleteItemStreamAsync("same-id", new PartitionKey("bob"),
                cancellationToken: cancellationToken);
            await Assert.That(bobDelete.StatusCode).IsEqualTo(HttpStatusCode.NoContent);
            using ResponseMessage bobMissing = await container.ReadItemStreamAsync("same-id", new PartitionKey("bob"),
                cancellationToken: cancellationToken);
            await Assert.That(bobMissing.StatusCode).IsEqualTo(HttpStatusCode.NotFound);
            using ResponseMessage alice = await container.ReadItemStreamAsync("same-id", new PartitionKey("alice"),
                cancellationToken: cancellationToken);
            await Assert.That(alice.StatusCode).IsEqualTo(HttpStatusCode.OK);
            await container.CreateItemAsync(new { id = "empty", pk = "" }, new PartitionKey(""),
                cancellationToken: cancellationToken);
            using ResponseMessage nullRead = await container.ReadItemStreamAsync("empty", PartitionKey.Null,
                cancellationToken: cancellationToken);
            await Assert.That(nullRead.StatusCode).IsEqualTo(HttpStatusCode.NotFound);
            using ResponseMessage nullDelete = await container.DeleteItemStreamAsync("empty", PartitionKey.Null,
                cancellationToken: cancellationToken);
            await Assert.That(nullDelete.StatusCode).IsEqualTo(HttpStatusCode.NotFound);
        }
        finally
        {
            await database.DeleteAsync(cancellationToken: cancellationToken);
        }
    }
}
