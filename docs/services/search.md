# Azure AI Search

Compatible with the `Microsoft.Search` ARM management plane as the `azurerm_search_service` Terraform
resource, the `azure-mgmt-search` SDKs and the `az search service`, `az search admin-key` and
`az search query-key` commands use it. Floci AZ serves search services with their admin and query API
keys. The other `az search` command groups are not served yet: see
[Notes & limitations](#notes-limitations).

> **Management plane only, for now.** Indexes, documents, queries, indexers and skillsets are not
> served yet. A data-plane request answers `404`.

> **HTTP-only: no Docker.** Search services are in-process ARM state. There is no sidecar.

---

## Features

- **Search services**: `Microsoft.Search/searchServices` CreateOrUpdate, Update (`PATCH`), Get,
  Delete, and List by resource group and by subscription. A service is `succeeded` and `running` as
  soon as the `PUT` answers; there is no provisioning delay
- **Admin keys**: `listAdminKeys` and `regenerateAdminKey/{primary|secondary}`. Regenerating one key
  leaves the other untouched
- **Query keys**: `listQueryKeys`, `createQueryKey/{name}` and `deleteQueryKey/{key}`. A new service
  has one unnamed query key, as in Azure
- **Name availability**: `checkNameAvailability` reports a taken name as `AlreadyExists` and a
  malformed one as `Invalid`
- **Managed identity**: `SystemAssigned`, `UserAssigned` and `SystemAssigned, UserAssigned`. A
  system-assigned identity gets a `principalId` and `tenantId` that stay stable across updates. Each
  user-assigned identity reads back the `principalId` and `clientId` of the
  [managed identity](managed-identity.md) it names, and one that does not exist is refused with
  `400 FailedIdentityOperation`. Ids a caller sends are read-only and are not echoed

---

## Endpoints

```
PUT/GET/PATCH/DELETE .../providers/Microsoft.Search/searchServices/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Search/searchServices
GET    /subscriptions/{sub}/providers/Microsoft.Search/searchServices
POST   /subscriptions/{sub}/providers/Microsoft.Search/checkNameAvailability

POST   .../searchServices/{name}/listAdminKeys
POST   .../searchServices/{name}/regenerateAdminKey/{primary|secondary}
POST   .../searchServices/{name}/listQueryKeys            # GET is accepted too
POST   .../searchServices/{name}/createQueryKey/{keyName}
DELETE .../searchServices/{name}/deleteQueryKey/{key}
```

A service's `properties.endpoint` is `https://{name}.search.windows.net` (the cloud's own search
suffix under `floci-az.cloud`). The emulator claims that host form and the path-style
`/{name}-search/` prefix for the data plane that is to come.

---

## Quickstart

### Azure CLI

```bash
az group create -n my-rg -l eastus
az search service create -n my-search -g my-rg -l eastus --sku basic

az search service show -n my-search -g my-rg --query endpoint -o tsv
az search admin-key show --service-name my-search -g my-rg
az search query-key create --service-name my-search -g my-rg -n my-app
```

### Terraform / OpenTofu

```hcl
resource "azurerm_search_service" "search" {
  name                = "my-search"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  sku                 = "basic"
}

output "search_primary_key" {
  value     = azurerm_search_service.search.primary_key
  sensitive = true
}
```

---

## Response shape

The shapes follow the resource-manager spec (`2025-05-01`) and the responses Azure gives the `az`
CLI:

- `PUT` answers `201` on create and `200` on update, with no polling headers
- `location` is answered as a display name (`East US`), while the generic resource listings carry the
  canonical one (`eastus`)
- `provisioningState` is `succeeded` and `status` is `running`, both lowercase
- `tags` is `{}` when the client sent none
- `authOptions` is `{"apiKeyOnly":{}}`, or `null` when `disableLocalAuth` is `true`
- `semanticSearch` defaults to `free`, `publicNetworkAccess` to `Enabled`, and `replicaCount` and
  `partitionCount` to `1`
- `knowledgeRetrieval` (from `2026-03-01-preview`) appears once a client has set it, and is kept across
  later updates
- The keys never appear on the resource: only the key operations return them

---

## SKU limits

| SKU | Replicas | Partitions |
|---|---|---|
| `free` | 1 | 1 |
| `basic` | 1 to 3 | 1 to 3 |
| `standard`, `standard2`, `standard3` | 1 to 12 | 1, 2, 3, 4, 6 or 12 |
| `standard3` with `hostingMode: HighDensity` | 1 to 12 | 1 to 3 |
| `storage_optimized_l1`, `storage_optimized_l2` | 1 to 12 | 1, 2, 3, 4, 6 or 12 |

- Replicas times partitions is at most 36 search units
- `hostingMode: HighDensity` is accepted on `standard3` only
- `basic` gets the three partitions of a service created after April 2024. Older Azure services are
  limited to one
- Not enforced: regional availability, subscription quota (such as one `free` service per
  subscription), which SKUs offer semantic search, and Azure's restrictions on changing the SKU of an
  existing service

The limits come from the `2025-05-01` spec and Microsoft's
[service limits](https://learn.microsoft.com/azure/search/search-limits-quotas-capacity) page.

---

## Configuration

```yaml
floci-az:
  services:
    search:
      enabled: true
```

| Env var | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_SEARCH_ENABLED` | `true` | Enable/disable the service |

---

## Notes & limitations

- **No data plane yet.** There are no indexes, documents, queries, indexers, data sources or
  skillsets. The keys are real values that stay stable, but nothing validates them yet.
- **Service names are global.** A name is a DNS label under the search suffix, so a second service of
  the same name is refused with `409 Conflict` in any subscription or resource group. Azure's own
  code for that conflict is not publicly documented.
- **Validation.** A create without a `sku`, an unknown SKU name, and a `replicaCount` or
  `partitionCount` outside 1 to 12 are refused with `400 InvalidRequestContent`. So is a scale the SKU
  does not reach (see [SKU limits](#sku-limits)). A `PATCH` is judged on the service as it would leave it, so
  changing only the SKU of a service that is too large for the new one is refused too. Azure's own
  error code for these is not publicly documented.
- **Field types.** A field sent with the wrong JSON type, such as `disableLocalAuth: "true"`,
  `dataExfiltrationProtections: "BlockAll"` or `networkRuleSet.ipRules: "invalid"`, is refused with
  `400 InvalidRequestContent` rather than replaced by its default, and so is an identity `type`
  outside the spec's four. A `null` is no value and keeps what is stored.
- **Enums and firewall rules.** `hostingMode`, `computeType`, `publicNetworkAccess`, `semanticSearch`,
  `knowledgeRetrieval`, `networkRuleSet.bypass`, `encryptionWithCmk.enforcement` and
  `dataExfiltrationProtections` must hold a value the spec lists, in any casing, and each firewall
  rule must be an IPv4 address or CIDR range. Anything else is refused with `400 InvalidRequestContent`.
  The allowed values are the union over every api-version, since every api-version is served the same
  way.
- **Local authentication.** A request that sends `disableLocalAuth: true` together with
  `authOptions` is refused with `400 InvalidRequestContent`, as the spec rules out that combination.
  A service with local authentication disabled answers `authOptions: null`.
- **Semantic ranker.** A service created without `semanticSearch` reports `free`, the spec's default
  and what Azure answers. This is reported on every SKU, including `free`, which Azure may treat
  differently: no public recording shows a `free`-SKU service.
- **Fixed at creation.** A `PUT` that moves an existing service to another location is refused with
  ARM's `400 InvalidResourceLocation`. A `PUT` or `PATCH` that sends a different `hostingMode` is
  refused with `400 InvalidRequestContent`, and a `PUT` that leaves it out keeps the stored one.
- **Naming rule.** With `strict-names`, a name must be 2 to 60 lowercase letters, digits and hyphens,
  start with two letters or digits, not end with a hyphen and have no consecutive hyphens.
- **Not modelled.** These answer `404 ResourceNotFound`:
    - private endpoint connections, shared private link resources and private link resources
      (`az search private-endpoint-connection`, `shared-private-link-resource`, `private-link-resource`)
    - network security perimeter configurations
    - usage and quota (`az search usage list`, `locations/{location}/usages`)
    - regional offerings (`az search offering list`) and service upgrade (`az search service upgrade`)
    - the provider's operation list, `GET /providers/Microsoft.Search/operations`

    The `privateEndpointConnections` and `sharedPrivateLinkResources` properties of a service are
    always empty lists. `autoGeneratedDomainNameLabelScope` (`2026-09-01-preview`) is not modelled
    either: it would change the service's hostname, so it is dropped rather than echoed.
- **Storage.** Services and their keys are kept in the `search` storage backend, so they follow
  `floci-az.storage.services.search.mode` (the global `storage.mode` by default).
