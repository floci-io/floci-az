# Event Grid

Compatible with the `azure-messaging-eventgrid` publisher SDK, the
`azure-resourcemanager-eventgrid` / ARM management plane, and any HTTP client. Event Grid is the
Azure counterpart of an event router (publish/subscribe with webhook fan-out): a **Custom Topic**
receives events and pushes them to subscriber **webhook** endpoints.

> **HTTP-only: no Docker.** Topics, subscriptions, publishing, and delivery are all in-process.
> There is no sidecar.

---

## Features

- **Custom Topics**: CreateOrUpdate, Get, Delete, List (by resource group and by subscription),
  with `properties.endpoint` and `inputSchema` (`EventGridSchema` default, or `CloudEventSchemaV1_0`)
- **Access keys**: `listKeys` returns `{key1, key2}`; `regenerateKey` rotates one of them
- **Event subscriptions**: classic scoped `eventSubscriptions` with a **WebHook** destination,
  `filter` (`subjectBeginsWith`, `subjectEndsWith`, `includedEventTypes`, `isSubjectCaseSensitive`),
  and `retryPolicy`
- **Publish**: `POST /api/events` accepts a JSON array of events in the **Event Grid** or
  **CloudEvents 1.0** schema
- **Delivery**: events are fanned out asynchronously to matching subscribers, retried per the
  subscription's `retryPolicy` with exponential backoff
- **Dead-lettering**: a `StorageBlob` `deadLetterDestination` (or `deadLetterWithResourceIdentity`)
  receives events that exhaust `retryPolicy.maxDeliveryAttempts`, written into the emulated Blob
  service (see below)
- **Validation handshake**: creating a webhook subscription triggers a
  `Microsoft.EventGrid.SubscriptionValidationEvent` (or, for CloudEvents, the `OPTIONS`
  abuse-protection probe)

---

## Endpoints

Management operations use ARM paths; publishing uses the topic's data-plane endpoint.

```
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.EventGrid/topics/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.EventGrid/topics/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.EventGrid/topics/{name}
POST   .../topics/{name}/listKeys
POST   .../topics/{name}/regenerateKey         # body: {"keyName":"key1"|"key2"}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.EventGrid/topics
GET    /subscriptions/{sub}/providers/Microsoft.EventGrid/topics

PUT    {topicId}/providers/Microsoft.EventGrid/eventSubscriptions/{name}
GET    {topicId}/providers/Microsoft.EventGrid/eventSubscriptions/{name}
DELETE {topicId}/providers/Microsoft.EventGrid/eventSubscriptions/{name}
GET    {topicId}/providers/Microsoft.EventGrid/eventSubscriptions

POST   /{name}-eventgrid/api/events            # data-plane publish (topic endpoint)
```

---

## Quickstart

### 1: Create a topic

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.EventGrid/topics/my-topic?api-version=2025-02-15" \
  -H "Content-Type: application/json" \
  -d '{"location":"eastus","properties":{"inputSchema":"EventGridSchema"}}'
```

The response carries the data-plane endpoint:

```json
{
  "name": "my-topic",
  "type": "Microsoft.EventGrid/topics",
  "properties": {
    "provisioningState": "Succeeded",
    "endpoint": "http://localhost:4577/my-topic-eventgrid/api/events",
    "inputSchema": "EventGridSchema"
  }
}
```

Fetch the keys with `POST .../topics/my-topic/listKeys` → `{"key1":"…","key2":"…"}`.

### 2: Subscribe a webhook

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.EventGrid/topics/my-topic/providers/Microsoft.EventGrid/eventSubscriptions/my-sub-1?api-version=2025-02-15" \
  -H "Content-Type: application/json" \
  -d '{
    "properties": {
      "destination": {"endpointType": "WebHook", "properties": {"endpointUrl": "https://my-app.example/hook"}},
      "filter": {"subjectBeginsWith": "/orders"}
    }
  }'
```

The emulator immediately runs the validation handshake against the webhook (a
`SubscriptionValidationEvent`; the subscriber must echo `{"validationResponse": "<code>"}`).

### 3: Publish events

```python
from azure.core.credentials import AzureKeyCredential
from azure.eventgrid import EventGridPublisherClient, EventGridEvent

client = EventGridPublisherClient(
    "http://localhost:4577/my-topic-eventgrid/api/events", AzureKeyCredential("<key1>"))
client.send([EventGridEvent(
    subject="/orders/123", event_type="Order.Created",
    data={"orderId": 123}, data_version="1.0")])
```

Events whose `subject` matches the subscription filter are POSTed (as a JSON array) to the webhook,
with `topic` set to the topic's full resource id and an `aeg-event-type: Notification` header.

---

## Configuration

```yaml
floci-az:
  services:
    event-grid:
      enabled: true
      default-region: eastus          # region label baked into the topic endpoint host text
      max-delivery-attempts: 30        # default when a subscription omits its own retryPolicy
```

| Env var | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_EVENT_GRID_ENABLED` | `true` | Enable/disable the service |
| `FLOCI_AZ_SERVICES_EVENT_GRID_DEFAULT_REGION` | `eastus` | Region label for the topic endpoint |
| `FLOCI_AZ_SERVICES_EVENT_GRID_MAX_DELIVERY_ATTEMPTS` | `30` | Default delivery attempts |

---

## Notes & limitations

- **WebHook destinations only.** Service Bus, Event Hub, Storage Queue, and Azure Function
  destinations, plus Domains, Partner/System Topics, and the Event Grid Namespace (MQTT/pull)
  surface are out of scope.
- **Dead-lettering.** When delivery exhausts `retryPolicy.maxDeliveryAttempts` and the subscription
  has a `StorageBlob` dead-letter destination, the event is written to that container of the storage
  account named by `resourceId` (the account name is the blob account, for example
  `devstoreaccount1`). The blob is named
  `<SUBSCRIPTION NAME IN UPPER CASE>/<yyyy>/<M>/<d>/<H>/<guid>.json` (UTC hour, not zero-padded) and
  holds a JSON array with the event plus `deadLetterReason` (`MaxDeliveryAttemptsExceeded`),
  `deliveryAttempts`, `lastDeliveryOutcome`, `lastHttpStatusCode` (when the endpoint answered),
  `publishTime` and `lastDeliveryAttemptTime`; CloudEvents subscriptions use the lower-case
  `deadletterreason`, `deliveryattempts`, `lastdeliveryoutcome` and `publishtime`. The container
  must already exist. Differences from Azure: the blob is written right after the last attempt
  (Azure waits about five minutes), `eventTimeToLiveInMinutes` is stored but not enforced, 400 and
  413 responses are retried like any other failure instead of being dead-lettered at once, and the
  managed identity of `deadLetterWithResourceIdentity` is echoed but not used. Without a dead-letter
  destination, or when the container does not exist, the event is logged and dropped, as in Azure.
- **Auth is permissive.** The `aeg-sas-key` header is accepted but not validated against the topic
  keys (dev mode), matching the rest of the emulator.
- **`CustomEventSchema`** is accepted but treated as the Event Grid schema.
- **Advanced filters** (`advancedFilters`, array filtering) are accepted but not evaluated.
