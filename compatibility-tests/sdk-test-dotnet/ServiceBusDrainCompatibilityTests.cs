using Azure.Messaging.ServiceBus.Administration;
using Microsoft.Azure.Amqp;
using Microsoft.Azure.Amqp.Framing;

namespace FlociAz.Compatibility;

public sealed class ServiceBusDrainCompatibilityTests
{
    // The SDK drains a receiver after an empty receive and completes the drain only when the
    // broker's flow frame arrives. A delivery that crosses the request uses the credit first,
    // so the broker sees a drain with no credit left. The raw link asks for exactly that.
    [Test]
    [Timeout(60_000)]
    public async Task DrainWithNoCreditLeftIsAnswered(CancellationToken cancellationToken)
    {
        string endpoint = Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577";
        string host = Environment.GetEnvironmentVariable("SERVICEBUS_HOST") ?? "localhost";
        string port = Environment.GetEnvironmentVariable("SERVICEBUS_AMQP_PORT") ?? "5673";
        var admin = new ServiceBusAdministrationClient(
            $"Endpoint=sb://{new Uri(endpoint).Authority};SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=devkey;UseDevelopmentEmulator=true;");
        string queue = $"dotnet-drain-{Guid.NewGuid():N}";
        await admin.CreateQueueAsync(queue, cancellationToken);
        try
        {
            AmqpConnection connection = await new AmqpConnectionFactory().OpenConnectionAsync(
                new Uri($"amqp://{host}:{port}"), TimeSpan.FromSeconds(10));
            try
            {
                AmqpSession session = connection.CreateSession(new AmqpSessionSettings());
                await session.OpenAsync(cancellationToken);
                var link = new ReceivingAmqpLink(session, new AmqpLinkSettings
                {
                    Role = true, LinkName = queue, Source = new Source { Address = queue }, Target = new Target(),
                    TotalLinkCredit = 0, AutoSendFlow = false, OperationTimeout = TimeSpan.FromSeconds(5)
                });
                await link.OpenAsync(cancellationToken);

                // Throws TimeoutException after OperationTimeout when the broker stays silent.
                await link.DrainAsyc(cancellationToken);
            }
            finally
            {
                await connection.CloseAsync(CancellationToken.None);
            }
        }
        finally
        {
            await admin.DeleteQueueAsync(queue, CancellationToken.None);
        }
    }
}
