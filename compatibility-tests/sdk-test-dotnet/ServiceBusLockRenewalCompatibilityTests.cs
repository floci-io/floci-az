using Azure.Messaging.ServiceBus;
using Azure.Messaging.ServiceBus.Administration;

namespace FlociAz.Compatibility;

[NotInParallel("servicebus-broker")]
public sealed class ServiceBusLockRenewalCompatibilityTests
{
    private static readonly string Endpoint = Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577";
    private static readonly string Host = Environment.GetEnvironmentVariable("SERVICEBUS_HOST") ?? "localhost";
    private static readonly string Port = Environment.GetEnvironmentVariable("SERVICEBUS_AMQP_PORT") ?? "5673";
    private static readonly TimeSpan LockDuration = TimeSpan.FromSeconds(6);

    [Test]
    [Timeout(90_000)]
    public async Task RepeatedRenewalReplenishesManagementLinkCredits(CancellationToken cancellationToken)
    {
        var admin = AdministrationClient();
        string queue = $"dotnet-renew-credits-{Guid.NewGuid():N}";
        await admin.CreateQueueAsync(new CreateQueueOptions(queue) { LockDuration = TimeSpan.FromSeconds(30) }, cancellationToken);
        try
        {
            await using var client = DataClient();
            await using var sender = client.CreateSender(queue);
            await using var receiver = client.CreateReceiver(queue);
            await sender.SendMessageAsync(new ServiceBusMessage("keep renewing"), cancellationToken);
            var message = await receiver.ReceiveMessageAsync(TimeSpan.FromSeconds(10), cancellationToken);
            // Exceed Artemis's initial 1,000 receiver credits on a single management link.
            for (int renewal = 0; renewal < 1_050; renewal++)
            {
                await receiver.RenewMessageLockAsync(message, cancellationToken);
            }
            await receiver.CompleteMessageAsync(message, cancellationToken);
        }
        finally
        {
            await admin.DeleteQueueAsync(queue, cancellationToken);
        }
    }

    [Test]
    [Timeout(60_000)]
    public async Task RenewedMessageCanCompleteAfterOriginalDeadline(CancellationToken cancellationToken)
    {
        var admin = AdministrationClient();
        string queue = $"dotnet-renew-message-{Guid.NewGuid():N}";
        await admin.CreateQueueAsync(new CreateQueueOptions(queue) { LockDuration = LockDuration }, cancellationToken);
        try
        {
            await using var client = DataClient();
            await using var sender = client.CreateSender(queue);
            await using var receiver = client.CreateReceiver(queue);
            await sender.SendMessageAsync(new ServiceBusMessage("renew me"), cancellationToken);
            var message = await receiver.ReceiveMessageAsync(TimeSpan.FromSeconds(10), cancellationToken);
            await Assert.That(message).IsNotNull();
            var originalDeadline = message.LockedUntil;
            await Task.Delay(TimeSpan.FromSeconds(3), cancellationToken);
            await receiver.RenewMessageLockAsync(message, cancellationToken);
            await Assert.That(message.LockedUntil).IsGreaterThan(originalDeadline);
            await Task.Delay(originalDeadline - DateTimeOffset.UtcNow + TimeSpan.FromMilliseconds(500), cancellationToken);
            await receiver.CompleteMessageAsync(message, cancellationToken);
            var counts = await admin.GetQueueRuntimePropertiesAsync(queue, cancellationToken);
            await Assert.That(counts.Value.ActiveMessageCount).IsEqualTo(0);
        }
        finally
        {
            await admin.DeleteQueueAsync(queue, cancellationToken);
        }
    }

    [Test]
    [Timeout(60_000)]
    public async Task RenewedSessionRetainsOwnershipAfterOriginalDeadline(CancellationToken cancellationToken)
    {
        var admin = AdministrationClient();
        string queue = $"dotnet-renew-session-{Guid.NewGuid():N}";
        await admin.CreateQueueAsync(new CreateQueueOptions(queue)
        {
            RequiresSession = true,
            LockDuration = LockDuration
        }, cancellationToken);
        try
        {
            await using var client = DataClient();
            await using var sender = client.CreateSender(queue);
            await sender.SendMessageAsync(new ServiceBusMessage("renew session") { SessionId = "session" }, cancellationToken);
            await using var receiver = await client.AcceptSessionAsync(queue, "session", cancellationToken: cancellationToken);
            var message = await receiver.ReceiveMessageAsync(TimeSpan.FromSeconds(10), cancellationToken);
            var originalDeadline = receiver.SessionLockedUntil;
            await Task.Delay(TimeSpan.FromSeconds(3), cancellationToken);
            await receiver.RenewSessionLockAsync(cancellationToken);
            await Assert.That(receiver.SessionLockedUntil).IsGreaterThan(originalDeadline);
            await Task.Delay(originalDeadline - DateTimeOffset.UtcNow + TimeSpan.FromMilliseconds(500), cancellationToken);
            await receiver.CompleteMessageAsync(message, cancellationToken);
            await receiver.RenewSessionLockAsync(cancellationToken);
        }
        finally
        {
            await admin.DeleteQueueAsync(queue, cancellationToken);
        }
    }

    [Test]
    [Timeout(60_000)]
    public async Task ExpiredRenewedMessageCannotBeRenewedAgain(CancellationToken cancellationToken)
    {
        var admin = AdministrationClient();
        string queue = $"dotnet-renew-expiry-{Guid.NewGuid():N}";
        await admin.CreateQueueAsync(new CreateQueueOptions(queue) { LockDuration = LockDuration }, cancellationToken);
        try
        {
            await using var client = DataClient();
            await using var sender = client.CreateSender(queue);
            await using var receiver = client.CreateReceiver(queue);
            await sender.SendMessageAsync(new ServiceBusMessage("expire me"), cancellationToken);
            var original = await receiver.ReceiveMessageAsync(TimeSpan.FromSeconds(10), cancellationToken);
            await receiver.RenewMessageLockAsync(original, cancellationToken);
            await Task.Delay(original.LockedUntil - DateTimeOffset.UtcNow + TimeSpan.FromMilliseconds(500), cancellationToken);
            await using var second = client.CreateReceiver(queue);
            var redelivered = await second.ReceiveMessageAsync(TimeSpan.FromSeconds(10), cancellationToken);
            await Assert.That(redelivered).IsNotNull();
            await Assert.That(redelivered.DeliveryCount).IsGreaterThan(original.DeliveryCount);
            ServiceBusException? failure = null;
            try
            {
                await receiver.RenewMessageLockAsync(original, cancellationToken);
            }
            catch (ServiceBusException exception)
            {
                failure = exception;
            }
            await Assert.That(failure).IsNotNull();
            await Assert.That(failure!.Reason).IsEqualTo(ServiceBusFailureReason.MessageLockLost);
            await second.CompleteMessageAsync(redelivered, cancellationToken);
        }
        finally
        {
            await admin.DeleteQueueAsync(queue, cancellationToken);
        }
    }

    private static ServiceBusClient DataClient() => new(
        $"Endpoint=sb://{Host}:{Port};SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=devkey;UseDevelopmentEmulator=true;",
        new ServiceBusClientOptions { RetryOptions = new ServiceBusRetryOptions { MaxRetries = 0, TryTimeout = TimeSpan.FromSeconds(10) } });

    private static ServiceBusAdministrationClient AdministrationClient() => new(
        $"Endpoint=sb://{new Uri(Endpoint).Authority};SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=devkey;UseDevelopmentEmulator=true;");
}
