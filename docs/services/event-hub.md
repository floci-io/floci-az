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

`UseDevelopmentEmulator=true` tells the SDK to use plain AMQP (no TLS). The `SharedAccessKey` value is ignored: Artemis runs without authentication in dev mode.

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

## Reading events

Reading an event does not remove it, as in Azure. Every receiver reads a partition from its own start position (earliest, latest, an offset, a sequence number or an enqueued time) and keeps receiving the events sent after it attached. Two receivers on the same partition and consumer group each get every event, and a new receiver can read the same events again.

An event stays until its hub's retention ends, measured from the time it was enqueued, and then it is gone for every receiver. Set the retention per hub in the entities, for example `eh1:4:PT10M`.

Azure sets a retention in whole hours, one hour or more ([`retentionTimeInHours`](https://github.com/Azure/azure-rest-api-specs/blob/7efab4ca79331ad2ac48eb67fb944d030745918d/specification/eventhub/resource-manager/Microsoft.EventHub/Eventhub/stable/2026-01-01/openapi.json#L6531)). A retention that is not a whole number of hours, such as `PT10M`, is an emulator allowance: it lets a test watch events leave without waiting an hour.

The broker keeps the events of each hub's retention in memory, one copy per consumer group, within its 2 GB heap. Give a hub that takes many events a shorter retention.

At most five receivers read one partition of one consumer group at a time, as in Azure. The sixth is refused with `amqp:resource-limit-exceeded`, which the SDKs report as quota exceeded.

A receiver with an owner level (the `com.microsoft:epoch` link property) owns its partition in its consumer group, as in Azure. It disconnects every receiver with a lower or equal owner level and every receiver without one. While it holds the partition, a receiver with a lower owner level or none is refused. A disconnect and a refusal both carry `amqp:link:stolen`, which the SDKs report as the consumer disconnected. This is how two event processors share the partitions of a hub without reading one partition twice.

## Management requests

floci-az answers these AMQP requests as Azure does:

| Node | Operation | What the SDKs use it for |
|---|---|---|
| `$cbs` | `put-token` | Authorize the connection's links |
| `$management` | `READ` of `com.microsoft:eventhub` | Get the hub's properties and partition ids |

Every other request gets status `501` (Not Implemented), with a status description that names the operation, and the SDK call fails with that status. This includes a `READ` of `com.microsoft:partition`, which the SDKs send to get a partition's properties: Artemis keeps no sequence numbers or offsets for floci-az to report.

## Configuration

### Docker Compose

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"   # floci-az HTTP
      - "5672:5672"   # Event Hubs AMQP (Artemis)
      - "9093:9093"   # Event Hubs Kafka (Redpanda, optional)
    environment:
      FLOCI_AZ_SERVICES_EVENT_HUB_ENABLED: "true"
      FLOCI_AZ_SERVICES_EVENT_HUB_DEFAULT_NAMESPACE: "emulatorNs1"
      FLOCI_AZ_SERVICES_EVENT_HUB_ENTITIES: "eh1:4,eh2:2"
      FLOCI_AZ_SERVICES_EVENT_HUB_KAFKA_ENABLED: "true"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

### Environment variables

| Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_EVENT_HUB_ENABLED` | `true` | Enable/disable the service |
| `FLOCI_AZ_SERVICES_EVENT_HUB_DEFAULT_NAMESPACE` | `emulatorNs1` | AMQP namespace name |
| `FLOCI_AZ_SERVICES_EVENT_HUB_ENTITIES` | `eh1:4` | Comma-separated `name:partitions[:retention]` entries. The retention is an ISO-8601 duration such as `PT10M`; without one a hub keeps events for `PT1H`, Azure's default, and the longest allowed is `P90D` |
| `FLOCI_AZ_SERVICES_EVENT_HUB_AMQP_PORT` | `5672` | Host port for AMQP (Artemis) |
| `FLOCI_AZ_SERVICES_EVENT_HUB_KAFKA_ENABLED` | `false` | Enable Kafka-compatible endpoint |
| `FLOCI_AZ_SERVICES_EVENT_HUB_KAFKA_PORT` | `9093` | Host port for Kafka (Redpanda). Redpanda advertises it on `localhost`, or on the Docker daemon's host with `FLOCI_AZ_DOCKER_ENDPOINT_MODE=published` |
| `FLOCI_AZ_SERVICES_EVENT_HUB_ARTEMIS_IMAGE` | `apache/activemq-artemis:2.44.0` | Artemis image; must match the Artemis version floci-az was built against, because the patched AMQP jars it copies in are version-specific |
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
{"name":"myns","amqpPort":47123,"amqpsPort":47124,"amqpHostPort":47123,"amqpsHostPort":47124,"mocked":false}
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
  "amqpHostPort": 47123,
  "amqpsHostPort": 47124,
  "amqpEndpoint": "amqp://localhost:47123",
  "amqpsEndpoint": "amqps://localhost:47124"
}
```

`amqpEndpoint` and `amqpsEndpoint` name `localhost`, or the Docker daemon's host in published endpoint mode (`floci-az.docker.endpoint-mode=published`), with the host ports Docker published (`amqpHostPort`/`amqpsHostPort`). The broker certificate covers both hosts. `amqpPort`/`amqpsPort` are the ports floci-az itself reaches the broker on: the same host ports when floci-az runs on the host or in published mode, but the broker's container ports (`5672`/`5671`) when floci-az runs in a container, for clients on the same Docker network.

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

Returns `200` when a namespace's AMQP broker is reachable, `503` otherwise. The probe goes to the broker where floci-az reaches it (the default namespace's broker when one is running): `localhost` when floci-az runs on the host, the broker container's IP when floci-az itself runs in a container, the Docker daemon's host with `FLOCI_AZ_DOCKER_ENDPOINT_MODE=published`. With no broker running it probes the configured ports on `localhost`. The `port` fields report the configured ports:

```json
{"amqp":{"port":5672,"status":"up"},"amqps":{"port":5671,"status":"up"},"kafka":{"enabled":false}}
```

## Consumer Groups

Consumer groups are declared in the `consumer-groups` config (comma-separated). The `$Default` group is always created automatically. Groups are provisioned on every namespace at startup via the Artemis Jolokia API.

## Limitations

- Entity configuration for additional namespaces uses the same defaults as the default namespace unless overridden in the `PUT` body
- Schema Registry and Kafka Capture are out of scope for v1
