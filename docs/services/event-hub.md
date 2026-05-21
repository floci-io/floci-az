# Event Hubs

Azure Event Hubs emulation backed by two lightweight sidecar containers managed automatically by floci-az:

| Protocol | Sidecar | Port |
|---|---|---|
| AMQP 1.0 | Apache ActiveMQ Artemis | `5672` |
| Kafka (optional) | Redpanda | `9093` |

## Connection String

```
Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=devkey;UseDevelopmentEmulator=true;
```

`UseDevelopmentEmulator=true` tells the SDK to use plain AMQP (no TLS). The `SharedAccessKey` value is ignored — Artemis runs without authentication in dev mode.

## Python SDK

```python
from azure.eventhub import EventHubProducerClient, EventHubConsumerClient

CONNECTION_STR = (
    "Endpoint=sb://localhost;"
    "SharedAccessKeyName=RootManageSharedAccessKey;"
    "SharedAccessKey=devkey;"
    "UseDevelopmentEmulator=true;"
)
EVENTHUB_NAME = "eh1"

# Send events
producer = EventHubProducerClient.from_connection_string(
    conn_str=CONNECTION_STR,
    eventhub_name=EVENTHUB_NAME,
)
with producer:
    batch = producer.create_batch()
    batch.add(EventData("hello world"))
    producer.send_batch(batch)

# Receive events
consumer = EventHubConsumerClient.from_connection_string(
    conn_str=CONNECTION_STR,
    consumer_group="$Default",
    eventhub_name=EVENTHUB_NAME,
)
def on_event(partition_context, event):
    print(event.body_as_str())
    partition_context.update_checkpoint()

with consumer:
    consumer.receive(on_event=on_event, starting_position="-1")
```

## Java SDK

```java
EventHubProducerClient producer = new EventHubClientBuilder()
    .connectionString(
        "Endpoint=sb://localhost;"
        + "SharedAccessKeyName=RootManageSharedAccessKey;"
        + "SharedAccessKey=devkey;"
        + "UseDevelopmentEmulator=true;",
        "eh1")
    .buildProducerClient();

EventDataBatch batch = producer.createBatch();
batch.tryAdd(new EventData("hello world"));
producer.send(batch);
producer.close();
```

## Configuration

### Docker Compose

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"   # floci-az HTTP
    environment:
      FLOCI_AZ_SERVICES_EVENT_HUB_ENABLED: "true"
      FLOCI_AZ_SERVICES_EVENT_HUB_DEFAULT_NAMESPACE: "emulatorNs1"
      FLOCI_AZ_SERVICES_EVENT_HUB_ENTITIES: "eh1:4,eh2:2"
      FLOCI_AZ_SERVICES_EVENT_HUB_KAFKA_ENABLED: "true"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

> **Note:** Do **not** publish the AMQP (`5672`) or Kafka (`9093`) ports on the `floci-az` service.
> Artemis and Redpanda are launched as **sibling containers** by the host Docker daemon (via the
> mounted socket). They bind their ports directly on the host, so `localhost:5672` and
> `localhost:9093` are accessible without any extra mappings. Adding those ports to the `floci-az`
> service would conflict when the sidecar container tries to bind the same port.

### Environment variables

| Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_EVENT_HUB_ENABLED` | `true` | Enable/disable the service |
| `FLOCI_AZ_SERVICES_EVENT_HUB_DEFAULT_NAMESPACE` | `emulatorNs1` | AMQP namespace name |
| `FLOCI_AZ_SERVICES_EVENT_HUB_ENTITIES` | `eh1:4` | Comma-separated `name:partitions` pairs |
| `FLOCI_AZ_SERVICES_EVENT_HUB_AMQP_PORT` | `5672` | Host port for AMQP (Artemis) |
| `FLOCI_AZ_SERVICES_EVENT_HUB_KAFKA_ENABLED` | `false` | Enable Kafka-compatible endpoint |
| `FLOCI_AZ_SERVICES_EVENT_HUB_KAFKA_PORT` | `9093` | Host port for Kafka (Redpanda) |
| `FLOCI_AZ_SERVICES_EVENT_HUB_ARTEMIS_IMAGE` | `apache/activemq-artemis:latest` | Artemis image |
| `FLOCI_AZ_SERVICES_EVENT_HUB_REDPANDA_IMAGE` | `redpandadata/redpanda:latest` | Redpanda image |

### application.yml

```yaml
floci-az:
  services:
    event-hub:
      enabled: true
      default-namespace: emulatorNs1
      entities: "eh1:4"
      amqp-port: 5672
      kafka-enabled: false
      kafka-port: 9093
```

## Multi-Namespace Support

Each Event Hubs namespace gets its own isolated Artemis container with dynamically allocated ports. The default namespace is started automatically from config. Additional namespaces can be created at runtime via the management API.

### Namespace Management API

| Method | Path | Description |
|---|---|---|
| `GET` | `/{account}-eventhub/namespaces` | List all namespaces |
| `PUT` | `/{account}-eventhub/namespaces/{ns}` | Create a new namespace |
| `DELETE` | `/{account}-eventhub/namespaces/{ns}` | Delete a namespace |
| `GET` | `/{account}-eventhub/namespaces/{ns}/connection` | Get connection info (ports) |
| `GET` | `/{account}-eventhub/namespaces/{ns}/tls-cert` | Get TLS certificate PEM |

#### Create a namespace

```bash
curl -X PUT http://localhost:4577/devstoreaccount1-eventhub/namespaces/myns \
  -H 'Content-Type: application/json' \
  -d '{"entities":"eh1:4,eh2:2","consumerGroups":"$Default,my-group"}'
```

Response `201`:
```json
{"name":"myns","amqpPort":47123,"amqpsPort":47124}
```

#### Get connection info

```bash
curl http://localhost:4577/devstoreaccount1-eventhub/namespaces/myns/connection
```

```json
{
  "namespace": "myns",
  "amqpPort": 47123,
  "amqpsPort": 47124,
  "amqpEndpoint": "amqp://localhost:47123",
  "amqpsEndpoint": "amqps://localhost:47124"
}
```

Connect with the Python SDK using the dynamically allocated port:

```python
conn_str = (
    "Endpoint=sb://localhost;"
    "SharedAccessKeyName=RootManageSharedAccessKey;"
    "SharedAccessKey=devkey;"
    "UseDevelopmentEmulator=true;"
)
producer = EventHubProducerClient.from_connection_string(
    conn_str=conn_str,
    eventhub_name="eh1",
)
```

> Note: `UseDevelopmentEmulator=true` bypasses TLS; the SDK connects on the plain AMQP port. To use the dynamic namespace port, construct the connection string with the port explicitly:
> `Endpoint=sb://localhost:47123;...`

## Health Check

```
GET /{account}-eventhub/health
```

Returns `200` when the default namespace AMQP broker is reachable, `503` otherwise:

```json
{"amqp":{"port":5672,"status":"up"},"amqps":{"port":5671,"status":"up"},"kafka":{"enabled":false}}
```

## Consumer Groups

Consumer groups are declared in the `consumer-groups` config (comma-separated). The `$Default` group is always created automatically. Groups are provisioned on every namespace at startup via the Artemis Jolokia API.

## Limitations

- Entity configuration for additional namespaces uses the same defaults as the default namespace unless overridden in the `PUT` body
- Schema Registry and Kafka Capture are out of scope for v1
