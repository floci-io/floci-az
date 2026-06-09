# Service Bus

Azure Service Bus emulation with a **management plane** for entity topology (queues, topics,
subscriptions) over HTTP, and an **AMQP 1.0 data plane** backed by an Apache ActiveMQ Artemis sidecar
managed automatically by floci-az.

| Plane | Protocol | Transport | Port |
|---|---|---|---|
| Management | HTTP | `/{account}-servicebus/` on `:4577` | `4577` |
| Data | AMQP 1.0 | Apache ActiveMQ Artemis sidecar | `5673` (AMQP) / `5674` (AMQPS) |

Unlike Event Hubs, entity topology is **not** pre-configured — queues, topics and subscriptions are
created dynamically through the management API (or auto-created on first use by the SDK).

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

## Connection String

```
Endpoint=sb://localhost:5673;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=devkey;UseDevelopmentEmulator=true;
```

`UseDevelopmentEmulator=true` tells the SDK to use plain AMQP (no TLS). The `SharedAccessKey` value is
ignored — Artemis runs without authentication in dev mode.

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
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

### Environment variables

| Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_SERVICE_BUS_ENABLED` | `true` | Enable/disable the service |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_MOCKED` | `true` | Mocked mode (management plane only, no Artemis) |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_AMQP_PORT` | `5673` | Host port for AMQP (Artemis) |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_AMQP_TLS_PORT` | `5674` | Host port for AMQPS |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_ARTEMIS_IMAGE` | `apache/activemq-artemis:latest` | Artemis image |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_MAX_DELIVERY_COUNT` | `10` | Max delivery attempts before dead-lettering |
| `FLOCI_AZ_SERVICES_SERVICE_BUS_LOCK_DURATION_SECONDS` | `60` | Peek-lock duration |

### application.yml

```yaml
floci-az:
  services:
    service-bus:
      enabled: true
      mocked: true              # true = management plane only, no Docker. false = real Artemis sidecar
      amqp-port: 5673
      amqp-tls-port: 5674
      artemis-image: "apache/activemq-artemis:latest"
      max-delivery-count: 10
      lock-duration-seconds: 60
```

## Out of scope (future work)

- Sessions, scheduled/deferred messages, and auto-forwarding
- Duplicate detection and message transactions
- Geo-disaster recovery and partitioned entities
