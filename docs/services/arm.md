# Azure Resource Manager (ARM)

The central management-plane handler. It serves the generic ARM surface
(subscriptions, resource groups, and the resource/provider listings) that the
`hashicorp/azurerm` Terraform provider, OpenTofu, and the Azure CLI expect, and it acts as the
fallthrough for management-plane paths not claimed by a more specific handler (AKS, SQL, Redis,
Managed Identity, and the other `Microsoft.*` providers).

> **HTTP-only: no Docker.** ARM resource state (resource groups and the ARM shells) is in-memory and
> ephemeral; it does not persist across restarts regardless of `storage.mode`. The tenant and
> subscription directory is the exception: it follows `storage.mode` (see
> [Tenants and subscriptions](#tenants-and-subscriptions)), and so do resource groups under
> `resource-group-semantics` (see [Resource groups](#resource-groups)).

!!! note "Disabling ARM disables the management plane"
    `arm.enabled: false` turns off `/subscriptions` and `/providers` routing, which the
    provider-specific ARM services (Redis, SQL, AKS, …) depend on. Leave it enabled unless you are
    deliberately testing a data-plane-only configuration.

---

## Features

- **Tenants and subscriptions**: a directory of tenants (each in one cloud) and subscriptions (each
  owned by one tenant, in one state). `GET /tenants` and `GET /subscriptions` (enumeration, used by
  `az login`) list it; an unknown subscription id is registered on first use, or refused with
  `404 SubscriptionNotFound` under `parity: strict`
- **Locations**: `GET /subscriptions/{sub}/locations`, the public-cloud region catalog read by
  `az account list-locations`, the CLI's display-name location translation (`-l "East US"`), and
  Terraform's `data.azurerm_location`
- **Resource groups**: CreateOrUpdate, Get, CheckExistence (`HEAD`), Update (`PATCH`), Delete, List
  (`/subscriptions/{sub}/resourceGroups/{rg}`), scoped per subscription, names matched case-insensitively;
  `tags` and `managedBy` are kept; accepts both `resourceGroups` and the lowercase `resourcegroups`
  spelling. See [Resource groups](#resource-groups)
- **Storage accounts**: an ARM shell that bridges `Microsoft.Storage/storageAccounts` to the live
  [Blob](blob.md) and [Queue](queue.md) backends, returning the well-known development account key
- **Key vaults**: an ARM shell for `Microsoft.KeyVault/vaults` whose `vaultUri` points at the
  live [Key Vault](key-vault.md) handler
- **Resource & provider listing**: `GET /subscriptions/{sub}/resources`,
  `GET /subscriptions/{sub}/providers[/{namespace}]`, and
  `POST .../{namespace}/checkNameAvailability`
- **Provider fallthrough**: management-plane paths for providers without a dedicated handler are
  answered here so Terraform reads and dependency lookups resolve

## Endpoints

```
GET    /tenants
GET    /subscriptions
GET    /subscriptions/{sub}
GET    /subscriptions/{sub}/locations

PUT    /subscriptions/{sub}/resourceGroups/{rg}
GET    /subscriptions/{sub}/resourceGroups/{rg}
HEAD   /subscriptions/{sub}/resourceGroups/{rg}
PATCH  /subscriptions/{sub}/resourceGroups/{rg}
DELETE /subscriptions/{sub}/resourceGroups/{rg}
GET    /subscriptions/{sub}/resourceGroups
GET    /subscriptions/{sub}/operationresults/{operationId}   # poll of a resource-group delete

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
  # metadata_host / endpoints redirected at localhost:4577: see the Terraform guide
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
  parity: compat                     # compat | azure | strict
  # parity-overrides:
  #   subscription-listing: true     # default: on from azure
  #   strict-subscriptions: true     # default: on from strict
  #   resource-group-semantics: true # default: on from azure
  #   resource-group-delete: true    # default: on from azure
  #   strict-resource-groups: true   # default: on from strict
  cloud:
    default: AzureCloud
  services:
    arm:
      enabled: true
      default-subscription-id: "00000000-0000-0000-0000-000000000001"
  storage:
    services:
      directory:
        # mode: persistent           # default: storage.mode
```

| Property | Env var | Default | Description |
|---|---|---|---|
| `services.arm.enabled` | `FLOCI_AZ_SERVICES_ARM_ENABLED` | `true` | Enables the ARM management plane. Disabling it turns off every ARM-based service |
| `services.arm.default-subscription-id` | `FLOCI_AZ_SERVICES_ARM_DEFAULT_SUBSCRIPTION_ID` | `00000000-0000-0000-0000-000000000001` | The subscription that always exists, listed first by `GET /subscriptions`, so the one `az login` selects |
| `parity` | `FLOCI_AZ_PARITY` | `compat` | The Azure parity level, `compat`, `azure` or `strict`; see [Parity levels](#parity-levels) |
| `parity-overrides.subscription-listing` | `FLOCI_AZ_PARITY_OVERRIDES_SUBSCRIPTION_LISTING` | on from `azure` | `true`: `GET /subscriptions` also lists subscriptions registered on first use, and `GET /subscriptions/{sub}` echoes the directory's id rather than the path's spelling |
| `parity-overrides.strict-subscriptions` | `FLOCI_AZ_PARITY_OVERRIDES_STRICT_SUBSCRIPTIONS` | on from `strict` | `true`: an unknown subscription is `404 SubscriptionNotFound`. `false`: it is registered on first use |
| `parity-overrides.resource-group-semantics` | `FLOCI_AZ_PARITY_OVERRIDES_RESOURCE_GROUP_SEMANTICS` | on from `azure` | `true`: a resource-group re-PUT answers `200`, a missing group is `404 ResourceGroupNotFound`, a resource PUT under a missing group creates the group, and groups persist in the directory store |
| `parity-overrides.resource-group-delete` | `FLOCI_AZ_PARITY_OVERRIDES_RESOURCE_GROUP_DELETE` | on from `azure` | `true`: a resource-group `DELETE` deletes every resource in the group and answers `202` with a `Location` to poll; the generic listings also show Event Grid, Monitor and Email resources |
| `parity-overrides.strict-resource-groups` | `FLOCI_AZ_PARITY_OVERRIDES_STRICT_RESOURCE_GROUPS` | on from `strict` | `true`: a request for a resource under a group that does not exist is `404 ResourceGroupNotFound` |
| `cloud.default` | `FLOCI_AZ_CLOUD_DEFAULT` | `AzureCloud` | The cloud of the default tenant and of a tenant that names none: `AzureCloud`, `AzureUSGovernment` or `AzureChinaCloud`. An unknown name fails startup |
| `tenants[n].*` | `FLOCI_AZ_TENANTS_<n>__ID`, `..._DISPLAY_NAME`, `..._DEFAULT_DOMAIN`, `..._CLOUD` | none | Extra tenants; see below |
| `subscriptions[n].*` | `FLOCI_AZ_SUBSCRIPTIONS_<n>__ID`, `..._TENANT_ID`, `..._DISPLAY_NAME`, `..._STATE` | none | Extra subscriptions; see below |
| `storage.services.directory.mode` | `FLOCI_AZ_STORAGE_SERVICES_DIRECTORY_MODE` | `storage.mode` | Storage mode of the tenant and subscription directory |

The default tenant comes from the [Entra ID](entra.md) `default-tenant-id` setting
(`00000000-0000-0000-0000-000000000002` by default), so `GET /tenants`, the subscription's `tenantId`
and issued tokens always agree. The [Managed Identity](managed-identity.md) `system-assigned-scope`
defaults to `subscriptions/${floci-az.services.arm.default-subscription-id}`, so it follows the
subscription when you change it.

## Tenants and subscriptions

The directory holds the default tenant and subscription, the ones listed under `floci-az.tenants` and
`floci-az.subscriptions`, and the subscriptions registered on first use:

```yaml
floci-az:
  tenants:
    - id: 11111111-0000-0000-0000-000000000000
      display-name: Contoso Gov        # default: floci-az local
      default-domain: contoso.example  # default: floci-az.local
      cloud: AzureUSGovernment         # default: cloud.default
  subscriptions:
    - id: 22222222-0000-0000-0000-000000000000
      tenant-id: 11111111-0000-0000-0000-000000000000   # default: the default tenant
      display-name: contoso-dev                         # default: floci-az local
      state: Enabled                                    # Enabled, Warned, PastDue, Disabled or Deleted
```

Only `id` is required. An entry with the default tenant's or subscription's id overrides it in place,
for example to start the default subscription `Disabled`. A subscription must name the default tenant
or a configured one, and an unknown cloud or state fails startup with the property that holds it.

- **Cloud**: a tenant lives in exactly one cloud, and a subscription's cloud is its tenant's. A request
  under `/subscriptions/{sub}` resolves to that cloud.
- **Listing**: `GET /subscriptions` lists the caller's tenant's subscriptions. Until tokens are read for
  their tenant, the caller's tenant is always the default tenant. The default subscription comes first,
  then configured ones in config order, so `az login` (which selects the first `Enabled` subscription)
  keeps selecting the default. Subscriptions registered on first use are listed after them only under
  `subscription-listing`. `GET /tenants` lists every tenant, each reported as the caller's `Home` tenant.
- **Id echo**: subscription ids are case-insensitive. By default `GET /subscriptions/{sub}` echoes the
  id as the path spells it; under `subscription-listing` it echoes the directory's spelling (the
  configured one, or the one the subscription was first used with).
- **Persistence**: configured entries are rewritten from configuration at every start, and an entry no
  longer configured is dropped. First-use entries survive a restart when `storage.services.directory.mode`
  (or `storage.mode`) persists. `POST /_admin/reset` drops first-use entries. A first-use subscription whose tenant is no longer
  in the directory after a restart (the default tenant id changed, or a configured tenant was removed) moves to
  the default tenant.

### Parity levels

`floci-az.parity` decides how closely floci-az follows Azure where Azure differs from what floci-az has
always answered. The levels nest, and every `parity-overrides` key defaults from the level, so one key
can also be set alone to opt in piecemeal:

| Level | What changes |
|---|---|
| `compat` (default) | Nothing a client could rely on: unknown subscriptions are registered internally on first use but not listed, ids are echoed as sent, and resource groups answer as they always have (every PUT `201`, `DELETE` `200` without cascade) |
| `azure` | Every parity override on: `subscription-listing`, `resource-group-semantics`, `resource-group-delete` |
| `strict` | `azure` plus every `strict-*` override: `strict-subscriptions`, `strict-resource-groups` |

`auth.mode` plays no part in any of this. The startup banner prints `parity: <level>` followed by every
override set to a value its level would not give it.

### First use and strict subscriptions

| Request names a subscription that is | Default | `strict-subscriptions` (on from `parity: strict`) |
|---|---|---|
| in the directory, `Enabled` or `PastDue` | served | served |
| in the directory, `Warned`, `Disabled` or `Deleted` | GET, HEAD and DELETE served; PUT, PATCH and POST refused with `409 ReadOnlyDisabledSubscription` | same |
| not in the directory | registered on first use (`Enabled`, default tenant), then served; listed only under `subscription-listing` | `404 SubscriptionNotFound`: `The subscription '{id}' could not be found.` |

By default any id is accepted, GUID or not, so Terraform and SDK clients configured with their own
subscription keep working. Strict subscriptions do not check the format: a malformed id is simply not in
the directory, and Azure documents `SubscriptionNotFound` for a wrong or wrongly formatted id too.

Emulator choices, with no Azure source to settle them:

- **The refusal of a write on a read-only subscription**: Azure's resource provider contract says only
  GET and DELETE work on a warned or suspended subscription, but no local source gives the status and
  code a client receives. floci-az answers `409 ReadOnlyDisabledSubscription`.
- **`PastDue`** is a billing state the provider contract does not list; floci-az keeps it writable.
- **`Deleted`** is read-only like `Disabled`. Azure cleans a deleted subscription's resources up; floci-az
  does not cascade them.

## Resource groups

Resource groups follow the `ResourceGroups` operations of the Resources REST spec (the az CLI pins
`2024-11-01`, azurerm `2023-07-01`; both declare the same status codes). Names are matched
case-insensitively, and a group answers with the spelling of its latest PUT; a PATCH in another casing
updates the group and keeps that spelling.

These hold in every configuration:

- **Tags and `managedBy`**: a PUT keeps both and every read returns them; a group created without tags
  has no `tags` key.
- **`PATCH`** answers `200` with the group. `tags` and `managedBy` replace what they name (a `tags`
  object replaces the whole tag set, as azurerm expects when it sends its full tag map); everything the
  body leaves out is kept. A missing group is `404 ResourceGroupNotFound`.
- **`HEAD`** answers `204` for a group and `404` without one.

The rest depends on the parity overrides:

| Behaviour | Default | Override |
|---|---|---|
| PUT of an existing group | `201`, as for a new one | `resource-group-semantics`: `200` |
| GET of a missing group | `404 ResourceNotFound` | `resource-group-semantics`: `404 ResourceGroupNotFound`, `Resource group '{rg}' could not be found.` |
| Resource PUT under a missing group | the resource is created, no group appears | `resource-group-semantics`: the group is created once the resource is (only after a `2xx`), in the location the resource reports, else the cloud's default location. `strict-resource-groups`: refused with `404 ResourceGroupNotFound` before the resource's handler runs, as is a read, an action or a delete of a resource under a missing group |
| `DELETE` | `200`; only the group is removed, a missing group is `200` too | `resource-group-delete`: see below |
| Persistence | in memory, lost on restart | `resource-group-semantics`: kept in the directory store, so they follow `storage.services.directory.mode` |
| Generic listings (`.../resources`) | as before: Event Grid topics, Log Analytics workspaces, data collection endpoints and rules, and Communication and Email services are not listed | `resource-group-delete`: listed, so Terraform's `prevent_deletion_if_contains_resources` sees what a delete would remove |

Under `resource-group-delete` a `DELETE` of a group is the long-running operation the spec declares:

1. Every resource the generic index holds in the group is deleted, the deepest ids first, by sending the
   resource's own `DELETE` through the same routing and handler a client request reaches, so each
   service applies its own side effects (a global name is released, a sidecar container stopped). A
   resource that is already gone counts as deleted; any other failure is logged and the delete goes on.
2. The group is removed and the request answers `202` with a `Location` of
   `{scheme}://{host}/subscriptions/{sub}/operationresults/{id}?api-version=...`, built from the request's
   own scheme and host, and `Retry-After: 1`.
3. The work is done by the time the `Location` is polled: it answers `200` with an empty body, the shape
   Azure's own poll ends with. A missing group is `404 ResourceGroupNotFound`.

While the delete runs, the group is marked as being deleted, so that no resource created meanwhile is
left behind: a `PUT` or `PATCH` of a resource under the group is refused with
`409 ResourceGroupBeingDeleted`, and the delete waits for any such request that was already running
before it lists the group's resources. The mark is removed when the delete ends, whether it removed the
group or failed. Without `resource-group-delete` nothing is marked and no request is refused.

Emulator choices, with no Azure source to settle them: a re-PUT with a different location replaces the
location, as floci-az always has (Azure documents the location as fixed at creation but no local source
gives the error it answers); `Retry-After` is `1` second (Azure sends none on this operation, and clients
then fall back to their own, much longer, poll interval); `forceDeletionTypes` is accepted and ignored;
a write under a group that is being deleted answers `409` with the message `The resource group '{rg}' is
in deprovisioning state and cannot perform this operation.` (Azure documents the
`ResourceGroupBeingDeleted` code, with the advice to wait for the deletion to complete, but not its
status or message).

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
For `Microsoft.Storage`, malformed names report `nameAvailable: false`, reason
`AccountNameInvalid`. Storage account names must contain 3 to 24 lowercase letters or digits. A `PUT`
that creates a storage account with such a name is refused with `400 AccountNameInvalid`.

The codes come from Microsoft's troubleshooting pages and real error payloads, because the REST specs
do not model create conflicts. No public source gives the code for Managed HSM or Communication Services,
so those two answer with ARM's generic `Conflict`. The status for the PostgreSQL, MySQL and MariaDB
servers is not publicly documented either; `409` is the likeliest.

## Intentional deviations

- **Unknown subscriptions exist on first use**: unless `strict-subscriptions` is on, any subscription
  id a request names is registered and served (and listed under `subscription-listing`), so a client
  configured with its own subscription id keeps working. ARM state is kept per subscription.
- **The caller's tenant is the default tenant**: `GET /subscriptions` and `GET /tenants` are not yet
  scoped by the token's tenant.
- **Locations are a static public-cloud catalog**: region names and display names come from the
  Azure SDK's region list; every entry is a physical region with zones 1 to 3 mapped to
  `<region>-az<n>`. `regionalDisplayName` repeats `displayName`, and `geography`, `geographyGroup`,
  `latitude`, `longitude`, `physicalLocation` and `pairedRegion` are omitted. There are no edge zones,
  so `includeExtendedLocations=true` returns the same list. Location values on resources are echoed,
  not validated.
- **ARM state is in-memory**: resource groups and the ARM shells do not persist across restarts; only
  the tenant and subscription directory follows `storage.mode`, and resource groups too under
  `resource-group-semantics`.
- **Resource-group deletion does not cascade by default**: deleting a group leaves resources created
  under it behind (they still appear in `GET .../resources`, so Terraform's
  `prevent_deletion_if_contains_resources` works). `resource-group-delete` turns the cascade on.
- **Shared Key / ARM auth is accepted but not verified**: any bearer token or Shared Key is
  honored, consistent with the rest of the emulator's permissive dev auth.
