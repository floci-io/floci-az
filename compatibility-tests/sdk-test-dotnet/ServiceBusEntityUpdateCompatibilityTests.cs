using Azure.Messaging.ServiceBus;
using Azure.Messaging.ServiceBus.Administration;

namespace FlociAz.Compatibility;

/// <summary>
/// UpdateQueueAsync, UpdateTopicAsync and UpdateSubscriptionAsync change the entity: the response and a later Get
/// show the new values, and the broker applies them to the next delivery.
/// </summary>
[NotInParallel]
public sealed class ServiceBusEntityUpdateCompatibilityTests
{
    [Test]
    [Timeout(60_000)]
    public async Task UpdateQueueChangesItsProperties(CancellationToken cancellationToken)
    {
        var admin = new ServiceBusAdministrationClient(ConnectionString(administration: true));
        string queue = $"update-{Guid.NewGuid():N}";
        QueueProperties created = (await admin.CreateQueueAsync(new CreateQueueOptions(queue)
        {
            MaxDeliveryCount = 3, LockDuration = TimeSpan.FromSeconds(30)
        }, cancellationToken)).Value;
        try
        {
            created.MaxDeliveryCount = 7;
            created.LockDuration = TimeSpan.FromSeconds(50);
            created.DefaultMessageTimeToLive = TimeSpan.FromHours(1);
            created.UserMetadata = "reconciled";

            QueueProperties updated = (await admin.UpdateQueueAsync(created, cancellationToken)).Value;
            QueueProperties read = (await admin.GetQueueAsync(queue, cancellationToken)).Value;

            foreach (QueueProperties properties in new[] { updated, read })
            {
                await Assert.That(properties.MaxDeliveryCount).IsEqualTo(7);
                await Assert.That(properties.LockDuration).IsEqualTo(TimeSpan.FromSeconds(50));
                await Assert.That(properties.DefaultMessageTimeToLive).IsEqualTo(TimeSpan.FromHours(1));
                await Assert.That(properties.UserMetadata).IsEqualTo("reconciled");
            }
        }
        finally
        {
            await admin.DeleteQueueAsync(queue, cancellationToken);
        }
    }

    [Test]
    [Timeout(60_000)]
    public async Task UpdatedLockDurationAndMaxDeliveryCountApplyToTheNextDelivery(CancellationToken cancellationToken)
    {
        var admin = new ServiceBusAdministrationClient(ConnectionString(administration: true));
        await using var client = new ServiceBusClient(ConnectionString());
        string queue = $"update-delivery-{Guid.NewGuid():N}";
        QueueProperties created = (await admin.CreateQueueAsync(new CreateQueueOptions(queue)
        {
            MaxDeliveryCount = 5, LockDuration = TimeSpan.FromSeconds(60)
        }, cancellationToken)).Value;
        try
        {
            created.LockDuration = TimeSpan.FromSeconds(5);
            created.MaxDeliveryCount = 1;
            await admin.UpdateQueueAsync(created, cancellationToken);

            await using ServiceBusSender sender = client.CreateSender(queue);
            await sender.SendMessageAsync(new ServiceBusMessage("updated"), cancellationToken);
            await using ServiceBusReceiver receiver = client.CreateReceiver(queue);
            ServiceBusReceivedMessage message = await receiver.ReceiveMessageAsync(TimeSpan.FromSeconds(10), cancellationToken);
            await Assert.That(message).IsNotNull();
            TimeSpan lockLeft = message.LockedUntil - DateTimeOffset.UtcNow;
            // The updated 5 s lock, not the 60 s it was created with; the floor rejects a lock shorter than asked for.
            await Assert.That(lockLeft).IsGreaterThan(TimeSpan.FromSeconds(2));
            await Assert.That(lockLeft).IsLessThan(TimeSpan.FromSeconds(20));

            // One delivery allowed: abandoning it moves the message to the dead-letter queue.
            await receiver.AbandonMessageAsync(message, cancellationToken: cancellationToken);
            await using ServiceBusReceiver deadLetters = client.CreateReceiver(queue,
                new ServiceBusReceiverOptions { SubQueue = SubQueue.DeadLetter });
            ServiceBusReceivedMessage deadLetter = await deadLetters.ReceiveMessageAsync(TimeSpan.FromSeconds(10), cancellationToken);
            await Assert.That(deadLetter).IsNotNull();
            await Assert.That(deadLetter.Body.ToString()).IsEqualTo("updated");
            await deadLetters.CompleteMessageAsync(deadLetter, cancellationToken);
        }
        finally
        {
            await admin.DeleteQueueAsync(queue, cancellationToken);
        }
    }

    [Test]
    [Timeout(60_000)]
    public async Task UpdateTopicAndSubscriptionChangeTheirProperties(CancellationToken cancellationToken)
    {
        var admin = new ServiceBusAdministrationClient(ConnectionString(administration: true));
        string topic = $"update-topic-{Guid.NewGuid():N}";
        TopicProperties createdTopic = (await admin.CreateTopicAsync(topic, cancellationToken)).Value;
        SubscriptionProperties createdSubscription = (await admin.CreateSubscriptionAsync(
            new CreateSubscriptionOptions(topic, "consumer") { MaxDeliveryCount = 3 }, cancellationToken)).Value;
        try
        {
            createdTopic.DefaultMessageTimeToLive = TimeSpan.FromHours(2);
            createdTopic.MaxSizeInMegabytes = 2048;
            TopicProperties updatedTopic = (await admin.UpdateTopicAsync(createdTopic, cancellationToken)).Value;
            await Assert.That(updatedTopic.DefaultMessageTimeToLive).IsEqualTo(TimeSpan.FromHours(2));
            await Assert.That(updatedTopic.MaxSizeInMegabytes).IsEqualTo(2048);

            createdSubscription.MaxDeliveryCount = 8;
            createdSubscription.LockDuration = TimeSpan.FromSeconds(20);
            SubscriptionProperties updatedSubscription =
                (await admin.UpdateSubscriptionAsync(createdSubscription, cancellationToken)).Value;
            await Assert.That(updatedSubscription.MaxDeliveryCount).IsEqualTo(8);
            await Assert.That(updatedSubscription.LockDuration).IsEqualTo(TimeSpan.FromSeconds(20));

            TopicProperties readTopic = (await admin.GetTopicAsync(topic, cancellationToken)).Value;
            SubscriptionProperties readSubscription =
                (await admin.GetSubscriptionAsync(topic, "consumer", cancellationToken)).Value;
            await Assert.That(readTopic.DefaultMessageTimeToLive).IsEqualTo(TimeSpan.FromHours(2));
            await Assert.That(readTopic.MaxSizeInMegabytes).IsEqualTo(2048);
            await Assert.That(readSubscription.MaxDeliveryCount).IsEqualTo(8);
            await Assert.That(readSubscription.LockDuration).IsEqualTo(TimeSpan.FromSeconds(20));
        }
        finally
        {
            await admin.DeleteTopicAsync(topic, cancellationToken);
        }
    }

    [Test]
    [Timeout(30_000)]
    public async Task UpdatingAMissingQueueIsEntityNotFound(CancellationToken cancellationToken)
    {
        var admin = new ServiceBusAdministrationClient(ConnectionString(administration: true));
        string queue = $"update-missing-{Guid.NewGuid():N}";
        QueueProperties template = (await admin.CreateQueueAsync(queue, cancellationToken)).Value;
        await admin.DeleteQueueAsync(queue, cancellationToken);

        ServiceBusException? failure = null;
        try
        {
            await admin.UpdateQueueAsync(template, cancellationToken);
        }
        catch (ServiceBusException exception)
        {
            failure = exception;
        }
        await Assert.That(failure).IsNotNull();
        await Assert.That(failure!.Reason).IsEqualTo(ServiceBusFailureReason.MessagingEntityNotFound);
    }

    private static string ConnectionString(bool administration = false)
    {
        string authority = administration
            ? new Uri(Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577").Authority
            : $"{Environment.GetEnvironmentVariable("SERVICEBUS_HOST") ?? "localhost"}:{Environment.GetEnvironmentVariable("SERVICEBUS_AMQP_PORT") ?? "5673"}";
        return $"Endpoint=sb://{authority};SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=devkey;UseDevelopmentEmulator=true;";
    }
}
