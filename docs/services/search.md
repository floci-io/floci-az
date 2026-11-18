# Azure AI Search

Compatible with the `Microsoft.Search` ARM management plane as the `azurerm_search_service` Terraform
resource, the `azure-mgmt-search` SDKs and the `az search service`, `az search admin-key` and
`az search query-key` commands use it, and with index management on the data plane as
`SearchIndexClient` of the `azure-search-documents` SDKs uses it. Floci AZ serves search services with
their admin and query API keys, and the index definitions of each service. The other `az search`
command groups are not served yet: see [Notes & limitations](#notes-limitations).

> **Index management only, for now.** Documents, queries, synonym maps, indexers and skillsets are not
> served yet. Those data-plane requests answer `404`.

> **HTTP-only: no Docker.** Search services are in-process ARM state. There is no sidecar.

---

## Features

- **Search services**: `Microsoft.Search/searchServices` CreateOrUpdate, Update (`PATCH`), Get,
  Delete, and List by resource group and by subscription. A service is `succeeded` and `running` as
  soon as the `PUT` answers; there is no provisioning delay
- **Admin keys**: `listAdminKeys` and `regenerateAdminKey/{primary|secondary}`. Regenerating one key
  leaves the other untouched
- **Query keys**: `listQueryKeys`, `createQueryKey/{name}` and `deleteQueryKey/{key}`. A new service
  has one unnamed query key, as in Azure. Deleting a key answers `200`, a key the service does not have
  answers `204`, so a repeated delete succeeds, and a missing service answers `404`
- **Name availability**: `checkNameAvailability` reports a taken name as `AlreadyExists` and a
  malformed one as `Invalid`
- **Managed identity**: `SystemAssigned`, `UserAssigned` and `SystemAssigned, UserAssigned`. A
  system-assigned identity gets a `principalId` and `tenantId` that stay stable across updates. Each
  user-assigned identity reads back the `principalId` and `clientId` of the
  [managed identity](managed-identity.md) it names, and one that does not exist is refused with
  `400 FailedIdentityOperation`. Ids a caller sends are read-only and are not echoed
- **Indexes**: create (`POST` and `PUT`), update, get, list with `$select`, and delete, with
  `@odata.etag` and the `If-Match` / `If-None-Match` conditions. Field definitions are validated and
  answered with Azure's attribute defaults; scoring profiles, analyzers, suggesters, and the semantic
  and vector configuration are stored and answered as they were sent
- **Statistics**: `search.stats` of an index and `servicestats` of the service

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
suffix under `floci-az.cloud`). The data plane is served on that host form and on the path-style
`/{name}-search/` prefix:

```
POST   /{name}-search/indexes
GET    /{name}-search/indexes                        # $select
PUT    /{name}-search/indexes('{index}')             # If-Match, If-None-Match
GET    /{name}-search/indexes('{index}')
DELETE /{name}-search/indexes('{index}')             # If-Match, If-None-Match
GET    /{name}-search/indexes('{index}')/search.stats
GET    /{name}-search/servicestats
```

`indexes/{index}` is accepted for `indexes('{index}')`. Every request needs an `api-version`, and every
api-version is served the same way.

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

### Python SDK

```python
from azure.core.credentials import AzureKeyCredential
from azure.search.documents.indexes import SearchIndexClient
from azure.search.documents.indexes.models import SearchableField, SearchIndex, SimpleField

client = SearchIndexClient("http://localhost:4577/my-search-search", AzureKeyCredential("<admin key>"))
client.create_index(SearchIndex(name="hotels", fields=[
    SimpleField(name="hotelId", type="Edm.String", key=True),
    SearchableField(name="hotelName"),
]))
print([name for name in client.list_index_names()])
```

The endpoint is the emulator's address followed by `/{service name}-search`. The Python SDK accepts a
plain `http` endpoint and the JavaScript SDK does with `allowInsecureConnection: true`. The Java and
.NET SDKs send an API key over `https` only: use the emulator's TLS endpoint (`FLOCI_AZ_TLS_ENABLED=true`,
with its certificate from `/_floci/tls-cert` trusted).

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

## Authentication

By default the data plane accepts any `api-key` or `Authorization: Bearer` header, or none, and it
serves a service name that was never created through ARM, as Key Vault does for a vault with no ARM
resource.

With `floci-az.parity-overrides.strict-search-keys` (on from `parity: strict`):

- a service that ARM does not hold answers `403`
- an `api-key` must be the primary or secondary admin key of the service. A query key, an unknown key
  and a missing key answer `403`: index management is an admin operation
- a service whose ARM resource has `disableLocalAuth: true` refuses every `api-key` with `403`
- an `Authorization: Bearer` token passes only when the service takes tokens: its `authOptions` is
  `aadOrApiKey`, or it has `disableLocalAuth: true`. A service with the default `apiKeyOnly` refuses
  it with `403`, as the spec says that mode means "only the API key can be used". Azure's status code
  for this refusal is not verified
- a token that passes is not validated: `strict-tokens` applies to ARM requests only, and no role
  assignment is checked

| Env var | Default | Description |
|---|---|---|
| `FLOCI_AZ_PARITY_OVERRIDES_STRICT_SEARCH_KEYS` | on from `parity: strict` | Check the service and its admin key on the data plane |

---

## Index definitions

The shapes follow the data-plane spec (`2025-09-01`) and its examples:

- `POST /indexes` answers `201`, or `409 ResourceNameAlreadyInUse` for a name that is taken. `PUT`
  answers `201` for a new index and `200` for one that exists, always with the definition
- Every definition carries `@odata.etag`, which changes on each write. `If-Match` and `If-None-Match`
  take the tag quoted, weak (`W/"..."`) or bare, or `*`, and a failed condition answers `412`
- A simple field is answered with `searchable`, `filterable`, `retrievable`, `stored`, `sortable`,
  `facetable` and `key`. What the request left out gets Azure's default: `true`, except that only
  strings and vectors are `searchable`, a multi-valued field (a collection, or any simple field under
  a `Collection(Edm.ComplexType)`, however deep) is not `sortable`, a `Edm.GeographyPoint` is not
  `facetable`, a vector field is neither `filterable`, `sortable` nor `facetable`, and a vector field
  is not `retrievable` (the spec: "Default is true for simple fields, false for vector fields")
- A complex field is answered with its `fields` and no attributes of its own, apart from properties a
  newer api-version adds, which come back as they were sent
- The sections a request leaves out are answered as empty lists or `null`, and `similarity` as BM25

A definition is refused with `400 InvalidRequestParameter` when:

- the index has no `name`, or the name is not 2 to 128 lowercase letters, digits, dashes and
  underscores, starting with a letter or digit and with no consecutive dashes or underscores, as
  Microsoft's [naming rules](https://learn.microsoft.com/rest/api/searchservice/naming-rules) set out
- it does not have exactly one `key` field, or the key is not a top-level `Edm.String`
- a field name does not start with a letter, holds anything but letters, digits and underscores,
  begins with `azureSearch`, or is used twice at one level
- a field type is not one the spec lists, or `Edm.Single`, `Edm.Half`, `Edm.Int16`, `Edm.SByte` or
  `Edm.Byte` is used outside `Collection(...)`
- a vector field has no `dimensions` from 2 to 4096, or names a `vectorSearchProfile` that
  `vectorSearch.profiles` does not define
- a complex field has no sub-fields, or a simple field has some
- a complex field sets any of `key`, `searchable`, `filterable`, `retrievable`, `stored`,
  `sortable`, `facetable`, `analyzer`, `searchAnalyzer`, `indexAnalyzer` or `normalizer`, even to
  `false`, or a non-empty `synonymMaps`: the spec says they "must be null for complex fields"
- a field other than a string or a vector is `searchable`, a vector field is not `searchable`, or a
  vector field is `filterable`, `sortable` or `facetable`
- a multi-valued field is `sortable`, or a `Edm.GeographyPoint` field is `facetable`
- the key field is not `retrievable` or not `stored`
- a field other than a vector has `stored: false`, or a field with `stored: false` is `retrievable`
- a value has the wrong JSON type, such as `"key": "true"`, `"fields": "id"` or a `synonymMaps` entry
  that is not a name
- the body is not a JSON object, the literal `null` included
- the body of a `PUT` names another index than its URL: the two must be the same, letter for letter

An update may add fields, and sub-fields to a complex field. It is refused with
`400 OperationNotAllowed` when it removes a field, changes the `type`, `key`, `searchable`,
`filterable`, `sortable`, `facetable`, `stored`, `analyzer`, `indexAnalyzer`, `normalizer`,
`dimensions` or `vectorEncoding` of one that exists, or changes `similarity`, which the spec says
"can only be defined at index creation time". It is refused the same way when it adds an analyzer,
tokenizer, token filter or char filter without `allowIndexDowntime=true`, the parameter the spec says
"allows new analyzers, tokenizers, token filters, or char filters to be added". Sending the components
the index already has needs no parameter, and nor does adding a field. A field it adds with `stored: false` is refused with
`400 InvalidRequestParameter`, as `stored: false` is for index creation only. `retrievable`,
`searchAnalyzer` and `synonymMaps` may change, as the spec allows. An update that leaves out
`similarity` keeps the stored one, and one that leaves out `encryptionKey` or sends it as `null`
keeps the stored key, as the spec says the service "will ignore attempts to set this property to
null". Sending a different key rotates it.

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

- **Index management is the whole data plane so far.** There are no documents, queries, synonym
  maps, indexers, data sources or skillsets yet, and `search.analyze` is not served. An index reports
  zero documents and zero bytes. Index aliases, a stable operation from api-version `2026-04-01`, are
  not served either.
- **Index sections are stored, not checked.** Scoring profiles, analyzers, tokenizers, suggesters, CORS
  options, and the semantic and vector configuration come back as they were sent. Only their JSON type
  is checked: a suggester that names a field the index does not have is accepted, where Azure refuses
  it, and so are an analyzer name that is not defined and a vector profile whose algorithm is not.
  The one reference checked is a vector field's profile. A property a newer api-version adds is kept
  and answered too. These rules of the spec are not enforced either, and wait for the phases that use
  the sections:
  - `analyzer` only on a searchable field, never together with `searchAnalyzer` or `indexAnalyzer`
  - `searchAnalyzer` and `indexAnalyzer` set together, and neither naming a language analyzer
  - `normalizer` only on a field that is `filterable`, `sortable` or `facetable`
  - `synonymMaps` only on a searchable field, with at most one map
  - a suggester's required `searchMode` and `sourceFields`, and its source fields being searchable
  - the required `allowedOrigins` of `corsOptions`
  - the required `algorithm` of a vector profile
- **`allowIndexDowntime` takes nothing offline.** With the parameter, a new analysis component is
  added at once, and requests to the index keep being served. Changing or removing an existing
  component is not refused.
- **Bearer tokens are not validated on the data plane.** By default a request with
  `Authorization: Bearer` and any token is served. With `strict-search-keys` the service must take
  tokens, but the token itself is still not checked, and no Search role (such as Search Service
  Contributor) is required.
- **Data-plane error codes are not from the spec.** The spec defines the `{"error":{"code","message"}}`
  envelope and no codes, so `ResourceNameAlreadyInUse`, `OperationNotAllowed`,
  `InvalidRequestParameter`, `ResourceNotFound`, `PreconditionFailed`, `MissingRequiredParameter` and
  `Forbidden` are descriptive and not verified against Azure, and so are the messages. Match on the
  status code, as the SDKs do.
- **A conditional `DELETE` of a missing index answers `404`.** The `If-Match` condition is not
  evaluated when there is no index, while a conditional `PUT` of a missing index answers `412`. Which
  one Azure answers for the delete is not verified.
- **A vector profile may change in place.** An update may change a vector field's
  `vectorSearchProfile` without refusal. Whether Azure refuses that change is not verified.
- **An index write can outlive its service's delete.** An index write that started before the
  service's ARM delete can finish after it, and leave that index behind for a service of the same
  name. Under the default lenient mode an index can be written to a service ARM does not hold anyway.
- **An update without `similarity` keeps the stored one.** Whether Azure treats a missing `similarity`
  on an existing index as no change or refuses it is not verified.
- **`encryptionKey` is stored, not used.** Nothing is encrypted, the Key Vault key is not checked,
  and a `free` service accepts a key, although the spec says customer-managed keys are not available
  for free services.
- **A `PUT` always answers the definition.** The spec requires `Prefer: return=representation`, which
  every SDK sends, so a `PUT` without it is answered the same way.
- **Limits are reported, not enforced.** `servicestats` reports the index quota of the service's SKU
  (`standard` for a service ARM does not hold; a `standard3` service in `HighDensity` mode holds 1000
  per partition, up to 3000) and the field limits Azure documents. Nothing stops an
  index beyond them. The counters of every api-version are answered, `aliasesCount` and the two
  knowledge counters included, as newer SDKs require them.
- **Deleting a service deletes its indexes.** A resource-group delete does too when it cascades
  (`resource-group-delete`, on from `parity: azure`). Indexes belong to the cloud the request
  addressed, so a service of the same name in another cloud keeps its own.
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
  outside the spec's four. So is a body that is the JSON literal `null`. A `null` property is no value
  and keeps what is stored.
- **Enums and firewall rules.** `hostingMode`, `computeType`, `publicNetworkAccess`, `semanticSearch`,
  `knowledgeRetrieval`, `networkRuleSet.bypass`, `encryptionWithCmk.enforcement`,
  `authOptions.aadOrApiKey.aadAuthFailureMode` and `dataExfiltrationProtections` must hold a value
  the spec lists, in any casing, and each firewall rule must be an IPv4 address or CIDR range.
  Anything else is refused with `400 InvalidRequestContent`. The allowed values are the union over every api-version, since every api-version is served the same
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
