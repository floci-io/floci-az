using Azure.Messaging.ServiceBus;
using Azure.Messaging.ServiceBus.Administration;

namespace FlociAz.Compatibility;

// Service Bus compares queue, topic and subscription paths ignoring case, so a client that spells
// an entity differently from how it was created still reaches it.
[NotInParallel("servicebus-broker")]
public sealed class ServiceBusEntityNameCaseCompatibilityTests
{
    private static readonly TimeSpan ReceiveTimeout = TimeSpan.FromSeconds(10);
    private static readonly string EmulatorEndpoint =
        Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577";
    private static readonly string ServiceBusHost =
        Environment.GetEnvironmentVariable("SERVICEBUS_HOST") ?? "localhost";
    private static readonly int ServiceBusPort =
        int.Parse(Environment.GetEnvironmentVariable("SERVICEBUS_AMQP_PORT") ?? "5673");

    [Test]
    [Timeout(90_000)]
    public async Task QueueAcceptsAnySpellingOfItsName(CancellationToken cancellationToken)
    {
        var admin = new ServiceBusAdministrationClient(AdministrationConnectionString());
        string queue = $"dotnet-case-{Guid.NewGuid():N}";
        await admin.CreateQueueAsync(queue, cancellationToken);
        try
        {
            await using var client = new ServiceBusClient(DataPlaneConnectionString());
            await using ServiceBusSender sender = client.CreateSender(queue.ToUpperInvariant());
            await sender.SendMessageAsync(new ServiceBusMessage("upper"), cancellationToken);

            await using ServiceBusReceiver peeker = client.CreateReceiver(MixedCase(queue));
            ServiceBusReceivedMessage? peeked = await peeker.PeekMessageAsync(
                cancellationToken: cancellationToken);
            await Assert.That(peeked).IsNotNull();
            await Assert.That(peeked!.Body.ToString()).IsEqualTo("upper");

            await using ServiceBusReceiver receiver = client.CreateReceiver(queue);
            ServiceBusReceivedMessage? received = await receiver.ReceiveMessageAsync(
                ReceiveTimeout, cancellationToken);
            await Assert.That(received).IsNotNull();
            await Assert.That(received!.Body.ToString()).IsEqualTo("upper");
            await receiver.DeadLetterMessageAsync(received, cancellationToken: cancellationToken);

            await using ServiceBusReceiver deadLetterReceiver = client.CreateReceiver(
                queue.ToUpperInvariant(),
                new ServiceBusReceiverOptions { SubQueue = SubQueue.DeadLetter });
            ServiceBusReceivedMessage? deadLettered = await deadLetterReceiver.ReceiveMessageAsync(
                ReceiveTimeout, cancellationToken);
            await Assert.That(deadLettered).IsNotNull();
            await Assert.That(deadLettered!.Body.ToString()).IsEqualTo("upper");
            await deadLetterReceiver.CompleteMessageAsync(deadLettered, cancellationToken);
        }
        finally
        {
            await admin.DeleteQueueAsync(queue, cancellationToken);
        }
    }

    [Test]
    [Timeout(90_000)]
    public async Task TopicAndSubscriptionAcceptAnySpellingOfTheirNames(CancellationToken cancellationToken)
    {
        var admin = new ServiceBusAdministrationClient(AdministrationConnectionString());
        string topic = $"dotnet-case-topic-{Guid.NewGuid():N}";
        string subscription = "Worker";
        await admin.CreateTopicAsync(topic, cancellationToken);
        try
        {
            await admin.CreateSubscriptionAsync(topic, subscription, cancellationToken);

            await using var client = new ServiceBusClient(DataPlaneConnectionString());
            await using ServiceBusSender sender = client.CreateSender(topic.ToUpperInvariant());
            await sender.SendMessageAsync(new ServiceBusMessage("fan-out"), cancellationToken);

            await using ServiceBusReceiver receiver = client.CreateReceiver(
                MixedCase(topic), subscription.ToLowerInvariant());
            ServiceBusReceivedMessage? received = await receiver.ReceiveMessageAsync(
                ReceiveTimeout, cancellationToken);
            await Assert.That(received).IsNotNull();
            await Assert.That(received!.Body.ToString()).IsEqualTo("fan-out");
            await receiver.CompleteMessageAsync(received, cancellationToken);
        }
        finally
        {
            await admin.DeleteTopicAsync(topic, cancellationToken);
        }
    }

    // Alternates the case of each letter, so the spelling matches neither the created name nor its upper case.
    private static string MixedCase(string name) =>
        string.Concat(name.Select((c, i) => i % 2 == 0 ? char.ToUpperInvariant(c) : c));

    private static string DataPlaneConnectionString() =>
        $"Endpoint=sb://{ServiceBusHost}:{ServiceBusPort};" +
        "SharedAccessKeyName=RootManageSharedAccessKey;" +
        "SharedAccessKey=devkey;UseDevelopmentEmulator=true;";

    private static string AdministrationConnectionString() =>
        $"Endpoint=sb://{new Uri(EmulatorEndpoint).Authority};" +
        "SharedAccessKeyName=RootManageSharedAccessKey;" +
        "SharedAccessKey=devkey;UseDevelopmentEmulator=true;";
}
