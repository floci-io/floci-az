# Azure SignalR Service

Emulates the Azure SignalR **Default** mode data plane for ASP.NET Core hubs. The real
`Microsoft.Azure.SignalR` SDK connects over WebSockets using the Azure MessagePack service protocol.
Clients negotiate through their application server and connect to floci-az using standard SignalR clients.

## Supported behavior

- Server handshake and keep-alive pings, client negotiation, connection open/close, and bidirectional hub calls.
- JSON and MessagePack hub protocols, including clients using different protocols on the same hub.
- Send to connections, users, groups, and all clients, including connection exclusions.
- Join/leave groups with acknowledgments, with group membership shared across application servers.
- Hub and application-name isolation. Losing an application server connection disconnects its assigned clients.
- Preferred and required server stickiness, using the server identity in SDK-issued client tokens.
- HMAC-SHA256 token signature, audience, and lifetime validation using the configured access key.

Connections and memberships are transient, like live network connections. No sidecar or persistent store is required.

## .NET setup

Install `Microsoft.Azure.SignalR`, then configure the ASP.NET Core application:

```csharp
builder.Services.AddSignalR().AddAzureSignalR(options =>
{
    options.ConnectionString =
        "Endpoint=http://localhost:4577;" +
        "AccessKey=bG9jYWwtc2lnbmFsci1kZXZlbG9wbWVudC1rZXk=;Version=1.0;";
});

app.MapHub<ChatHub>("/chat");
```

SignalR clients connect to the application's `/chat` URL. The SDK handles the redirect to floci-az.
For MessagePack, register `AddMessagePackProtocol()` on both the application and the client.

Use a hostname reachable by both the application servers and their clients. Inside a shared Docker
network this can be `http://floci-az:4577`; clients running on the host need a host-reachable endpoint.
Separate applications can set `options.ApplicationName` to isolate their hubs.

## Endpoints and configuration

The SDK uses `/server/?hub={hub}`, `/client/negotiate?hub={hub}`, and `/client/?hub={hub}` on port 4577.
The local root endpoint uses the `default` account. Hostnames under `.service.signalr.net` identify
separate accounts when those names resolve to the emulator, and that is the way to reach a named
account.

The `-signalr` account suffix is also registered, because every service registers one, but it cannot
carry an SDK connection. The SDK signs its access token with an audience built from the endpoint's
scheme and host only, with no path segment, so a request to `/{account}-signalr/server` presents a
token whose audience does not match the path it arrived on and is rejected with 401. Use the root
endpoint or a `{account}.service.signalr.net` hostname instead.

| Environment variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_SIGNALR_ENABLED` | `true` | Enable the service |
| `FLOCI_AZ_SERVICES_SIGNALR_ACCESS_KEY` | `bG9jYWwtc2lnbmFsci1kZXZlbG9wbWVudC1rZXk=` | AccessKey string shared with the SDK |

## Current limits

Only WebSockets are advertised. Server-Sent Events, long polling, serverless upstreams, REST management,
ARM provisioning, Microsoft Entra authentication, migration, stateful reconnect,
and server-to-client invocations that return results are not implemented. Unsupported service message
types close the server connection explicitly instead of reporting success.

Clients must reconnect after losing their application server. Negotiated connection tokens expire
after 30 seconds. Service protocol messages are limited to 1 MiB; the HTTP server's WebSocket limits also apply.

## Validation and references

The .NET compatibility suite runs real ASP.NET Core hub hosts with `Microsoft.Azure.SignalR` 1.33.1
and JSON/MessagePack clients. It exercises hub calls, groups, users, and broadcasts across application servers.

- [Azure SignalR service protocol](https://github.com/Azure/azure-signalr/blob/dev/specs/ServiceProtocol.md)
- [Service protocol implementation](https://github.com/Azure/azure-signalr/blob/dev/src/Microsoft.Azure.SignalR.Protocols/ServiceProtocol.cs)
