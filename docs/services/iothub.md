# Azure IoT Hub

Compatible with the `Microsoft.Devices` ARM management plane as the `azurerm_iothub` Terraform resource, the
`azure-mgmt-iothub` SDKs and the `az iot hub` and `az iot hub policy` commands use it, and with the device
identity registry of the IoT Hub service API as `az iot hub device-identity` (the `azure-iot` extension) uses
it. Floci AZ serves IoT hubs with their shared access policies and keys, and each hub's devices on its
`hostName`. Telemetry and message routing are not served yet: see [Notes & limitations](#notes-limitations).

> **No device endpoints yet.** A hub's `hostName` serves the identity registry only. The MQTT, AMQP and
> HTTPS device endpoints and message routing are not served, so a registered device cannot connect yet.

> **HTTP-only: no Docker.** Hubs are in-process ARM state. There is no sidecar.

---

## Features

- **Hubs**: `Microsoft.Devices/IotHubs` CreateOrUpdate, Update (`PATCH`, tags only), Get, Delete, and List
  by resource group and by subscription. A hub is `Active` and `Succeeded` as soon as the `PUT` answers;
  there is no provisioning delay
- **Shared access policies**: a new hub has Azure's five default policies (`iothubowner`, `service`,
  `device`, `registryRead`, `registryReadWrite`) with generated keys. `listkeys` lists them all and
  `IotHubKeys/{keyName}/listkeys` answers one. Policies are added, changed and removed through
  `properties.authorizationPolicies` on a `PUT`, as `az iot hub policy` and
  `azurerm_iothub_shared_access_policy` do
- **ETags**: every write changes the hub's `etag`. A `PUT`, `PATCH` or `DELETE` whose `If-Match` names an
  older one is refused with `412 PreconditionFailed`; `*` and no `If-Match` at all both pass. A `PUT`
  with an ETag for a hub that no longer exists is refused the same way, so a delayed update cannot
  bring a deleted hub back. An `authorizationPolicies` that
  is not a list of policy objects is refused with `400 InvalidRequestContent`; `null` keeps the policies
- **Name availability**: `checkNameAvailability` reports a taken name as `AlreadyExists` and a malformed
  one as `Invalid`. Hub names are global, so a second hub with a taken name is refused with
  `409 Conflict` in any subscription
- **Consumer groups**: the consumer groups of the built-in `events` endpoint, as `az iot hub consumer-group`
  and `azurerm_iothub_consumer_group` manage them: create, show, list and delete. Every hub has `$Default`
  from its creation. The groups are names only for now: the `events` endpoint itself is not served, so
  nothing reads through them
- **Read-only views**: a hub's valid SKUs (`skus`), its quota metrics (`quotaMetrics`) and its registry
  statistics (`IotHubStats`), as `az iot hub list-skus`, `show-quota-metrics` and `show-stats` read them,
  and the provider's operations (`/providers/Microsoft.Devices/operations`)
- **Managed identity**: `SystemAssigned`, `UserAssigned` and `SystemAssigned, UserAssigned`, answered as
  [Azure AI Search](search.md) answers them. A hub without one answers `"identity": {"type": "None"}`
- **Device identity registry**: on `https://{hub}.azure-devices.net` (`.us` in the US Government cloud), devices are created, read, updated
  (status, keys, thumbprints) and deleted as `az iot hub device-identity create`, `show`, `update`,
  `renew-key`, `list` and `delete` do, with symmetric keys generated when none are sent. Every request is
  checked against the hub's shared access policies. See [Device registry](#device-registry)

---

## Endpoints

```
PUT/GET/PATCH/DELETE .../providers/Microsoft.Devices/IotHubs/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Devices/IotHubs
GET    /subscriptions/{sub}/providers/Microsoft.Devices/IotHubs
POST   /subscriptions/{sub}/providers/Microsoft.Devices/checkNameAvailability

POST   .../IotHubs/{name}/listkeys
POST   .../IotHubs/{name}/IotHubKeys/{keyName}/listkeys

GET    .../IotHubs/{name}/eventHubEndpoints/events/ConsumerGroups
PUT/GET/DELETE .../IotHubs/{name}/eventHubEndpoints/events/ConsumerGroups/{group}

GET    .../IotHubs/{name}/skus
GET    .../IotHubs/{name}/quotaMetrics
GET    .../IotHubs/{name}/IotHubStats
GET    /providers/Microsoft.Devices/operations
```

The service API, on `https://{hub}.azure-devices.net`:

```
GET    /devices
PUT/GET/DELETE /devices/{deviceId}
POST   /devices/query
POST   /devices/keys/regenerate
```

Every api-version Azure lists for `Microsoft.Devices/IotHubs` is accepted and served the same way, and so is
every api-version of the service API.

---

## Quickstart

### Azure CLI

```bash
az group create -n my-rg -l eastus
az iot hub create -n my-hub -g my-rg --sku S1 --partition-count 2
az iot hub show -n my-hub -g my-rg --query properties.hostName -o tsv   # my-hub.azure-devices.net
az iot hub policy list --hub-name my-hub -g my-rg
az iot hub policy create --hub-name my-hub -g my-rg -n backend --permissions RegistryRead ServiceConnect
az iot hub policy renew-key --hub-name my-hub -g my-rg -n backend --renew-key Primary
az iot hub update -n my-hub -g my-rg --retention-day 3
az iot hub consumer-group create --hub-name my-hub -g my-rg -n analytics
az iot hub consumer-group list --hub-name my-hub -g my-rg
az iot hub show-quota-metrics -n my-hub -g my-rg
az extension add --name azure-iot
az iot hub device-identity create -n my-hub -g my-rg -d esp32-1
az iot hub device-identity connection-string show -n my-hub -g my-rg -d esp32-1
az iot hub device-identity update -n my-hub -g my-rg -d esp32-1 --set status=disabled
az iot hub device-identity list -n my-hub -g my-rg
az iot hub device-identity delete -n my-hub -g my-rg -d esp32-1
az iot hub delete -n my-hub -g my-rg
```

The `device-identity` commands call `https://my-hub.azure-devices.net`, so that name must reach floci-az,
over TLS with its certificate trusted: point it at floci-az in `/etc/hosts` (or the embedded DNS), as the
az CLI compatibility tests do. floci-az's generated certificate covers `*.azure-devices.net` and
`*.azure-devices.us`.

### Terraform / OpenTofu

```hcl
resource "azurerm_iothub" "hub" {
  name                = "my-hub"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location

  sku {
    name     = "S1"
    capacity = 1
  }
}

resource "azurerm_iothub_consumer_group" "analytics" {
  name                   = "analytics"
  iothub_name            = azurerm_iothub.hub.name
  eventhub_endpoint_name = "events"
  resource_group_name    = azurerm_resource_group.rg.name
}

output "owner_key" {
  value     = one([for p in azurerm_iothub.hub.shared_access_policy : p.primary_key if p.key_name == "iothubowner"])
  sensitive = true
}
```

---

## Response shape

The shapes follow the resource-manager spec (2023-06-30) and the responses Azure gave the az CLI's `iot`
test recordings:

- `location` is the canonical name (`westus2`), and `properties.locations` lists it as the `primary` by its
  display name. No secondary location is listed
- `properties.hostName` is `{name}.azure-devices.net`, from the cloud's own IoT Hub suffix under
  `floci-az.cloud`. It is derived when the hub is read, not stored
- `properties.eventHubEndpoints.events` carries the `partitionIds`, `path` and `endpoint` Azure generates
  (`sb://iothub-ns-{name}-{digits}-{hex}.servicebus.windows.net/`). The names are fixed when the hub is
  created and kept across updates and restarts. The endpoint is not served
- `subscriptionid` and `resourcegroup` are answered as Azure answers them, in lower case
- A `PUT` answers `201` for an update as well as a create, as Azure does. Azure answers it with
  `provisioningState: Accepted` and an `Azure-AsyncOperation` header; floci-az completes the write first,
  so it answers `Succeeded` with no header, which ends the clients' polling at once. `DELETE` answers `200`
  for a hub that existed and `204` for one that did not
- A `GET` never carries `authorizationPolicies`: Azure sets security properties to null there, and the
  keys are read through `listkeys`
- Connection strings the hub holds for other resources (the file-upload storage endpoint and the routing
  endpoints) are answered with their secret masked, as Azure answers them: `AccountKey=****`,
  `SharedAccessKey=****` and `SharedAccessSignature=****`. A Cosmos DB routing endpoint's `primaryKey` and
  `secondaryKey` are answered as `****`. A `PUT` that sends a masked secret back keeps the stored one,
  which is what `az iot hub update` relies on when it writes back the hub it read. A routing endpoint is
  matched by its `name`, so a masked secret sent under a new name, or with nothing stored, is refused with
  `400 InvalidRequestContent` rather than stored as `****`
- A consumer group answers as Azure answered the az CLI: `properties.created` in RFC 1123
  (`Mon, 22 Sep 2025 10:49:41 GMT`), `etag: null`, and the name URL-encoded in its `id`
  (`.../ConsumerGroups/%24Default`). A `PUT` takes the body the spec requires,
  `{"properties": {"name": "<name>"}}`, with the path's name in any casing, and refuses any other body with
  `400 InvalidRequestContent`. Only a `PUT` echoes the body's name, under `properties.properties.name`.
  The spec's examples show a string `etag`; the recorded answers have `null`. A `PUT` answers `200`, and a `PUT` of a group that exists answers it
  unchanged, with its creation time. Writing a group does not change the hub's `etag`, and a hub `PUT`
  keeps the hub's groups
- The `etag` is in the body only, with no `ETag` header, as in Azure's recorded answers. It changes on
  every write, and a hub created again under a deleted hub's id never answers the deleted hub's `etag`

### What a PUT keeps and replaces

A `PUT` replaces the hub. Each property it leaves out falls back to Azure's default: the `events`
endpoint (1 day, 4 partitions, 2 on `F1`), the `$fallback` route, an empty `$default` storage endpoint,
the cloud-to-device and file-notification settings, `features: None`, and no IP filter rules. A section
that is sent replaces its default whole, even when it is sent empty: `azurerm_iothub` sends
`"storageEndpoints": {}` for a hub without file upload, and reads a `$default` storage endpoint back as a
`file_upload` block. Read-only properties sent in the body (`hostName`, `state`, the endpoint's `path`)
are ignored.

The shared access policies are the exception, as `az iot hub update` relies on:

| The `PUT` sends | The policies afterwards |
|---|---|
| no `authorizationPolicies` | unchanged, keys included |
| a policy with its keys | that policy, with those keys |
| a policy without a key | that policy, with a new key in place of each one left out |
| a list without some existing policy | the list as sent: the missing policy is gone |

`az iot hub policy renew-key` relies on the third row: it sends the policy without the key to renew.

### Errors

Errors the hub itself reports use the IoT Hub `ErrorDetails` shape with Azure's codes:

| Request | Answer |
|---|---|
| `F1` with a partition count other than 2, or a Basic or Standard SKU outside 2 to 32 (Learn's maximum; azurerm's own check allows 128). A hub saved by an earlier floci-az with more keeps its count, so an update that sends it back passes | `400 {"code": 400016, "httpStatusCode": 400, "message": "Invalid PartitionCount 4 - value must be between 2 and 2."}` |
| `minTlsVersion` other than `1.0` or `1.2` | `400 {"code": 400089, ...}` |

The following are refused with ARM's `{"error": {...}}` envelope:

- a missing or unknown SKU, or more units than the SKU allows (1 on `F1`, 10 on `S3` and `B3`, 200 on the
  others)
- a SKU change Azure does not allow: a hub changes SKU within its tier, or from Basic to Standard, as
  its `skus` list says
- a `retentionTimeInDays` outside 1 to 7 days (1 day on `F1`)
- a partition count other than the one the hub was created with (a `PUT` that leaves it out keeps it)
- an `identity` type outside the spec's `ResourceIdentityType` values
- rights outside the spec's `AccessRights` values, or a repeated policy name
- a change of location

Under `parity-overrides.strict-names`, a hub name outside the spec's pattern (3 to 50 letters, digits and
hyphens, not all digits, no leading or trailing hyphen) is refused with `400 InvalidResourceName`.

---

## Device registry

The registry answers on the hub's `hostName`, routed by the `Host` header: `{hub}.azure-devices.net`, or
`{hub}.azure-devices.us` for a hub in the US Government cloud. A hub answers only on its own cloud's host
name, and a request to another hub host finds no hub. The host serves the registry only: an ARM path sent to
it answers `404`, since ARM requests go to the management endpoint. Its shapes follow the IoT Hub service
API's `Device` and the field list on Learn's identity registry page:

- **Device IDs** are 1 to 128 ASCII letters and digits plus `- . % _ * ? ! ( ) , : = @ $ '`, as Learn
  gives them, and are case-sensitive: `Sensor` and `sensor` are two devices. Any other ID is refused
  with `400 ArgumentInvalid`
- **A `PUT` without `If-Match`** creates the device, and answers `409 DeviceAlreadyExists` for one that
  exists. **With `If-Match`** it replaces the device: `*` or its current `etag` pass, any other ETag is
  refused with `412 PreconditionFailed`, and a missing device answers `404 DeviceNotFound`. A `DELETE`
  checks `If-Match` the same way and answers `204`
- **Keys**: a `sas` identity (the default) gets a new 32-byte key in place of each key it leaves out. A key
  that is sent must be base64 of 16 to 64 bytes. A `selfSigned` identity needs a `primaryThumbprint`.
  `renew-key --key-type primary`, `secondary` and `both` call `POST /devices/keys/regenerate`, which
  renews those keys of the listed devices and answers `{"policyKey", "errors", "rotatedKeys"}`: a device
  that is missing or does not use symmetric keys is listed in `errors` and the rest are renewed.
  `--key-type swap` sends the identity back with its keys swapped
- **Generated values**: `generationId` is fixed when the device is created, and the `etag` changes on
  every write. An IoT Edge device gets its own `deviceScope`, `ms-azure-iot-edge://{id}-{generationId}`.
  `parentScopes` follows Learn's identity registry table: an edge device always has it, `[]` without a
  parent, and any other device has it only when it has a parent.
  `connectionState` is `Disconnected` and the activity times are `0001-01-01T00:00:00Z`, since nothing
  connects yet
- **Queries**: `POST /devices/query` answers the two queries `device-identity list` sends,
  `select * from devices` and `select * from devices where capabilities.iotEdge = true` (any case and
  spacing), with each device's twin. Pages follow `x-ms-max-item-count` (100 by default, at most 1000)
  and `x-ms-continuation`. Any other query is refused with `400 SqlQueryInvalid`. The twins' desired and
  reported properties are empty, as twins are not served yet
- **Shared access signatures**: every request must carry
  `Authorization: SharedAccessSignature sr={host}&sig={signature}&se={expiry}&skn={policy}`, signed with
  the primary or secondary key of one of the hub's policies, for the hub's host name (or something under
  it), and not expired. Reading needs `RegistryRead` or `RegistryWrite`, and writing `RegistryWrite`, so
  `iothubowner` and `registryReadWrite` do both, `registryRead` only reads, and `service` and `device`
  do neither. A device's own token (no `skn`) is refused too. A refusal answers
  `401 IotHubUnauthorizedAccess`. The check runs whatever the parity overrides say
- **Errors** are answered as the IoT Hub service SDKs parse them:
  `{"Message": "ErrorCode:DeviceNotFound;...", "ExceptionMessage": "Tracking ID:..."}`, with the code in
  the `iothub-errorcode` header. An unknown hub answers `404 IotHubNotFound`
- **A hub's devices go with it**: deleting the hub, or its resource group, deletes its registry, so a hub
  created again under the name starts empty. Devices are kept per hub, not per name, so this holds even
  when a restart interrupts the cleanup, and a request that a deleted hub authorized changes nothing in
  its successor's registry. `IotHubStats` and the `TotalDeviceCount` of `quotaMetrics` count the registry

---

## Configuration

```yaml
floci-az:
  services:
    iothub:
      enabled: true
  storage:
    services:
      iothub:
        mode: persistent   # optional; follows floci-az.storage.mode by default
```

| Env var | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_IOTHUB_ENABLED` | `true` | Enable/disable the service |
| `FLOCI_AZ_STORAGE_SERVICES_IOTHUB_MODE` | global mode | Storage mode for hubs, their keys and their devices |

---

## Notes & limitations

- **No device endpoints or messages yet.** The device endpoints (MQTT, AMQP, HTTPS) and cloud-to-device
  messaging are not served, so a device's connection string cannot be used yet. Of the service API only the
  identity registry is served: twins, modules, jobs, configurations and import and export of devices answer
  `404`. The registry holds any number of devices; Azure's `TotalDeviceCount` limit is reported, not
  enforced.
- **Routing is stored, not run.** `properties.routing` endpoints, routes and enrichments are kept and
  answered as they were sent, but nothing is routed and their references are not checked.
- **Consumer groups are management plane only.** Nothing reads messages through them: the built-in
  `events` endpoint (AMQP, as `EventHubConsumerClient`, the Functions IoT Hub trigger and
  `az iot hub monitor-events` use it) is not served. Only the `events` endpoint has groups: any other
  endpoint name answers `404`. A hub has at most 20, `$Default` included, as Learn states for every
  tier. `$Default` cannot be deleted. Group names are 1 to 50 letters, digits, hyphens, periods and
  underscores, as Learn's [resource naming rules](https://learn.microsoft.com/en-us/azure/azure-resource-manager/management/resource-name-rules)
  give them for `IotHubs / eventHubEndpoints / ConsumerGroups`, and match ignoring case.
- **The read-only views count devices, not messages.** `IotHubStats` and the `TotalDeviceCount` of
  `quotaMetrics` count the hub's registry; `TotalMessages` has a `currentValue` of 0, since no message
  arrives yet. The maximums follow the hub's SKU and units: `TotalMessages` is the SKU's daily message quota per unit (8,000 on `F1`, 400,000
  on `S1` and `B1`, 6,000,000 on `S2` and `B2`, 300,000,000 on `S3` and `B3`) times the units, and
  `TotalDeviceCount` is 1,000,000 (500 on `F1`). `skus` lists the SKUs the hub can move to, each with
  the units it allows: those of its own tier, as Azure answered for an `S1` hub (`S1`, `S2`, `S3`), and
  for a Basic hub the Standard ones after them. The Basic and `F1` answers (`F1` alone, with a
  `scaleType` of `None`) are unrecorded. `operations` lists the IoT Hub entries of the spec's example,
  without the Device Provisioning Service's.
- **Not served:** certificates, private endpoint connections, endpoint health, route testing, failover,
  import and export of devices, and the Device Provisioning Service
  (`Microsoft.Devices/provisioningServices`).
- **Terraform waits about two minutes per hub write.** After every create and update, `azurerm_iothub`
  3.x waits a fixed minute before its first `GET` and then polls once a minute, working around a `404` Azure
  can answer for a while after an update (azure-rest-api-specs#27183). That wait is in the provider, so it
  is the same against floci-az as against Azure.
- **Unverified answers.** No public source gives Azure's answer to a duplicate hub name on `PUT` or to a
  stale `If-Match`, so ARM's generic `409 Conflict` and `412 PreconditionFailed` stand in. The answers
  to an unknown endpoint, a missing or badly named consumer group, a 21st group and a `$Default` delete
  are unrecorded as well, so ARM's `NotFound` and `InvalidRequestContent` stand in. The codes for
  too many units, a SKU change Azure does not allow (any but within a tier or from Basic to Standard),
  a retention period out of range, a partition count change and an unknown policy on
  `IotHubKeys/{keyName}/listkeys` are unrecorded too, so ARM's `InvalidRequestContent` and `NotFound`
  stand in. Whether Azure masks Cosmos DB keys as `****` or leaves them out is also unrecorded. Learn's
  20-group limit does not say whether `$Default` counts toward it; floci-az counts it. The registry's error
  body is the shape the IoT Hub service SDKs parse rather than a recorded answer, and its codes and messages
  for an invalid device ID or key, a body whose `deviceId` differs from the path's, an unsupported query and
  a refused signature are unrecorded. So is the answer to a key regeneration that leaves some devices out:
  floci-az answers `200` with them in `errors`. The key length rule (16 to 64 bytes) is unrecorded too.
