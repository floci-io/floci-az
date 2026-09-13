using Azure.Messaging.ServiceBus;
using Azure.Messaging.ServiceBus.Administration;

namespace FlociAz.Compatibility;

[NotInParallel]
public sealed class ServiceBusPeekLockCompatibilityTests
{
    [Test]
    [Arguments("queue")]
    [Arguments("large-queue")]
    [Arguments("subscription")]
    [Arguments("session-dlq")]
    [Timeout(90_000)]
    public async Task ExpiredMessagesReturnWhileTheirReceiverRemainsOpen(string entityKind, CancellationToken cancellationToken)
    {
        string endpoint = Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577";
        string host = Environment.GetEnvironmentVariable("SERVICEBUS_HOST") ?? "localhost";
        string port = Environment.GetEnvironmentVariable("SERVICEBUS_AMQP_PORT") ?? "5673";
        const string credentials = "SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=devkey;UseDevelopmentEmulator=true;";
        var admin = new ServiceBusAdministrationClient($"Endpoint=sb://{new Uri(endpoint).Authority};{credentials}");
        await using var client = new ServiceBusClient($"Endpoint=sb://{host}:{port};{credentials}",
            new ServiceBusClientOptions { RetryOptions = { MaxRetries = 0, TryTimeout = TimeSpan.FromSeconds(10) } });
        string entity = $"peeklock-{Guid.NewGuid():N}";
        bool queue = entityKind.EndsWith("queue");
        bool deadLetter = entityKind == "session-dlq";
        if (queue)
        {
            await admin.CreateQueueAsync(new CreateQueueOptions(entity)
            {
                LockDuration = TimeSpan.FromSeconds(2), MaxDeliveryCount = 4
            }, cancellationToken);
        }
        else
        {
            await admin.CreateTopicAsync(entity, cancellationToken);
            await admin.CreateSubscriptionAsync(new CreateSubscriptionOptions(entity, "consumer")
            {
                RequiresSession = deadLetter, LockDuration = TimeSpan.FromSeconds(2), MaxDeliveryCount = 4
            }, cancellationToken);
        }
        try
        {
            await using ServiceBusSender sender = client.CreateSender(entity);
            string body = entityKind == "large-queue" ? new string('x', 200_000) : "expires";
            await sender.SendMessageAsync(new ServiceBusMessage(body)
            {
                MessageId = "expires", SessionId = deadLetter ? "session" : null
            }, cancellationToken);
            if (deadLetter)
            {
                await using ServiceBusSessionReceiver session = await client.AcceptSessionAsync(
                    entity, "consumer", "session", cancellationToken: cancellationToken);
                ServiceBusReceivedMessage poison = (await session.ReceiveMessageAsync(TimeSpan.FromSeconds(5), cancellationToken))!;
                await Assert.That(poison).IsNotNull();
                await session.DeadLetterMessageAsync(poison, cancellationToken: cancellationToken);
            }

            var options = new ServiceBusReceiverOptions { SubQueue = deadLetter ? SubQueue.DeadLetter : SubQueue.None };
            await using ServiceBusReceiver owner = queue ? client.CreateReceiver(entity, options) : client.CreateReceiver(entity, "consumer", options);
            await using ServiceBusReceiver competitor = queue ? client.CreateReceiver(entity, options) : client.CreateReceiver(entity, "consumer", options);
            ServiceBusReceivedMessage first = (await owner.ReceiveMessageAsync(TimeSpan.FromSeconds(5), cancellationToken))!;
            await Assert.That(first).IsNotNull();
            await Assert.That(first.LockedUntil > DateTimeOffset.UtcNow).IsTrue();
            await Assert.That(first.LockedUntil < DateTimeOffset.UtcNow.AddSeconds(3)).IsTrue();
            await Task.Delay(TimeSpan.FromSeconds(3), cancellationToken);

            ServiceBusReceivedMessage redelivered = (await competitor.ReceiveMessageAsync(TimeSpan.FromSeconds(5), cancellationToken))!;
            await Assert.That(redelivered).IsNotNull();
            await Assert.That(redelivered.MessageId).IsEqualTo(first.MessageId);
            await Assert.That(redelivered.Body.ToString()).IsEqualTo(body);
            await Assert.That(redelivered.DeliveryCount).IsEqualTo(first.DeliveryCount + 1);
            await Assert.That(redelivered.LockedUntil > DateTimeOffset.UtcNow).IsTrue();
            try
            {
                await owner.CompleteMessageAsync(first, cancellationToken);
                throw new InvalidOperationException("Expired delivery was incorrectly completed");
            }
            catch (ServiceBusException error)
            {
                await Assert.That(error.Reason).IsEqualTo(ServiceBusFailureReason.MessageLockLost);
            }
            await competitor.CompleteMessageAsync(redelivered, cancellationToken);
            await Assert.That(await competitor.ReceiveMessageAsync(TimeSpan.FromMilliseconds(200), cancellationToken)).IsNull();
        }
        finally
        {
            if (queue) await admin.DeleteQueueAsync(entity, cancellationToken);
            else await admin.DeleteTopicAsync(entity, cancellationToken);
        }
    }
}
