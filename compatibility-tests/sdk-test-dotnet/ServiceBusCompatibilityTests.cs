using System.Net.Http.Headers;
using System.Text;
using Azure.Messaging.ServiceBus;
using Xunit;

namespace FlociAz.Compatibility;

public sealed class ServiceBusCompatibilityTests
{
    private static readonly TimeSpan ReceiveTimeout = TimeSpan.FromSeconds(10);
    private static readonly string EmulatorEndpoint =
        Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577";
    private static readonly string ServiceBusHost =
        Environment.GetEnvironmentVariable("SERVICEBUS_HOST") ?? "localhost";
    private static readonly int ServiceBusPort =
        int.Parse(Environment.GetEnvironmentVariable("SERVICEBUS_AMQP_PORT") ?? "5673");

    [Fact]
    public async Task DotnetSdkSupportsSettlementOperations()
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(
            TestContext.Current.CancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(30));
        CancellationToken cancellationToken = timeout.Token;
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
        Assert.Equal("complete", completed.Body.ToString());
        await receiver.CompleteMessageAsync(completed, cancellationToken);

        await sender.SendMessageAsync(new ServiceBusMessage("abandon"), cancellationToken);
        ServiceBusReceivedMessage abandoned = await Receive(receiver, cancellationToken);
        await receiver.AbandonMessageAsync(abandoned, cancellationToken: cancellationToken);
        ServiceBusReceivedMessage redelivered = await Receive(receiver, cancellationToken);
        Assert.Equal("abandon", redelivered.Body.ToString());
        Assert.True(redelivered.DeliveryCount >= 1);
        await receiver.CompleteMessageAsync(redelivered, cancellationToken);

        await sender.SendMessageAsync(new ServiceBusMessage("dead-letter"), cancellationToken);
        ServiceBusReceivedMessage deadLettered = await Receive(receiver, cancellationToken);
        await receiver.DeadLetterMessageAsync(deadLettered, cancellationToken: cancellationToken);

        await using ServiceBusReceiver deadLetterReceiver = client.CreateReceiver(
            queue, new ServiceBusReceiverOptions { SubQueue = SubQueue.DeadLetter });
        ServiceBusReceivedMessage fromDeadLetterQueue = await Receive(deadLetterReceiver, cancellationToken);
        Assert.Equal("dead-letter", fromDeadLetterQueue.Body.ToString());
        await deadLetterReceiver.CompleteMessageAsync(fromDeadLetterQueue, cancellationToken);
    }

    private static async Task<ServiceBusReceivedMessage> Receive(
        ServiceBusReceiver receiver, CancellationToken cancellationToken)
    {
        ServiceBusReceivedMessage? message =
            await receiver.ReceiveMessageAsync(ReceiveTimeout, cancellationToken);
        return Assert.IsType<ServiceBusReceivedMessage>(message);
    }

    private static async Task EnsureNamespace(
        string serviceBusNamespace, CancellationToken cancellationToken)
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(2) };
        using var body = new StringContent("{}", Encoding.UTF8, "application/json");
        HttpResponseMessage response = await http.PutAsync(
            $"{EmulatorEndpoint}/devstoreaccount1-servicebus/namespaces/{serviceBusNamespace}",
            body, cancellationToken);
        Assert.True(response.IsSuccessStatusCode,
            $"Namespace creation failed: {(int)response.StatusCode} {await response.Content.ReadAsStringAsync()}");
    }

    private static async Task EnsureQueue(
        string serviceBusNamespace, string queue, CancellationToken cancellationToken)
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
        using var request = new HttpRequestMessage(HttpMethod.Put,
            $"{EmulatorEndpoint}/devstoreaccount1-servicebus/{serviceBusNamespace}/queues/{queue}");
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/atom+xml"));
        request.Content = new StringContent("", Encoding.UTF8, "application/atom+xml");
        HttpResponseMessage response = await http.SendAsync(request, cancellationToken);
        Assert.True(response.IsSuccessStatusCode,
            $"Queue creation failed: {(int)response.StatusCode} {await response.Content.ReadAsStringAsync()}");
    }
}
