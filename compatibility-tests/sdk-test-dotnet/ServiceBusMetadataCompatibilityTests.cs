using Azure.Messaging.ServiceBus;
using Azure.Messaging.ServiceBus.Administration;

namespace FlociAz.Compatibility;

[NotInParallel]
public sealed class ServiceBusMetadataCompatibilityTests
{
    [Test]
    [Arguments(false, false, false)]
    [Arguments(false, false, true)]
    [Arguments(false, true, false)]
    [Arguments(false, true, true)]
    [Arguments(true, false, false)]
    [Arguments(true, false, true)]
    [Arguments(true, true, false)]
    [Arguments(true, true, true)]
    [Timeout(90_000)]
    public async Task ReceiveMetadataMatchesPeekThroughRedeliveryAndDeadLetter(
        bool subscription, bool session, bool large, CancellationToken cancellationToken)
    {
        var admin = new ServiceBusAdministrationClient(ConnectionString(administration: true));
        await using var client = new ServiceBusClient(ConnectionString());
        string entity = $"metadata-{Guid.NewGuid():N}";
        if (subscription)
        {
            await admin.CreateTopicAsync(entity, cancellationToken);
            await admin.CreateSubscriptionAsync(new CreateSubscriptionOptions(entity, "consumer")
            {
                RequiresSession = session
            }, cancellationToken);
        }
        else
        {
            await admin.CreateQueueAsync(new CreateQueueOptions(entity) { RequiresSession = session }, cancellationToken);
        }

        try
        {
            DateTimeOffset beforeSend = DateTimeOffset.UtcNow.AddSeconds(-5);
            string body = large ? new string('x', 200_000) : "metadata";
            await using ServiceBusSender sender = client.CreateSender(entity);
            for (int i = 0; i < 2; i++)
            {
                await sender.SendMessageAsync(new ServiceBusMessage(body + i)
                {
                    MessageId = i.ToString(), SessionId = session ? "session" : null,
                    CorrelationId = "correlation-" + i, ContentType = "text/plain",
                    ApplicationProperties = { ["custom-text"] = "preserved", ["custom-number"] = i }
                }, cancellationToken);
            }
            DateTimeOffset afterSend = DateTimeOffset.UtcNow.AddSeconds(5);
            await using ServiceBusReceiver receiver = await CreateReceiver(client, entity, subscription, session, cancellationToken);
            var peeked = (await receiver.PeekMessagesAsync(2, cancellationToken: cancellationToken))
                .ToDictionary(message => message.MessageId);
            await Assert.That(peeked.Count).IsEqualTo(2);

            var received = new List<ServiceBusReceivedMessage>();
            for (int i = 0; i < 2; i++)
            {
                ServiceBusReceivedMessage message = await Receive(receiver, cancellationToken);
                await Assert.That(message.DeliveryCount).IsEqualTo(1);
                await CheckMetadata(message, peeked[message.MessageId], beforeSend, afterSend);
                await CheckUserProperties(message);
                await Assert.That(message.Body.ToString()).IsEqualTo(body + message.MessageId);
                received.Add(message);
            }
            await Assert.That(received.Select(message => message.SequenceNumber).Distinct().Count()).IsEqualTo(2);
            foreach (ServiceBusReceivedMessage message in received)
            {
                await receiver.AbandonMessageAsync(message, cancellationToken: cancellationToken);
            }
            for (int i = 0; i < 2; i++)
            {
                ServiceBusReceivedMessage message = await Receive(receiver, cancellationToken);
                await Assert.That(message.DeliveryCount).IsEqualTo(2);
                await CheckMetadata(message, peeked[message.MessageId], beforeSend, afterSend);
                await CheckUserProperties(message);
                await receiver.DeadLetterMessageAsync(message, "metadata-probe", "retention check", cancellationToken);
            }

            var options = new ServiceBusReceiverOptions { SubQueue = SubQueue.DeadLetter };
            await using ServiceBusReceiver dlq = subscription
                ? client.CreateReceiver(entity, "consumer", options) : client.CreateReceiver(entity, options);
            var dlqPeeked = (await dlq.PeekMessagesAsync(2, cancellationToken: cancellationToken))
                .ToDictionary(message => message.MessageId);
            await Assert.That(dlqPeeked.Count).IsEqualTo(2);
            var inspected = new HashSet<long>();
            for (int i = 0; i < 2; i++)
            {
                ServiceBusReceivedMessage message = await Receive(dlq, cancellationToken);
                await CheckMetadata(message, dlqPeeked[message.MessageId], beforeSend, afterSend);
                await CheckUserProperties(message);
                await Assert.That(message.EnqueuedTime).IsEqualTo(peeked[message.MessageId].EnqueuedTime);
                await Assert.That(message.EnqueuedTime > DateTimeOffset.UtcNow.AddDays(-7)).IsTrue();
                await Assert.That(inspected.Add(message.SequenceNumber)).IsTrue();
                await Assert.That(message.DeadLetterReason).IsEqualTo("metadata-probe");
                await Assert.That(message.Body.ToString()).IsEqualTo(body + message.MessageId);
                await dlq.CompleteMessageAsync(message, cancellationToken);
            }
        }
        finally
        {
            if (subscription)
            {
                await admin.DeleteTopicAsync(entity, cancellationToken);
            }
            else
            {
                await admin.DeleteQueueAsync(entity, cancellationToken);
            }
        }
    }

    [Test]
    [Arguments(false)]
    [Arguments(true)]
    [Timeout(60_000)]
    public async Task ReceiveAndDeleteIncludesBrokerMetadata(bool large, CancellationToken cancellationToken)
    {
        var admin = new ServiceBusAdministrationClient(ConnectionString(administration: true));
        await using var client = new ServiceBusClient(ConnectionString());
        string queue = $"metadata-delete-{Guid.NewGuid():N}";
        await admin.CreateQueueAsync(queue, cancellationToken);
        try
        {
            await using ServiceBusSender sender = client.CreateSender(queue);
            DateTimeOffset beforeSend = DateTimeOffset.UtcNow.AddSeconds(-5);
            string body = large ? new string('x', 200_000) : "delete";
            await sender.SendMessageAsync(new ServiceBusMessage(body), cancellationToken);
            await using ServiceBusReceiver receiver = client.CreateReceiver(queue,
                new ServiceBusReceiverOptions { ReceiveMode = ServiceBusReceiveMode.ReceiveAndDelete });
            ServiceBusReceivedMessage peeked = await receiver.PeekMessageAsync(cancellationToken: cancellationToken);
            ServiceBusReceivedMessage received = await Receive(receiver, cancellationToken);
            await Assert.That(received.DeliveryCount).IsEqualTo(1);
            await CheckMetadata(received, peeked, beforeSend, DateTimeOffset.UtcNow.AddSeconds(5));
            await Assert.That(received.Body.ToString()).IsEqualTo(body);
            await Assert.That(await receiver.ReceiveMessageAsync(TimeSpan.FromMilliseconds(200), cancellationToken)).IsNull();
        }
        finally
        {
            await admin.DeleteQueueAsync(queue, cancellationToken);
        }
    }

    private static async Task CheckUserProperties(ServiceBusReceivedMessage message)
    {
        await Assert.That(message.CorrelationId).IsEqualTo("correlation-" + message.MessageId);
        await Assert.That(message.ContentType).IsEqualTo("text/plain");
        await Assert.That(message.ApplicationProperties["custom-text"]).IsEqualTo("preserved");
        await Assert.That(message.ApplicationProperties["custom-number"]).IsEqualTo(int.Parse(message.MessageId));
    }

    [Test]
    [Arguments(false)]
    [Arguments(true)]
    [Timeout(60_000)]
    public async Task FirstSessionDeliveryAndImmediateDeadLetterStartAtOne(
        bool peekBeforeReceive, CancellationToken cancellationToken)
    {
        var admin = new ServiceBusAdministrationClient(ConnectionString(administration: true));
        await using var client = new ServiceBusClient(ConnectionString());
        string topic = $"delivery-count-{Guid.NewGuid():N}";
        await admin.CreateTopicAsync(topic, cancellationToken);
        try
        {
            await admin.CreateSubscriptionAsync(new CreateSubscriptionOptions(topic, "consumer")
            {
                RequiresSession = true
            }, cancellationToken);
            await using ServiceBusSender sender = client.CreateSender(topic);
            await sender.SendMessageAsync(new ServiceBusMessage("poison") { SessionId = "session" }, cancellationToken);
            await using ServiceBusSessionReceiver receiver = await client.AcceptSessionAsync(
                topic, "consumer", "session", cancellationToken: cancellationToken);
            await receiver.RenewSessionLockAsync(cancellationToken);
            if (peekBeforeReceive)
            {
                await Assert.That(await receiver.PeekMessageAsync(cancellationToken: cancellationToken)).IsNotNull();
            }
            ServiceBusReceivedMessage first = await Receive(receiver, cancellationToken);
            await Assert.That(first.DeliveryCount).IsEqualTo(1);
            await receiver.DeadLetterMessageAsync(first, "poison", cancellationToken: cancellationToken);

            await using ServiceBusReceiver dlq = client.CreateReceiver(topic, "consumer",
                new ServiceBusReceiverOptions { SubQueue = SubQueue.DeadLetter });
            ServiceBusReceivedMessage deadLetter = await Receive(dlq, cancellationToken);
            await Assert.That(deadLetter.DeliveryCount).IsEqualTo(1);
            await Assert.That(deadLetter.DeadLetterReason).IsEqualTo("poison");
            await dlq.CompleteMessageAsync(deadLetter, cancellationToken);
        }
        finally
        {
            await admin.DeleteTopicAsync(topic, cancellationToken);
        }
    }

    private static async Task CheckMetadata(ServiceBusReceivedMessage received, ServiceBusReceivedMessage peeked,
        DateTimeOffset beforeSend, DateTimeOffset afterSend)
    {
        await Assert.That(received.SequenceNumber > 0).IsTrue();
        await Assert.That(received.SequenceNumber).IsEqualTo(peeked.SequenceNumber);
        await Assert.That(received.EnqueuedTime).IsEqualTo(peeked.EnqueuedTime);
        await Assert.That(received.EnqueuedTime >= beforeSend && received.EnqueuedTime <= afterSend).IsTrue();
    }

    private static async Task<ServiceBusReceivedMessage> Receive(ServiceBusReceiver receiver, CancellationToken cancellationToken)
    {
        return await receiver.ReceiveMessageAsync(TimeSpan.FromSeconds(5), cancellationToken)
            ?? throw new InvalidOperationException("Expected Service Bus message");
    }

    private static async Task<ServiceBusReceiver> CreateReceiver(ServiceBusClient client, string entity,
        bool subscription, bool session, CancellationToken cancellationToken)
    {
        if (session)
        {
            return subscription
                ? await client.AcceptSessionAsync(entity, "consumer", "session", cancellationToken: cancellationToken)
                : await client.AcceptSessionAsync(entity, "session", cancellationToken: cancellationToken);
        }
        return subscription ? client.CreateReceiver(entity, "consumer") : client.CreateReceiver(entity);
    }

    private static string ConnectionString(bool administration = false)
    {
        string authority = administration
            ? new Uri(Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577").Authority
            : $"{Environment.GetEnvironmentVariable("SERVICEBUS_HOST") ?? "localhost"}:{Environment.GetEnvironmentVariable("SERVICEBUS_AMQP_PORT") ?? "5673"}";
        return $"Endpoint=sb://{authority};SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=devkey;UseDevelopmentEmulator=true;";
    }
}
