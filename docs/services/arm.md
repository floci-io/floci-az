# Azure Resource Manager (ARM)

The central management-plane handler. It serves the generic ARM surface —
subscriptions, resource groups, and the resource/provider listings — that the
`hashicorp/azurerm` Terraform provider, OpenTofu, and the Azure CLI expect, and it acts as the
fallthrough for management-plane paths not claimed by a more specific handler (AKS, SQL, Redis,
Managed Identity, and the other `Microsoft.*` providers).

> **HTTP-only — no Docker.** All ARM resource state is in-memory and ephemeral; it does not persist
> across restarts regardless of `storage.mode`, matching how the other control-plane resources
> behave.

!!! note "Disabling ARM disables the management plane"
    `arm.enabled: false` turns off `/subscriptions` and `/providers` routing, which the
    provider-specific ARM services (Redis, SQL, AKS, …) depend on. Leave it enabled unless you are
    deliberately testing a data-plane-only configuration.

---

## Features

- **Tenant and subscription**: `GET /tenants` and `GET /subscriptions` (enumeration, used by
  `az login`) list the configured tenant and default subscription; `GET /subscriptions/{sub}` answers
  for any subscription id
- **Locations**: `GET /subscriptions/{sub}/locations`, the public-cloud region catalog read by
  `az account list-locations`, the CLI's display-name location translation (`-l "East US"`), and
  Terraform's `data.azurerm_location`
- **Resource groups** — CreateOrUpdate, Get, Delete, List
  (`/subscriptions/{sub}/resourceGroups/{rg}`), scoped per subscription; accepts both `resourceGroups` and the lowercase
  `resourcegroups` spelling
- **Storage accounts** — an ARM shell that bridges `Microsoft.Storage/storageAccounts` to the live
  [Blob](blob.md) and [Queue](queue.md) backends, returning the well-known development account key
- **Key vaults** — an ARM shell for `Microsoft.KeyVault/vaults` whose `vaultUri` points at the
  live [Key Vault](key-vault.md) handler
- **Resource & provider listing** — `GET /subscriptions/{sub}/resources`,
  `GET /subscriptions/{sub}/providers[/{namespace}]`, and
  `POST .../{namespace}/checkNameAvailability`
- **Provider fallthrough** — management-plane paths for providers without a dedicated handler are
  answered here so Terraform reads and dependency lookups resolve

## Endpoints

```
GET    /tenants
GET    /subscriptions
GET    /subscriptions/{sub}
GET    /subscriptions/{sub}/locations

PUT    /subscriptions/{sub}/resourceGroups/{rg}
GET    /subscriptions/{sub}/resourceGroups/{rg}
DELETE /subscriptions/{sub}/resourceGroups/{rg}
GET    /subscriptions/{sub}/resourceGroups

GET    /subscriptions/{sub}/resources
GET    /subscriptions/{sub}/providers[/{namespace}]
POST   /subscriptions/{sub}/providers/{namespace}/checkNameAvailability

# ARM shells (bridge to the live data-plane handlers)
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Storage/storageAccounts/{name}
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.KeyVault/vaults/{name}
```

`azurerm_storage_account` and `azurerm_key_vault` resolve to those two provider paths; the storage
account returns the well-known development key and the vault's `vaultUri` points at the live
[Key Vault](key-vault.md) data plane.

## Quickstart

Point Terraform at the emulator with a minimal `azurerm` provider block (see the
[Terraform guide](../terraform.md) for the full skip-provider-registration setup):

```hcl
provider "azurerm" {
  features {}
  skip_provider_registration = true
  # metadata_host / endpoints redirected at localhost:4577 — see the Terraform guide
}

resource "azurerm_resource_group" "example" {
  name     = "my-rg"
  location = "eastus"
}
```

Or drive it directly:

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/my-rg?api-version=2021-04-01" \
  -H "Content-Type: application/json" \
  -d '{"location":"eastus"}'
```

## Configuration

```yaml
floci-az:
  services:
    arm:
      enabled: true
      default-subscription-id: "00000000-0000-0000-0000-000000000001"
```

| Property | Env var | Default | Description |
|---|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_ARM_ENABLED` | `true` | Enables the ARM management plane. Disabling it turns off every ARM-based service |
| `default-subscription-id` | `FLOCI_AZ_SERVICES_ARM_DEFAULT_SUBSCRIPTION_ID` | `00000000-0000-0000-0000-000000000001` | The subscription `GET /subscriptions` lists, so the one `az login` selects |

The tenant comes from the [Entra ID](entra.md) `default-tenant-id` setting
(`00000000-0000-0000-0000-000000000002` by default), so `GET /tenants`, the subscription's `tenantId`
and issued tokens always agree. The [Managed Identity](managed-identity.md) `system-assigned-scope`
defaults to `subscriptions/${floci-az.services.arm.default-subscription-id}`, so it follows the
subscription when you change it.

## Resource names across subscriptions

Two subscriptions can use the same resource-group name, and most resources then coexist: each
subscription gets its own identity, virtual network, DNS zone, Event Grid topic, AKS cluster, container
group, Container Apps environment or email service under the same name.

Resources whose name is a global DNS name behave as in Azure: the first subscription to create the name
owns it. A create of the same name from another subscription or resource group is refused, that scope
reads the name as `404`, and the name is free again once the owner deletes it. The same holds for child
calls: a storage account's containers, queues and `listKeys` answer `404` under any scope but the
owner's, and a path that names no resource group never reaches another subscription's resource.
Concurrent creates of one name from several subscriptions leave exactly one owner.

| Resource | Refused with |
|---|---|
| `Microsoft.Storage/storageAccounts` | `409 StorageAccountAlreadyTaken`, or `409 StorageAccountInAnotherResourceGroup` within the same subscription |
| `Microsoft.KeyVault/vaults` | `409 VaultAlreadyExists` |
| `Microsoft.KeyVault/managedHSMs` | `409 Conflict` (see below) |
| `Microsoft.Web/sites` | `409` in the App Service error shape (`Code: Conflict`, `ExtendedCode: 54001`) |
| `Microsoft.ContainerRegistry/registries` | `409 AlreadyInUse` |
| `Microsoft.Cache/redis` | `409 NameNotAvailable` |
| `Microsoft.Sql/servers` | `400 NameAlreadyExists` |
| `Microsoft.DBforPostgreSQL/flexibleServers`, `Microsoft.DBforMySQL/flexibleServers`, `Microsoft.DBforMariaDB/servers` | `409 ServerNameAlreadyExists` |
| `Microsoft.ApiManagement/service` | `409 ServiceAlreadyExists` |
| `Microsoft.Communication/communicationServices` | `409 Conflict` (see below) |

`POST /subscriptions/{sub}/providers/Microsoft.Storage/checkNameAvailability` and the
`Microsoft.KeyVault` equivalent report a taken name as `nameAvailable: false`, reason `AlreadyExists`.

The codes come from Microsoft's troubleshooting pages and real error payloads, because the REST specs
do not model create conflicts. No public source gives the code for Managed HSM or Communication Services,
so those two answer with ARM's generic `Conflict`. The status for the PostgreSQL, MySQL and MariaDB
servers is not publicly documented either; `409` is the likeliest.

## Intentional deviations

- **One listed subscription and tenant**: `GET /subscriptions` lists only the default subscription,
  but `GET /subscriptions/{sub}` answers for any id and ARM state is kept per subscription, so a client
  configured with its own subscription id keeps working.
- **Locations are a static public-cloud catalog**: region names and display names come from the
  Azure SDK's region list; every entry is a physical region with zones 1 to 3 mapped to
  `<region>-az<n>`. `regionalDisplayName` repeats `displayName`, and `geography`, `geographyGroup`,
  `latitude`, `longitude`, `physicalLocation` and `pairedRegion` are omitted. There are no edge zones,
  so `includeExtendedLocations=true` returns the same list. Location values on resources are echoed,
  not validated.
- **ARM state is in-memory** — resource groups and the ARM shells do not persist across restarts.
- **Resource-group deletion does not cascade** — deleting a group leaves resources created under it
  behind (they still appear in `GET .../resources`, so Terraform's
  `prevent_deletion_if_contains_resources` works).
- **`checkNameAvailability` always reports `nameAvailable: true`** — the emulator does not track
  global name uniqueness.
- **Shared Key / ARM auth is accepted but not verified** — any bearer token or Shared Key is
  honored, consistent with the rest of the emulator's permissive dev auth.
