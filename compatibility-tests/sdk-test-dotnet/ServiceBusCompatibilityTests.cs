using System.Collections.Concurrent;
using System.Net.Http.Headers;
using System.Text;
using Azure.Messaging.ServiceBus;

namespace FlociAz.Compatibility;

[NotInParallel]
public sealed class ServiceBusCompatibilityTests
{
    private static readonly TimeSpan ReceiveTimeout = TimeSpan.FromSeconds(10);
    private static readonly string EmulatorEndpoint =
        Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577";
    private static readonly string ServiceBusHost =
        Environment.GetEnvironmentVariable("SERVICEBUS_HOST") ?? "localhost";
    private static readonly int ServiceBusPort =
        int.Parse(Environment.GetEnvironmentVariable("SERVICEBUS_AMQP_PORT") ?? "5673");

    [Test]
    [Timeout(60_000)]
    public async Task DotnetSdkSupportsSettlementOperations(CancellationToken cancellationToken)
    {
        const string serviceBusNamespace = "default";
        string queue = $"dotnet-{Guid.NewGuid():N}";
        await EnsureNamespace(serviceBusNamespace, cancellationToken);
        await EnsureQueue(serviceBusNamespace, queue, cancellationToken);

        string connectionString =
            $"Endpoint=sb://{ServiceBusHost}:{ServiceBusPort};" +
            "SharedAccessKeyName=RootManageSharedAccessKey;" +
            "SharedAccessKey=devkey;UseDevelopmentEmulator=true;";

        await using var client = new ServiceBusClient(connectionString, new ServiceBusClientOptions
        {
            RetryOptions = { TryTimeout = TimeSpan.FromSeconds(10) }
        });
        await using ServiceBusSender sender = client.CreateSender(queue);
        await using ServiceBusReceiver receiver = client.CreateReceiver(queue);

        await sender.SendMessageAsync(new ServiceBusMessage("complete"), cancellationToken);
        ServiceBusReceivedMessage completed = await Receive(receiver, cancellationToken);
        await Assert.That(completed.Body.ToString()).IsEqualTo("complete");
        await receiver.CompleteMessageAsync(completed, cancellationToken);

        await sender.SendMessageAsync(new ServiceBusMessage("abandon"), cancellationToken);
        ServiceBusReceivedMessage abandoned = await Receive(receiver, cancellationToken);
        await receiver.AbandonMessageAsync(abandoned, cancellationToken: cancellationToken);
        ServiceBusReceivedMessage redelivered = await Receive(receiver, cancellationToken);
        await Assert.That(redelivered.Body.ToString()).IsEqualTo("abandon");
        await Assert.That(redelivered.DeliveryCount).IsGreaterThanOrEqualTo(1);
        await receiver.CompleteMessageAsync(redelivered, cancellationToken);

        await sender.SendMessageAsync(new ServiceBusMessage("dead-letter"), cancellationToken);
        ServiceBusReceivedMessage deadLettered = await Receive(receiver, cancellationToken);
        await receiver.DeadLetterMessageAsync(deadLettered, cancellationToken: cancellationToken);

        await using ServiceBusReceiver deadLetterReceiver = client.CreateReceiver(
            queue, new ServiceBusReceiverOptions { SubQueue = SubQueue.DeadLetter });
        ServiceBusReceivedMessage fromDeadLetterQueue = await Receive(deadLetterReceiver, cancellationToken);
        await Assert.That(fromDeadLetterQueue.Body.ToString()).IsEqualTo("dead-letter");
        await deadLetterReceiver.CompleteMessageAsync(fromDeadLetterQueue, cancellationToken);

        string topic = $"dotnet-topic-{Guid.NewGuid():N}";
        string subscription = $"dotnet-sub-{Guid.NewGuid():N}";
        await EnsureTopic(serviceBusNamespace, topic, cancellationToken);
        await EnsureSubscription(serviceBusNamespace, topic, subscription, cancellationToken);

        await using ServiceBusSender topicSender = client.CreateSender(topic);
        await using ServiceBusReceiver subscriptionReceiver = client.CreateReceiver(topic, subscription);
        await topicSender.SendMessageAsync(new ServiceBusMessage("subscription-dead-letter"), cancellationToken);
        ServiceBusReceivedMessage subscriptionMessage = await Receive(subscriptionReceiver, cancellationToken);
        await subscriptionReceiver.DeadLetterMessageAsync(
            subscriptionMessage, cancellationToken: cancellationToken);

        await using ServiceBusReceiver subscriptionDeadLetterReceiver = client.CreateReceiver(
            topic, subscription, new ServiceBusReceiverOptions { SubQueue = SubQueue.DeadLetter });
        ServiceBusReceivedMessage subscriptionDeadLetter =
            await Receive(subscriptionDeadLetterReceiver, cancellationToken);
        await Assert.That(subscriptionDeadLetter.Body.ToString())
            .IsEqualTo("subscription-dead-letter");
        await subscriptionDeadLetterReceiver.CompleteMessageAsync(
            subscriptionDeadLetter, cancellationToken);
    }

    [Test]
    [Timeout(60_000)]
    public async Task DotnetSdkSupportsSessionReceivers(CancellationToken cancellationToken)
    {
        const string serviceBusNamespace = "default";
        string queue = $"dotnet-session-{Guid.NewGuid():N}";
        await EnsureNamespace(serviceBusNamespace, cancellationToken);
        await EnsureQueue(serviceBusNamespace, queue, cancellationToken, requiresSession: true);

        string connectionString =
            $"Endpoint=sb://{ServiceBusHost}:{ServiceBusPort};" +
            "SharedAccessKeyName=RootManageSharedAccessKey;" +
            "SharedAccessKey=devkey;UseDevelopmentEmulator=true;";

        await using var client = new ServiceBusClient(connectionString, new ServiceBusClientOptions
        {
            RetryOptions = { TryTimeout = TimeSpan.FromSeconds(10) }
        });
        await using ServiceBusSender sender = client.CreateSender(queue);

        string firstSession = $"session-a-{Guid.NewGuid():N}";
        string secondSession = $"session-b-{Guid.NewGuid():N}";
        await sender.SendMessageAsync(
            new ServiceBusMessage("a-0") { SessionId = firstSession }, cancellationToken);
        await sender.SendMessageAsync(
            new ServiceBusMessage("a-1") { SessionId = firstSession }, cancellationToken);
        await sender.SendMessageAsync(
            new ServiceBusMessage("b-0") { SessionId = secondSession }, cancellationToken);

        await using (ServiceBusSessionReceiver firstReceiver = await client.AcceptNextSessionAsync(
            queue, new ServiceBusSessionReceiverOptions(), cancellationToken))
        {
            await Assert.That(firstReceiver.SessionId).IsEqualTo(firstSession);
            IReadOnlyList<ServiceBusReceivedMessage> firstMessages = await firstReceiver.ReceiveMessagesAsync(
                2, ReceiveTimeout, cancellationToken);
            await Assert.That(firstMessages.Count).IsEqualTo(2);
            await Assert.That(firstMessages[0].Body.ToString()).IsEqualTo("a-0");
            await Assert.That(firstMessages[1].Body.ToString()).IsEqualTo("a-1");
            foreach (ServiceBusReceivedMessage message in firstMessages)
            {
                await firstReceiver.CompleteMessageAsync(message, cancellationToken);
            }
        }

        await using (ServiceBusSessionReceiver secondReceiver = await client.AcceptSessionAsync(
            queue, secondSession, new ServiceBusSessionReceiverOptions(), cancellationToken))
        {
            ServiceBusReceivedMessage secondMessage = await Receive(secondReceiver, cancellationToken);
            await Assert.That(secondMessage.SessionId).IsEqualTo(secondSession);
            await Assert.That(secondMessage.Body.ToString()).IsEqualTo("b-0");
            await secondReceiver.CompleteMessageAsync(secondMessage, cancellationToken);
        }

        string processorSessionA = $"session-processor-a-{Guid.NewGuid():N}";
        string processorSessionB = $"session-processor-b-{Guid.NewGuid():N}";
        await sender.SendMessageAsync(
            new ServiceBusMessage("processor-a") { SessionId = processorSessionA }, cancellationToken);
        await sender.SendMessageAsync(
            new ServiceBusMessage("processor-b") { SessionId = processorSessionB }, cancellationToken);
        var processedMessages = new ConcurrentDictionary<string, string>();
        var processingComplete = new TaskCompletionSource(
            TaskCreationOptions.RunContinuationsAsynchronously);
        await using ServiceBusSessionProcessor processor = client.CreateSessionProcessor(
            queue,
            new ServiceBusSessionProcessorOptions
            {
                AutoCompleteMessages = false,
                MaxConcurrentSessions = 2,
                MaxConcurrentCallsPerSession = 1
            });
        processor.ProcessMessageAsync += async args =>
        {
            await args.CompleteMessageAsync(args.Message, cancellationToken);
            processedMessages[args.SessionId] = args.Message.Body.ToString();
            if (processedMessages.Count == 2)
            {
                processingComplete.TrySetResult();
            }
        };
        processor.ProcessErrorAsync += args =>
        {
            processingComplete.TrySetException(args.Exception);
            return Task.CompletedTask;
        };

        await processor.StartProcessingAsync(cancellationToken);
        await processingComplete.Task.WaitAsync(ReceiveTimeout, cancellationToken);
        await processor.StopProcessingAsync(cancellationToken);
        await Assert.That(processedMessages[processorSessionA]).IsEqualTo("processor-a");
        await Assert.That(processedMessages[processorSessionB]).IsEqualTo("processor-b");
    }

    private static async Task<ServiceBusReceivedMessage> Receive(
        ServiceBusReceiver receiver, CancellationToken cancellationToken)
    {
        ServiceBusReceivedMessage? message =
            await receiver.ReceiveMessageAsync(ReceiveTimeout, cancellationToken);
        await Assert.That(message).IsNotNull();
        return message!;
    }

    private static async Task EnsureNamespace(
        string serviceBusNamespace, CancellationToken cancellationToken)
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(2) };
        using var body = new StringContent("{}", Encoding.UTF8, "application/json");
        HttpResponseMessage response = await http.PutAsync(
            $"{EmulatorEndpoint}/devstoreaccount1-servicebus/namespaces/{serviceBusNamespace}",
            body, cancellationToken);
        await Assert.That(response.IsSuccessStatusCode)
            .IsTrue()
            .Because($"Namespace creation failed: {(int)response.StatusCode} {await response.Content.ReadAsStringAsync(cancellationToken)}");
    }

    private static async Task EnsureQueue(
        string serviceBusNamespace, string queue, CancellationToken cancellationToken,
        bool requiresSession = false)
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
        using var request = new HttpRequestMessage(HttpMethod.Put,
            $"{EmulatorEndpoint}/devstoreaccount1-servicebus/{serviceBusNamespace}/queues/{queue}");
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/atom+xml"));
        string body = requiresSession
            ? "<entry xmlns=\"http://www.w3.org/2005/Atom\"><content type=\"application/xml\">" +
              "<QueueDescription xmlns=\"http://schemas.microsoft.com/netservices/2010/10/servicebus/connect\">" +
              "<RequiresSession>true</RequiresSession></QueueDescription></content></entry>"
            : "";
        request.Content = new StringContent(body, Encoding.UTF8, "application/atom+xml");
        HttpResponseMessage response = await http.SendAsync(request, cancellationToken);
        await Assert.That(response.IsSuccessStatusCode)
            .IsTrue()
            .Because($"Queue creation failed: {(int)response.StatusCode} {await response.Content.ReadAsStringAsync(cancellationToken)}");
    }

    private static async Task EnsureTopic(
        string serviceBusNamespace, string topic, CancellationToken cancellationToken)
    {
        await EnsureEntity(
            $"{EmulatorEndpoint}/devstoreaccount1-servicebus/{serviceBusNamespace}/topics/{topic}",
            "Topic", cancellationToken);
    }

    private static async Task EnsureSubscription(
        string serviceBusNamespace, string topic, string subscription,
        CancellationToken cancellationToken)
    {
        await EnsureEntity(
            $"{EmulatorEndpoint}/devstoreaccount1-servicebus/{serviceBusNamespace}/topics/{topic}/subscriptions/{subscription}",
            "Subscription", cancellationToken);
    }

    private static async Task EnsureEntity(
        string url, string entityType, CancellationToken cancellationToken)
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
        using var request = new HttpRequestMessage(HttpMethod.Put, url);
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/atom+xml"));
        request.Content = new StringContent("", Encoding.UTF8, "application/atom+xml");
        HttpResponseMessage response = await http.SendAsync(request, cancellationToken);
        await Assert.That(response.IsSuccessStatusCode)
            .IsTrue()
            .Because($"{entityType} creation failed: {(int)response.StatusCode} {await response.Content.ReadAsStringAsync(cancellationToken)}");
    }
}
