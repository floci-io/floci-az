# Azure IoT Hub

Compatible with the `Microsoft.Devices` ARM management plane as the `azurerm_iothub` Terraform resource, the
`azure-mgmt-iothub` SDKs and the `az iot hub` and `az iot hub policy` commands use it. Floci AZ serves IoT hubs
with their shared access policies and keys. Devices, telemetry and message routing are not served yet: see
[Notes & limitations](#notes-limitations).

> **Management plane only, for now.** The device identity registry, the MQTT and AMQP device endpoints and
> message routing are not served. A hub's `hostName` does not answer requests yet.

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
- **Managed identity**: `SystemAssigned`, `UserAssigned` and `SystemAssigned, UserAssigned`, answered as
  [Azure AI Search](search.md) answers them. A hub without one answers `"identity": {"type": "None"}`

---

## Endpoints

```
PUT/GET/PATCH/DELETE .../providers/Microsoft.Devices/IotHubs/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Devices/IotHubs
GET    /subscriptions/{sub}/providers/Microsoft.Devices/IotHubs
POST   /subscriptions/{sub}/providers/Microsoft.Devices/checkNameAvailability

POST   .../IotHubs/{name}/listkeys
POST   .../IotHubs/{name}/IotHubKeys/{keyName}/listkeys
```

Every api-version Azure lists for `Microsoft.Devices/IotHubs` is accepted and served the same way.

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
az iot hub delete -n my-hub -g my-rg
```

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
| `F1` with a partition count other than 2, or any SKU outside 2 to 128 | `400 {"code": 400016, "httpStatusCode": 400, "message": "Invalid PartitionCount 4 - value must be between 2 and 2."}` |
| `minTlsVersion` other than `1.0` or `1.2` | `400 {"code": 400089, ...}` |

The following are refused with ARM's `{"error": {...}}` envelope:

- a missing or unknown SKU, or more units than the SKU allows (1 on `F1`, 10 on `S3` and `B3`, 200 on the
  others)
- a `retentionTimeInDays` outside 1 to 7 days (1 day on `F1`)
- a partition count other than the one the hub was created with (a `PUT` that leaves it out keeps it)
- an `identity` type outside the spec's `ResourceIdentityType` values
- rights outside the spec's `AccessRights` values, or a repeated policy name
- a change of location Under
`parity-overrides.strict-names`, a hub name outside the spec's pattern (3 to 50 letters, digits and
hyphens, not all digits, no leading or trailing hyphen) is refused with `400 InvalidResourceName`.

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
| `FLOCI_AZ_STORAGE_SERVICES_IOTHUB_MODE` | global mode | Storage mode for hubs and their keys |

---

## Notes & limitations

- **No devices or messages yet.** The device identity registry (`az iot hub device-identity`), the device
  endpoints (MQTT, AMQP, HTTPS) and cloud-to-device messaging are not served, so a hub's `hostName` and
  connection strings cannot be used by a device yet.
- **Routing is stored, not run.** `properties.routing` endpoints, routes and enrichments are kept and
  answered as they were sent, but nothing is routed and their references are not checked.
- **Not served:** consumer groups (`eventHubEndpoints/events/ConsumerGroups`), certificates, private
  endpoint connections, `skus`, `quotaMetrics`, `IotHubStats`, failover, import and export of devices,
  and the Device Provisioning Service (`Microsoft.Devices/provisioningServices`).
- **Unchecked rules.** A SKU change between tiers is accepted; Azure refuses it.
- **Terraform waits about two minutes per hub write.** After every create and update, `azurerm_iothub`
  3.x waits a fixed minute before its first `GET` and then polls once a minute, working around a `404` Azure
  can answer for a while after an update (azure-rest-api-specs#27183). That wait is in the provider, so it
  is the same against floci-az as against Azure.
- **Unverified answers.** No public source gives Azure's answer to a duplicate hub name on `PUT` or to a
  stale `If-Match`, so ARM's generic `409 Conflict` and `412 PreconditionFailed` stand in. The codes for
  too many units, a retention period out of range, a partition count change and an unknown policy on
  `IotHubKeys/{keyName}/listkeys` are unrecorded too, so ARM's `InvalidRequestContent` and `NotFound`
  stand in. Whether Azure masks Cosmos DB keys as `****` or leaves them out is also unrecorded.
