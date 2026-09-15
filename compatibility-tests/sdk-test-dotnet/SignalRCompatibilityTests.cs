using System.Security.Claims;
using System.Threading.Channels;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.SignalR;
using Microsoft.AspNetCore.SignalR.Client;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Azure.SignalR;

namespace FlociAz.Compatibility;

public sealed class SignalRCompatibilityTests
{
    private static readonly string Endpoint = Environment.GetEnvironmentVariable("FLOCI_AZ_ENDPOINT") ?? "http://localhost:4577";
    private const string Key = "bG9jYWwtc2lnbmFsci1kZXZlbG9wbWVudC1rZXk=";

    [Test]
    [Timeout(90_000)]
    public async Task DefaultModeRelaysHubsGroupsUsersAndMessagePack(CancellationToken cancellationToken)
    {
        string application = "compat" + Guid.NewGuid().ToString("N");
        await using var first = await StartServer(application, cancellationToken);
        await using var second = await StartServer(application, cancellationToken);
        await using var isolated = await StartServer(application + "isolated", cancellationToken);
        await using var alice = Client(first, "alice", false);
        await using var bob = Client(second, "bob", true);
        await using var isolatedAlice = Client(isolated, "alice", false);
        var aliceMessages = Channel.CreateUnbounded<string>();
        var bobMessages = Channel.CreateUnbounded<string>();
        var isolatedMessages = Channel.CreateUnbounded<string>();
        alice.On<string>("message", value => aliceMessages.Writer.TryWrite(value));
        bob.On<string>("message", value => bobMessages.Writer.TryWrite(value));
        isolatedAlice.On<string>("message", value => isolatedMessages.Writer.TryWrite(value));
        await Connect(alice, cancellationToken);
        await Connect(bob, cancellationToken);
        await Connect(isolatedAlice, cancellationToken);
        await Assert.That(await alice.InvokeAsync<string>("Server", cancellationToken)).IsEqualTo(first.Services.GetRequiredService<HostIdentity>().Value);
        await Assert.That(await bob.InvokeAsync<string>("Server", cancellationToken)).IsEqualTo(second.Services.GetRequiredService<HostIdentity>().Value);
        await Assert.That(await alice.InvokeAsync<string>("Echo", "hello", cancellationToken)).IsEqualTo("hello");
        await Assert.That(await bob.InvokeAsync<string>("Echo", "binary", cancellationToken)).IsEqualTo("binary");
        await alice.InvokeAsync("Join", "friends", cancellationToken);
        await bob.InvokeAsync("Join", "friends", cancellationToken);
        await isolatedAlice.InvokeAsync("Join", "friends", cancellationToken);
        var hub = first.Services.GetRequiredService<IHubContext<CompatibilityHub>>();
        await hub.Clients.Group("friends").SendAsync("message", "group", cancellationToken);
        await Assert.That(await Read(aliceMessages, cancellationToken)).IsEqualTo("group");
        await Assert.That(await Read(bobMessages, cancellationToken)).IsEqualTo("group");
        await alice.InvokeAsync("Leave", "friends", cancellationToken);
        await hub.Clients.Group("friends").SendAsync("message", "bob-only", cancellationToken);
        await Assert.That(await Read(bobMessages, cancellationToken)).IsEqualTo("bob-only");
        await hub.Clients.User("alice").SendAsync("message", "private", cancellationToken);
        await Assert.That(await Read(aliceMessages, cancellationToken)).IsEqualTo("private");
        await hub.Clients.All.SendAsync("message", "everyone", cancellationToken);
        await Assert.That(await Read(aliceMessages, cancellationToken)).IsEqualTo("everyone");
        await Assert.That(await Read(bobMessages, cancellationToken)).IsEqualTo("everyone");
        var isolatedHub = isolated.Services.GetRequiredService<IHubContext<CompatibilityHub>>();
        await isolatedHub.Clients.User("alice").SendAsync("message", "isolated", cancellationToken);
        await Assert.That(await Read(isolatedMessages, cancellationToken)).IsEqualTo("isolated");
        await alice.StopAsync(cancellationToken);
        await Connect(alice, cancellationToken);
        await Assert.That(await alice.InvokeAsync<string>("Echo", "reconnected", cancellationToken)).IsEqualTo("reconnected");
        // Cross the SDK's server timeout with no hub traffic; service pings must keep the relay alive.
        await Task.Delay(TimeSpan.FromSeconds(35), cancellationToken);
        await Assert.That(await bob.InvokeAsync<string>("Echo", "still connected", cancellationToken)).IsEqualTo("still connected");
    }

    public sealed record HostIdentity(string Value);

    public sealed class CompatibilityHub(HostIdentity host) : Hub
    {
        public string Server() => host.Value;
        public string Echo(string value) => value;
        public Task Join(string group) => Groups.AddToGroupAsync(Context.ConnectionId, group);
        public Task Leave(string group) => Groups.RemoveFromGroupAsync(Context.ConnectionId, group);
    }

    private static async Task<WebApplication> StartServer(string application, CancellationToken cancellationToken)
    {
        var builder = WebApplication.CreateBuilder();
        builder.Services.AddSingleton(new HostIdentity(Guid.NewGuid().ToString("N")));
        builder.WebHost.UseUrls("http://127.0.0.1:0");
        builder.Services.AddSignalR().AddMessagePackProtocol().AddAzureSignalR(options =>
        {
            options.ConnectionString = $"Endpoint={Endpoint};AccessKey={Key};Version=1.0;";
            options.ApplicationName = application;
            options.InitialHubServerConnectionCount = 2;
            options.ServerStickyMode = ServerStickyMode.Preferred;
        });
        var app = builder.Build();
        app.Use(async (context, next) =>
        {
            context.User = new ClaimsPrincipal(new ClaimsIdentity(
                [new Claim(ClaimTypes.NameIdentifier, context.Request.Query["user"].ToString())], "compatibility"));
            await next(context);
        });
        app.MapHub<CompatibilityHub>("/hub");
        await app.StartAsync(cancellationToken);
        return app;
    }

    private static HubConnection Client(WebApplication server, string user, bool messagePack)
    {
        string address = server.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.Single();
        var builder = new HubConnectionBuilder().WithUrl($"{address}/hub?user={user}");
        if (messagePack)
        {
            builder.AddMessagePackProtocol();
        }
        return builder.Build();
    }

    private static async Task Connect(HubConnection client, CancellationToken cancellationToken)
    {
        using var startup = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        startup.CancelAfter(TimeSpan.FromSeconds(25));
        while (true)
        {
            try { await client.StartAsync(startup.Token); return; }
            catch (HttpRequestException) when (!startup.IsCancellationRequested)
            {
                await Task.Delay(200, startup.Token);
            }
        }
    }

    private static async Task<string> Read(Channel<string> channel, CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(10));
        return await channel.Reader.ReadAsync(timeout.Token);
    }
}
