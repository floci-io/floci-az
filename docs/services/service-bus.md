# Service Bus

Azure Service Bus emulation with a **management plane** for entity topology (queues, topics,
subscriptions) over HTTP, and an **AMQP 1.0 data plane** backed by an Apache ActiveMQ Artemis sidecar
managed automatically by floci-az.

| Plane | Protocol | Transport | Port |
|---|---|---|---|
| Management | HTTP | `/{account}-servicebus/` on `:4577` | `4577` |
| Data | AMQP 1.0 | Apache ActiveMQ Artemis sidecar | `5673` (AMQP) / `5674` (AMQPS) |

Entity topology is created dynamically through the management API (or auto-created on first use by
the SDK), and can additionally be **pre-provisioned declaratively** from a `Config.json` file in the
official Service Bus emulator's format — see [Declarative topology](#declarative-topology-configjson).

> **Mocked mode (default).** With `mocked: true` the Artemis sidecar is not started: the management
> API responds, but the AMQP data plane is unavailable. Set `mocked: false` (and expose the AMQP
> ports) to send and receive messages.

## Management plane

Entity CRUD is served over HTTP at `/{account}-servicebus/`:

| Method | Path | Description |
|---|---|---|
| `GET` | `/{account}-servicebus/$Resources/queues` | List queues |
| `PUT` / `DELETE` | `/{account}-servicebus/{queue}` | Create / delete a queue |
| `GET` | `/{account}-servicebus/$Resources/topics` | List topics |
| `PUT` / `DELETE` | `/{account}-servicebus/{topic}` | Create / delete a topic |
| `GET` | `/{account}-servicebus/{topic}/subscriptions` | List subscriptions |
| `PUT` / `GET` / `DELETE` | `/{account}-servicebus/{topic}/subscriptions/{sub}` | Manage a subscription |
| `GET` | `/{account}-servicebus/{topic}/subscriptions/{sub}/rules` | List subscription rules |
| `PUT` / `GET` / `DELETE` | `/{account}-servicebus/{topic}/subscriptions/{sub}/rules/{rule}` | Manage a subscription rule |

## Declarative topology (Config.json)

At startup, floci-az applies a declarative topology file in the
[official Service Bus emulator's `Config.json` format](https://learn.microsoft.com/azure/service-bus-messaging/test-locally-with-service-bus-emulator)
— the same file .NET Aspire's Service Bus hosting integration writes from the AppHost model. The
file location is resolved in order:

1. `floci-az.services.service-bus.topology-file` (env `FLOCI_AZ_SERVICES_SERVICE_BUS_TOPOLOGY_FILE`)
2. `/ServiceBus_Emulator/ConfigFiles/Config.json` — the official emulator's mount path, so a volume
   prepared for the official emulator works unchanged

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    volumes:
      - ./Config.json:/ServiceBus_Emulator/ConfigFiles/Config.json:ro
      - /var/run/docker.sock:/var/run/docker.sock
```

Namespaces, queues, topics, subscriptions, and rules from the file are created through the same
code paths as the management API, before any client connects — useful for apps whose consumers
attach to pre-provisioned entities and never create them. Semantics:

- The first namespace binds the configured AMQP ports (`amqp-port`/`amqp-tls-port`); the official
  emulator supports a single namespace, and additional namespaces get dynamic ports.
- A discovered topology file takes precedence over `start-on-boot`; its first namespace owns the
  configured ports, and no separate `default` namespace is started.
- A subscription with declared `Rules` gets exactly those rules — the implicit `$Default`
  TrueFilter is removed, as in the official emulator. A subscription with no rules keeps `$Default`.
- Rule replacement is atomic. If a reload of an existing subscription contains an invalid rule,
  its previous complete rule set remains active; a new subscription keeps only valid declarations.
- Entity properties honor the same validation as the management API (`LockDuration` ≤ `PT5M`,
  `MaxDeliveryCount` 1–2000, duplicate-detection window `PT20S`–`P7D`).
- `ForwardTo`/`ForwardDeadLetteredMessagesTo` are not emulated and log a warning if present.
- Loading is best-effort: an invalid entity is skipped with an `ERROR` log and the rest of the
  topology still loads; a missing or unparsable file never fails startup.

## Subscription rules and filters

Subscriptions filter which topic messages they receive through named **rules**, matching Azure
semantics:

- Every new subscription starts with the implicit **`$Default`** rule (a `TrueFilter` that accepts
  everything). The usual SDK flow — add your real rule, then delete `$Default` — works as on Azure,
  as does passing a `DefaultRuleDescription` in the subscription create body
  (`CreateSubscriptionAsync(subscriptionOptions, ruleOptions)`).
- **`CorrelationFilter`** — exact-match (case-sensitive) AND-combination over `CorrelationId`,
  `Label`/`Subject`, `SessionId`, and application properties.
- **`SqlFilter`** — SQL92 expressions over application properties and `sys.CorrelationId`,
  `sys.Label`, `sys.Subject`, `sys.SessionId` (including `LIKE`, `IN`, `BETWEEN`, `IS NULL`,
  `EXISTS(prop)`, arithmetic and boolean operators).
- **`TrueFilter`** / **`FalseFilter`** — accept-all / accept-none.
- Multiple rules combine as a logical **OR** and deliver a **single** copy of a matching message.
  A subscription whose rules have all been deleted receives nothing.

Filters compile to [Artemis queue selectors](https://activemq.apache.org/components/artemis/documentation/latest/filter-expressions.html),
so evaluation happens inside the broker at routing time — messages that don't match a
subscription's rules are never routed to it (no delivery-count inflation, no spurious
dead-lettering).

**Emulator deviations from Azure:**

- Filters on `MessageId`, `To`, `ReplyTo`, `ReplyToSessionId`, or `ContentType` (and their `sys.*`
  forms) are **rejected with HTTP 400** — these AMQP fields have no broker-side selector mapping.
- **Rule actions** (`SqlRuleAction`) are stored and echoed back by the management API but are
  **not applied** to delivered messages, and rules with actions do not produce extra message
  copies.
- Property names in filters are case-sensitive and must be valid selector identifiers
  (letters, digits, `_`, `$`). Correlation-filter values typed `int`/`long`/`double`/`boolean`
  etc. compare with their declared type; other non-string types compare as strings.
- Rule changes update the subscription's filter in place (messages already routed to the
  subscription stay, receivers stay attached) and, as on Azure, apply to future messages only.

## Message sessions

Queues and subscriptions created with `RequiresSession` support Azure SDK session receivers.
Set `SessionId` on sent messages, then use a specific-session receiver, an accept-next-session
receiver, or a session processor. The broker translates Azure's AMQP session filter into an
Artemis `JMSXGroupID` selector, which keeps each session on one receiver and preserves FIFO order
within that session. Attach responses include Azure's `com.microsoft:locked-until-utc` property.

Session ownership lasts for the receiver link. Session state and explicit session-lock renewal are
not currently emulated.

Read a session-enabled entity's dead-letter subqueue with an ordinary receiver. Azure SDKs do not
expose session receivers for dead-letter subqueues, and receiving from that subqueue does not
require a session lock.

## Message peeking

Queue and subscription receivers support Azure SDK `peekMessages()` calls through each entity's
AMQP `$management` node. Peeking preserves message bodies, system properties, and application
properties; supports `maxMessages` and `fromSequenceNumber`; and does not lock, remove, or change
delivery count. Each call returns at most Azure's 250-message limit and may return fewer messages
when needed to keep the AMQP response bounded. Empty entities return an empty result.

## Connection String

```
Endpoint=sb://localhost:5673;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=devkey;UseDevelopmentEmulator=true;
```

`UseDevelopmentEmulator=true` tells the SDK to use plain AMQP (no TLS). The `SharedAccessKey` value is
ignored — Artemis runs without authentication in dev mode.

## Deterministic endpoint for orchestrators

The AMQP data plane always binds the configured host ports (`amqp-port`, default `5673`;
`amqp-tls-port`, default `5674`), so an orchestrator that starts floci-az knows the Service Bus
endpoint up front — there is no dynamic port to discover. Two more pieces complete the story:

- **`start-on-boot: true`** starts the `default` namespace (and its Artemis sidecar) together with
  the emulator, instead of on the first entity-management call. Without it, nothing listens on the
  AMQP port until a queue, topic, or namespace is created, which breaks health checks and clients
  that connect at startup.
- **`GET /{account}-servicebus/namespaces`** reports each running namespace's actual
  `amqpPort`/`amqpsPort`, for tooling that wants to verify or discover the endpoint at runtime.

This is what hosting integrations (e.g. .NET Aspire) should rely on: pass
`FLOCI_AZ_SERVICES_SERVICE_BUS_AMQP_PORT` (with `..._MOCKED=false` and `..._START_ON_BOOT=true`),
then hand clients `Endpoint=sb://<host>:<that port>;...;UseDevelopmentEmulator=true;`.

## Python SDK

```python
from azure.servicebus import ServiceBusClient, ServiceBusMessage

CONN = (
    "Endpoint=sb://localhost:5673;"
    "SharedAccessKeyName=RootManageSharedAccessKey;"
    "SharedAccessKey=devkey;"
    "UseDevelopmentEmulator=true;"
)

with ServiceBusClient.from_connection_string(CONN) as client:
    with client.get_queue_sender("myqueue") as sender:
        sender.send_messages(ServiceBusMessage("hello world"))

    with client.get_queue_receiver("myqueue", max_wait_time=5) as receiver:
        for msg in receiver:
            print(str(msg))
            receiver.complete_message(msg)
```

## .NET SDK

```csharp
await using var client = new ServiceBusClient(
    "Endpoint=sb://localhost:5673;SharedAccessKeyName=RootManageSharedAccessKey;" +
    "SharedAccessKey=devkey;UseDevelopmentEmulator=true;");
ServiceBusSender sender = client.CreateSender("myqueue");
await sender.SendMessageAsync(new ServiceBusMessage("hello world"));
```

The Artemis sidecar includes the `MSSBCBS` anonymous SASL mechanism expected by
`Azure.Messaging.ServiceBus`; authorization continues through the standard CBS link.

## Configuration

### Docker Compose

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"   # floci-az HTTP (management plane)
      - "5673:5673"   # Service Bus AMQP (Artemis)
      - "5674:5674"   # Service Bus AMQPS (Artemis)
    environment:
      FLOCI_AZ_SERVICES_SERVICE_BUS_ENABLED: "true"
      FLOCI_AZ_SERVICES_SERVICE_BUS_MOCKED: "false"
      FLOCI_AZ_SERVICES_SERVICE_BUS_START_ON_BOOT: "true"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

### Environment variables

| Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_SERVICE_BUS_ENABLED` | `true` | Enable/disable the service |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_MOCKED` | `true` | Mocked mode (management plane only, no Artemis) |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_START_ON_BOOT` | `false` | Start the `default` namespace with the emulator when no topology file is discovered |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_TOPOLOGY_FILE` | *(unset)* | Path to a declarative topology `Config.json`; when unset, `/ServiceBus_Emulator/ConfigFiles/Config.json` is probed |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_AMQP_PORT` | `5673` | Host port for AMQP (Artemis) |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_AMQP_TLS_PORT` | `5674` | Host port for AMQPS |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_ARTEMIS_IMAGE` | `apache/activemq-artemis:2.44.0` | Artemis image; must match bundled protocol patches |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_MAX_DELIVERY_COUNT` | `10` | Default max delivery attempts before dead-lettering, for entities that do not set `MaxDeliveryCount` |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_LOCK_DURATION_SECONDS` | `60` | Default peek-lock duration, for entities that do not set `LockDuration` |

### application.yml

```yaml
floci-az:
  services:
    service-bus:
      enabled: true
      mocked: true              # true = management plane only, no Docker. false = real Artemis sidecar
      start-on-boot: false      # true = default namespace starts when no topology file is discovered
      amqp-port: 5673
      amqp-tls-port: 5674
      artemis-image: "apache/activemq-artemis:2.44.0"
      max-delivery-count: 10      # default when the entity omits MaxDeliveryCount
      lock-duration-seconds: 60   # default when the entity omits LockDuration
```

### Per-entity delivery settings

Queues and subscriptions honor `MaxDeliveryCount` (1–2000) and `LockDuration`
(up to `PT5M`) from the entity-create payload, matching Azure. A message is
dead-lettered once its delivery count exceeds the entity's `MaxDeliveryCount`.
`LockDuration` is enforced for session-enabled entities (session locks expire
and can be reacquired after the configured duration); non-session peek-lock
expiry is not enforced by the broker. Entities that omit either property fall
back to the configured defaults above.

## Out of scope (future work)

- Session state and explicit session-lock renewal
- Non-session peek-lock expiry enforcement
- Deferred messages and auto-forwarding
- Message transactions
- Geo-disaster recovery and partitioned entities
