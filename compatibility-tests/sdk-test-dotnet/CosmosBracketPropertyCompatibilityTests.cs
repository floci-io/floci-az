using Microsoft.Azure.Cosmos;
using Newtonsoft.Json.Linq;

namespace FlociAz.Compatibility;

[NotInParallel]
public sealed class CosmosBracketPropertyCompatibilityTests
{
    [Test]
    [Timeout(60_000)]
    public async Task ErasureQueryFindsBracketIndexedUserReferences(CancellationToken cancellationToken)
    {
        string endpoint = Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577";
        using var client = new CosmosClient($"{endpoint}/devstoreaccount1-cosmos/",
            "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==",
            new CosmosClientOptions { ConnectionMode = ConnectionMode.Gateway, LimitToEndpoint = true });
        Database database = await client.CreateDatabaseAsync(
            $"dotnet-bracket-{Guid.NewGuid():N}", cancellationToken: cancellationToken);
        try
        {
            Container container = await database.CreateContainerAsync(
                "items", "/pk", cancellationToken: cancellationToken);
            string userId = Guid.NewGuid().ToString();
            await container.CreateItemAsync(new
            {
                id = "declined", pk = "event-a", goingUserIds = Array.Empty<string>(),
                rsvpVersionsByUserId = new Dictionary<string, int> { [userId] = 4 }
            }, new PartitionKey("event-a"), cancellationToken: cancellationToken);
            await container.CreateItemAsync(new
            {
                id = "unrelated", pk = "event-b", goingUserIds = Array.Empty<string>(),
                rsvpVersionsByUserId = new Dictionary<string, int> { ["another-user"] = 2 }
            }, new PartitionKey("event-b"), cancellationToken: cancellationToken);

            var query = new QueryDefinition(
                "SELECT * FROM c WHERE ARRAY_CONTAINS(c.goingUserIds, @userId) "
                + $"OR IS_DEFINED(c.rsvpVersionsByUserId[\"{userId}\"])")
                .WithParameter("@userId", userId);
            using FeedIterator<JObject> iterator = container.GetItemQueryIterator<JObject>(query,
                requestOptions: new QueryRequestOptions { MaxItemCount = 1 });
            var ids = new List<string>();
            while (iterator.HasMoreResults)
            {
                FeedResponse<JObject> page = await iterator.ReadNextAsync(cancellationToken);
                ids.AddRange(page.Select(item => item.Value<string>("id")!));
            }
            await Assert.That(ids).IsEquivalentTo(["declined"]);
        }
        finally
        {
            await database.DeleteAsync(cancellationToken: cancellationToken);
        }
    }
}
