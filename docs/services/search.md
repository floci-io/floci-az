# Azure AI Search

Compatible with the `Microsoft.Search` ARM management plane as the `azurerm_search_service` Terraform
resource, the `azure-mgmt-search` SDKs and the `az search service`, `az search admin-key` and
`az search query-key` commands use it, and with index management and documents on the data plane as
`SearchIndexClient` and `SearchClient` of the `azure-search-documents` SDKs use them. Floci AZ serves
search services with their admin and query API keys, the index definitions of each service, the
documents of each index, and full-text search over them. The other `az search` command groups are not
served yet: see [Notes & limitations](#notes-limitations).

> **Full-text search, filters, sorting and facets.** A search runs the simple or full Lucene syntax
> with Azure's analyzers, BM25 scores, highlights, scoring profiles and paging, and takes `$filter`,
> `$orderby` and facets. Suggestions, autocomplete, vector and semantic queries are not served yet.

> **Queries run in a Solr sidecar.** The first search service pulls and starts one shared
> `solr:10.1.0-slim` container, `floci-az-search-default`, and deleting the last one removes it.
> Without Docker, a search answers `503` and everything else works. See [Queries](#queries).

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
- **Documents**: a batch of `upload`, `merge`, `mergeOrUpload` and `delete` actions, a lookup by key
  with `$select`, and `$count`. Values are checked against their fields and answered in Azure's shapes
- **Statistics**: `search.stats` of an index and `servicestats` of the service, with real document
  counts

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

POST   /{name}-search/indexes('{index}')/docs/search.index   # allowUnsafeKeys
GET    /{name}-search/indexes('{index}')/docs('{key}')       # $select
GET    /{name}-search/indexes('{index}')/docs/$count

POST   /{name}-search/indexes('{index}')/docs/search.post.search
GET    /{name}-search/indexes('{index}')/docs        # search, $top, $skip, ... as query parameters
```

`indexes/{index}` is accepted for `indexes('{index}')`, and `docs/{key}` for `docs('{key}')`. Every request needs an `api-version`, and every
api-version is served the same way.

The shapes below follow `2025-09-01`, but the SDKs send newer versions by default: Python
`azure-search-documents` 12.1.0 and JavaScript `@azure/search-documents` 13.1.0 send `2026-10-01`,
and Java `azure-search-documents` 12.0.1 and .NET `Azure.Search.Documents` 12.0.0 send `2026-04-01`.
These are the versions the compatibility suites run at. Where a version's model differs, the
response follows the request's version (see the `servicestats` counters below).

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
- a request with no credential answers `401` with no body and the challenge
  `Bearer authorization="{login}/{tenant}/oauth2/authorize" resource="https://search.azure.com"`, as
  Azure does. The authority is the emulator's own login endpoint, and the audience is the cloud's
  (`https://search.azure.us` and `https://search.azure.cn` in the national clouds)
- an `api-key` must be the primary or secondary admin key of the service. An unknown key answers
  `403` with Azure's message, "The given API key doesn't match service's internal, primary or
  secondary keys." A query key may look up and count documents, the read-only access the spec gives
  it, and is refused with `403` everywhere else: index management and a document batch need an admin
  key. Azure does not document how it answers a query key, so these answers are floci-az's
- a service whose ARM resource has `disableLocalAuth: true` refuses every `api-key` with `403`
- an `Authorization: Bearer` token passes only when the service takes tokens: its `authOptions` is
  `aadOrApiKey`, or it has `disableLocalAuth: true`. A service with the default `apiKeyOnly` refuses
  it with `401`, no body and `Bearer realm="Azure Cognitive Search" error="invalid_token"`, the answer
  Azure gives a token it cannot use
- a token that passes is not validated: `strict-tokens` applies to ARM requests only, and no role
  assignment is checked

| Env var | Default | Description |
|---|---|---|
| `FLOCI_AZ_PARITY_OVERRIDES_STRICT_SEARCH_KEYS` | on from `parity: strict` | Check the service and its admin key on the data plane |

---

## Index definitions

The shapes follow the data-plane spec (`2025-09-01`) and its examples, and a recording of a real
service (Free tier, api-version `2025-09-01`, November 2026) where the spec is silent. The status
codes, error `code` values, `details` and messages below are Azure's, word for word:

- `POST /indexes` answers `201`, or `400 ResourceNameAlreadyInUse` with the detail
  `CannotCreateExistingIndex` for a name that is taken (Azure does not answer `409`). `PUT` answers
  `201` for a new index and `200` for one that exists, always with the definition. A `201` carries a
  `Location` that ends in `?api-version=` and the request's version
- Every definition carries `@odata.etag`, quoted, and the same tag in a strong `ETag` header. The
  tag changes on each write. An update that changes nothing writes nothing: the tag stays, and the
  `200` carries no `@odata.etag` and no `ETag`, as on Azure. `If-Match` and `If-None-Match` take the
  tag quoted, weak (`W/"..."`) or bare, or `*`, and a failed condition answers `412` with an empty
  `code`
- A `GET` of a missing index answers `404` with an empty `code`. A `DELETE` of one answers `404`
  `OperationNotAllowed` with the detail `IndexNotFoundInService`, or `412` when it carries `If-Match`,
  even `If-Match: *`
- A simple field is answered with `searchable`, `filterable`, `retrievable`, `stored`, `sortable`,
  `facetable` and `key`, then `indexAnalyzer`, `searchAnalyzer`, `analyzer`, `normalizer`,
  `dimensions`, `vectorSearchProfile` and `vectorEncoding`, `null` when unset, then `synonymMaps`.
  What the request left out gets Azure's default: `true`, except that only strings and vectors are
  `searchable`, a multi-valued field (a collection, or any simple field under a
  `Collection(Edm.ComplexType)`, however deep) is not `sortable`, a `Edm.GeographyPoint` is not
  `facetable`, a vector field is neither `filterable`, `sortable` nor `facetable`, and a vector field
  is not `retrievable` (the spec: "Default is true for simple fields, false for vector fields")
- A boolean attribute sent as the string `"true"` or `"false"` is read as the boolean, as Azure reads
  it; any other string is refused
- A complex field is answered with its `fields` and no attributes of its own, apart from properties a
  newer api-version adds, which come back as they were sent
- The sections a request leaves out are answered as empty lists or `null`, `description` included, and
  `similarity` as BM25 with `null` parameters. A custom analyzer gains empty `tokenFilters` and
  `charFilters`. `vectorSearch` is answered with `algorithms`, `profiles`, `vectorizers` and
  `compressions`; a profile gains a `null` `vectorizer` and `compression`, and an `hnsw` algorithm the
  default `hnswParameters` (`metric: cosine`, `m: 4`, `efConstruction: 400`, `efSearch: 500`, each
  only where the request left it out) and a `null` `exhaustiveKnnParameters`. An `exhaustiveKnn`
  algorithm gains `metric: cosine` and a `null` `hnswParameters`, floci-az's reading of the spec

A value that does not fit the model, such as `"fields": "id"`, a `synonymMaps` entry that is not a
name, or a body that is not a JSON object, is refused with `400` and an empty `code`. Azure's reader
words it as `Cannot convert the literal '3' to the expected type 'Edm.String'.`, and the body `null` as
`An unexpected 'PrimitiveValue' node was found when reading from the JSON reader. A 'StartObject'
node was expected.` A `PUT` whose body names another index than its URL is refused with `400` and the
code `"400"`, as on Azure. The two names must be the same, letter for letter.

Any other definition is refused with `400 OperationNotAllowed` when:

- the index has no `name`, or the name is not 2 to 128 lowercase letters, digits, dashes and
  underscores, starting with a letter or digit and with no consecutive dashes or underscores, as
  Microsoft's [naming rules](https://learn.microsoft.com/rest/api/searchservice/naming-rules) set out
- it does not have exactly one `key` field (Azure's detail `MissingKeyField`), or the key is not a
  top-level `Edm.String`
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

An update may add fields, and sub-fields to a complex field. It is refused with
`400 OperationNotAllowed` when it removes a field (`Existing field(s) 'title' cannot be deleted.`,
detail `CannotDeleteExistingField`) or changes the `type`, `key`, `searchable`, `filterable`,
`sortable`, `facetable`, `stored`, `analyzer`, `indexAnalyzer`, `normalizer`, `dimensions`,
`vectorSearchProfile` or `vectorEncoding` of one that exists (`Existing field 'rating' cannot be
changed.`, detail `CannotChangeExistingField`). A field it adds with `stored: false` is refused too, as
`stored: false` is for index creation only. `retrievable`, `searchAnalyzer` and `synonymMaps` may
change, as the spec allows.

An update that adds an analyzer, normalizer, tokenizer, token filter or char filter, or changes a
parameter of `similarity` (such as `k1`), needs `allowIndexDowntime=true`. Without it the update is
refused with `400`, an empty `code` and Azure's message: "An index with the name 'x' in service 'y'
could not be updated. Index update not allowed because it would cause downtime. ...". Sending the
components the index already has needs no parameter, and nor does adding a field. The `similarity`
algorithm cannot change at all: `400`, "Similarity type cannot be changed on existing indexes." An
update that leaves out `similarity` keeps the stored one, and one that leaves out `encryptionKey` or
sends it as `null` keeps the stored key, as the spec says the service "will ignore attempts to set
this property to null". Sending a different key rotates it.

---

## Documents

The operations follow the data-plane spec (`searchindex.json`, `2025-09-01`), and where it is silent the
recording of a real service the index definitions follow. The status codes, error codes, `details` and
messages below are Azure's, word for word.

A batch, `POST docs/search.index` with `{"value": [...]}`, answers each action with its `key`,
`status`, `errorMessage` and `statusCode`: `201` for a document it created, `200` for one it updated or
deleted. It answers `200` when every action succeeds and `207` when one fails.

- `upload` replaces the whole document. An action with no `@search.action` is an `upload`
- `merge` replaces the fields it sends, and an explicit `null` clears one. A collection is replaced
  whole, and a complex field that is not a collection merges field by field: merging
  `address: {city}` keeps `address.postcode`. A `merge` of a key that does not exist fails that
  action alone: `404`, `Document not found.`
- `mergeOrUpload` merges into a document that exists, and uploads one that does not
- `delete` answers `200` whether or not the document exists
- two actions on one key run in order, and the last one wins
- a string that would be one indexed term over Lucene's 32766 UTF-8 bytes fails that action alone,
  with `400` and `Field 'title' contains a term that is too large to process. ...`: the whole value
  of a filterable, sortable or facetable field is one term, and so is what the `keyword`,
  `whitespace` and `pattern` analyzers do not split. This message is not yet checked against Azure

Everything else that is wrong refuses the whole batch with `400`, and nothing in it is written:

- a value that does not fit its field, an unknown field, or a `null` inside a collection, with an
  empty `code`: `Cannot convert the literal 'not-a-number' to the expected type 'Edm.Double'.`,
  `The property 'x' does not exist on type 'search.documentFields' ...`
- an action that is not one of the four, a key that is missing or not valid, or a vector whose length
  is not its field's `dimensions`, with a code, a `details` entry and the position of the action:
  `The request is invalid. Details: actions : 0: Document key cannot be missing or empty.`
- a batch with no actions (`MissingRequiredParameter`)

A key holds letters, digits, `_`, `-` and `=`, does not start with `_`, and is at most 1024
characters. `allowUnsafeKeys=true` lifts the rule on characters, as Azure's message offers. Keys are
case-sensitive: `Case` and `case` are two documents.

Values are kept and answered as Azure answers them:

- an `Edm.Double` takes a number or the strings `"NaN"`, `"INF"` and `"-INF"`, which come back as
  those strings
- an `Edm.Int32` out of range is refused, and so is an `Edm.Int64` sent as a string
- an `Edm.DateTimeOffset` comes back in UTC with a `Z`, to the millisecond, with no fraction when there
  is none: `2026-01-02T12:00:00+02:00` is answered as `2026-01-02T10:00:00Z`
- an `Edm.GeographyPoint` is GeoJSON. Azure accepts one with any `crs` or none, and answers it with
  WGS 84's (`"crs": {"type": "name", "properties": {"name": "EPSG:4326"}}`), the coordinates unchanged.
  A longitude outside -180 to 180 or a latitude outside -90 to 90 is refused, as in a filter, a sort or a
  scoring parameter

A lookup, `GET docs('{key}')`, answers every retrievable field in the order of the index. A field the
document does not have is `null`, an empty collection `[]`. A field with `retrievable: false`, such as
a vector field by default, is left out. `$select` answers the fields it names, `address/city` for a
sub-field, and refuses a field that is not retrievable or does not exist with `400`. A document that
does not exist is `404` with no body, as on Azure. `docs/$count` answers the bare number as
`text/plain; charset=utf-8`.

Deleting an index deletes its documents, and so does deleting its service or resetting the emulator.

---

## Queries

`POST docs/search.post.search` takes the request body of the spec, and `GET docs` the same request as
query parameters (`search`, `$top`, `$skip`, `$count`, `$select`, `searchFields`, `highlight`, ...).
Lookups and `$count` do not need the engine.

| Request | Served |
|---|---|
| `search` | The simple syntax (`queryType: simple`, the default): `+`, `\|`, `-`, `"phrase"`, `prefix*`, `( )` and `\` escapes. `*` or no text matches every document |
| `queryType: full` | The full Lucene syntax: `field:term`, `address/city:term`, `AND`/`OR`/`NOT`, fuzzy `term~1`, proximity `"a b"~3`, boosts `^2`, wildcards and `/regex/`. A field must be searchable |
| `searchMode` | `any` (the default) or `all`. As on Azure, `wifi -luxury` in `any` mode matches what has `wifi` or lacks `luxury` |
| `searchFields` | Searchable fields to search, `address/city` for a sub-field, also in complex collections |
| `$filter` | OData filters: see [Filters](#filters) |
| `$orderby` | Up to 32 clauses of sortable fields, `search.score()` and `geo.distance(field, geography'POINT(lon lat)')`, each `asc` (the default) or `desc`. Ties are broken by score, highest first. A null sorts first ascending and last descending |
| `facets` | See [Facets](#facets). `facets: []` asks for none |
| `scoringProfile`, `scoringParameters` | Text `weights` boost the fields, and `freshness`, `magnitude`, `distance` and `tag` functions boost the score: see [Scoring functions](#scoring-functions). The index's `defaultScoringProfile` applies when none is named |
| `highlight`, `highlightPreTag`, `highlightPostTag` | `@search.highlights` per field, `<em>` and `</em>` by default; custom tags are set together; a highlighted field must be searchable and retrievable. Up to five highlights per field, or as many as a suffix says: `description-10` |
| `$top`, `$skip`, `$count`, `$select` | 50 results by default and 1000 at most, `$skip` up to 100000. Past 1000, or under a `$top` larger than a page, the response carries `@search.nextPageParameters` (POST) or `@odata.nextLink` (GET), which the SDKs follow, up to the `$skip` limit |
| `minimumCoverage` | From 0 to 100; answered with `@search.coverage: 100` |
| `sessionId`, `scoringStatistics` | Accepted |

A hit carries `@search.score` and the document's retrievable fields, read from the emulator's own
store, so values come back exactly as a lookup answers them.

Text is analyzed as on Azure. `standard.lucene` (the default), `standardasciifolding.lucene`,
`simple`, `stop`, `whitespace`, `keyword`, `pattern` and the `xx.lucene` language analyzers run their
Lucene counterparts, and `analyzer` or `indexAnalyzer`/`searchAnalyzer` apply as the index says.
Scores are Lucene's BM25 with the index's `k1` and `b`, or the classic similarity.

Refused with `400`:

- `vectorQueries`, `queryType: semantic`, `semanticConfiguration`, `geo.intersects` in a filter, and
  `answers`, `captions` or `debug` other than `none`: `FeatureNotSupportedInService`
- a field in `searchFields` or `highlight` that does not exist or is not searchable, a `$select`
  field that is not retrievable, an unknown scoring profile, a missing scoring parameter, a value of
  the wrong type or out of range, and a query the full syntax cannot parse
- a `$filter` or `$orderby` that does not parse, names a field that does not exist or is not
  filterable or sortable, or breaks a rule below: Azure's `Invalid expression: ...` message, ending with
  `Parameter name: $filter` (or `$orderby`), and an empty `code`
- a facet on a field that is not facetable, or with a parameter Azure does not take

A document is searchable as soon as its batch answers: there is no indexing delay.

### Filters

A `$filter` is OData as Azure reads it: `eq`, `ne`, `gt`, `ge`, `lt`, `le`, `and`, `or`, `not` and
parentheses, where `not` binds tighter than a comparison and `and` tighter than `or`. A field must be
`filterable`; `address/city` names a sub-field. A filter takes up to 1000 comparisons and 100 levels
of parentheses, `not` and lambdas; Learn warns of a limit at hundreds of clauses without naming it, so
these numbers and their messages are floci-az's.

- **Constants:** `'text'` (with `''` for a quote), numbers, `true`/`false`, `null`, dates such as
  `2026-01-02T10:00:00Z` or with an offset, `INF`, `-INF`, `NaN` and `geography'POINT(lon lat)'`. The
  constant may stand on either side
- **Strings** compare the whole value, letter for letter, after the field's `normalizer`
  (`standard`, `lowercase`, `uppercase`, `asciifolding`, `elision`, or a custom one's token filters
  of these names); `gt` and the others compare ordinally. A bare boolean field is a test of itself
- **Null:** `eq null` matches a document without a value, and `ne` is true for one, so
  `rating ne 4` matches it. Only `eq` and `ne` take `null`. A `NaN` is a value, so `ne null` finds it,
  but it equals nothing and is neither less nor greater than anything
- **Collections** are tested with `any` and `all`: `tags/any(t: t eq 'wifi')`, `tags/any()` for a
  non-empty one, `rooms/any(r: r/type eq 'Suite' and r/price lt 100)`, which needs one room that is
  both, and lambdas inside lambdas. Azure's rules for what a lambda may hold apply, with its messages:
  strings take `eq` and `search.in` joined by `or` in `any`, and `ne`, `not eq` and `not search.in`
  joined by `and` in `all`; numbers and dates take any comparison in Disjunctive Normal Form in `any`
  and Conjunctive Normal Form in `all`; `geo.distance` takes `lt`/`le` with `or` in `any` and
  `gt`/`ge` with `and` in `all`
- **Functions:** `search.in(field, 'a, b')` (space and comma delimit, or the third argument),
  `search.ismatch(text, fields, queryType, searchMode)`, which filters by a full-text query, and
  `search.ismatchscoring`, which also adds to the score; `geo.distance(field, point)` in kilometres

### Facets

A facet expression is a facetable field and its parameters, `"rating,interval:1"`; the GET form
repeats `facet=`. The response carries `@search.facets`, with one list of buckets per expression.

- **Values:** `{"count": n, "value": v}`, the 10 most frequent first; `count:N` asks for more (`0`
  for all), and `sort:count`, `-count`, `value` or `-value` orders them
- **Ranges:** `values:2|4` gives `{"to": 2}`, `{"from": 2, "to": 4}` and `{"from": 4}`, each `from`
  included and each `to` excluded; the values must ascend. A date bound is written with milliseconds
  (`2000-01-01T00:00:00.000Z`) and a bound on a `Double` field as a double (`2.0`), as Azure writes them
- **Intervals:** `interval:N` for a number, a whole number greater than zero, or `minute`, `hour`, `day`, `week`, `month`, `quarter`
  or `year` for a date, with `timeoffset:+02:00` to bucket in another offset. Only buckets that hold
  a document are answered, as `{"count": n, "value": start}`
- `count` and `sort` do not combine with `values` or `interval`. A sub-field of a complex
  collection, such as `rooms/type`, counts each document once per value

### Scoring functions

A scoring profile's `functions` multiply the text score. Each one maps its field's value to a
closeness between 0 and 1 (`freshness`: 1 now, 0 at `boostingDuration`; `magnitude`: 0 at
`boostingRangeStart`, 1 at `boostingRangeEnd`, beyond it 1 with `constantBoostBeyondRange`;
`distance`: 1 at the point of the `referencePointParameter`, 0 at `boostingDistance` km; `tag`: 1 when
the field holds one of the tags of the `tagsParameter`), shapes it by the `interpolation`, and
contributes `boost - 1` times that. `functionAggregation` combines the contributions (`sum`,
`average`, `minimum`, `maximum` or `firstMatching`), and the score is multiplied by one plus the
result. A document without a value keeps its score. `scoringParameters` are `name-value`, a point
`here--122.1,47.6` as longitude and latitude.

### The Solr sidecar

Searches run in [Apache Solr](https://solr.apache.org/), Lucene's search server. floci-az keeps the
documents; Solr holds an index derived from them, which floci-az builds and updates before each search.
The container has no volume: a new one starts empty and is filled again from the store on the first
search.

- **Started on demand.** Nothing is pulled or started until a search service exists: its ARM create,
  a data-plane write to an undeclared service, or a restart that finds stored services or indexes
  starts the container in the background. A search sent while it starts waits up to `start-timeout-seconds`
- **Removed when not needed.** Once no search service and no index is left (the last service or the
  last index of an undeclared service deleted, or `POST /_admin/reset`), the container is removed 30
  seconds later, unless a new service wants it meanwhile. The image stays in Docker's cache
- **One container** serves every service and index, as `floci-az-search-default` (with the
  [resource namespace](../configuration/advanced/application-yml.md) when one is set), on the
  emulator's Docker network when `FLOCI_AZ_SERVICES_DOCKER_NETWORK` is set
- **Unavailable engine.** Without Docker, with the engine disabled, or when the container fails to
  start or stops answering, a search answers `503` `ServiceUnavailable` with a message that names the
  cause. A failed start is tried again after 30 seconds; a container that stops answering is
  started again by the next search

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
      engine:
        enabled: true                # false: never pull or start Solr; searches answer 503
        image: solr:10.1.0-slim
        heap: 256m
        start-timeout-seconds: 120
```

| Env var | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_SEARCH_ENABLED` | `true` | Enable/disable the service |
| `FLOCI_AZ_SERVICES_SEARCH_ENGINE_ENABLED` | `true` | Run searches in the Solr sidecar. `false` never pulls or starts it, and searches answer `503` |
| `FLOCI_AZ_SERVICES_SEARCH_ENGINE_IMAGE` | `solr:10.1.0-slim` | Solr image of the sidecar |
| `FLOCI_AZ_SERVICES_SEARCH_ENGINE_HEAP` | `256m` | Solr's JVM heap (`SOLR_HEAP`) |
| `FLOCI_AZ_SERVICES_SEARCH_ENGINE_START_TIMEOUT_SECONDS` | `120` | How long a search waits for the sidecar to start, the image pull included |
| `FLOCI_AZ_STORAGE_SERVICES_SEARCH_MODE` | global `FLOCI_AZ_STORAGE_MODE` (`memory`) | Storage mode of the Search store |

### Persistence

Services, their keys, indexes and documents share one `search` store, which follows the
[storage mode](../configuration/storage.md) (`memory` by default). With `persistent`, `hybrid` or `wal`
they survive a restart: lookups, `$count`, ETags and the statistics come back as they were, and the
Solr sidecar is started again and filled from the store. Resource
groups are kept apart, in memory, unless `resource-group-semantics` or `strict-resource-groups` is on
(see [ARM](arm.md#resource-groups)). Without either, a restarted emulator still serves the service and
its data, but its resource group answers `404`.

---

## Notes & limitations

- **Not served yet:** suggestions, autocomplete, synonym maps, indexers, data sources, skillsets, vector and semantic queries; and
  `search.analyze`. Index aliases, a stable operation from api-version `2026-04-01`, are not served
  either.
- **Searches need Docker.** They run in the Solr sidecar and answer `503` without it. The `503` and its
  message are floci-az's: Azure has no equivalent.
- **Scores are close to Azure's, not equal.** Both are Lucene BM25, but how Azure combines the
  scores of several fields is not documented. floci-az adds them up, so the numbers may differ, and so
  may the order of near ties.
- **Analyzers.** `xx.microsoft` analyzers run the Lucene analyzer of their language. Languages Lucene
  has no analyzer for (`pl.lucene`, among others), and custom analyzers, run the standard analyzer.
  Chinese runs Lucene's CJK bigram analyzer.
- **Scoring functions are approximate.** Learn gives no formula for how a function's boost and
  interpolation combine with the text score; the one under [Scoring functions](#scoring-functions) is
  floci-az's. The order they give follows Azure's intent, the numbers may differ.
- **`geo.intersects` is refused.** The Solr image has no polygon support, so a filter with it answers
  `400 FeatureNotSupportedInService`. `geo.distance` is served.
- **Facet counts are exact.** Azure may count approximately on large indexes; floci-az counts every
  document. A `week` interval starts on Monday, and a value facet of a normalized field answers the
  normalized values. Learn states neither, so both are floci-az's choices, as are the words of the
  refusals of an unknown, non-filterable, non-sortable or non-facetable field.
- **Highlights** are up to five fragments per field from Lucene's unified highlighter. Azure's
  fragment boundaries may differ.
- **Document statistics are counted at once and sized roughly.** Azure counts new documents a little
  later; floci-az counts them as the batch answers. `storageSize` is the size of the documents' JSON
  alone. Azure's also counts its index structures, so it is far larger (two small documents took 6222
  bytes on the recorded service), and
  `vectorIndexSize` is the size of their vectors' elements.
- **A batch body over 16 MB answers `413`.** The SDKs answer it by splitting the batch. Azure does not
  document its answer to an oversized body. A batch of more than 32000 actions answers Azure's own
  `400 LimitExceeded`.
- **Some document error texts are floci-az's.** The refusals of a value that is not a string, a boolean, a
  date or a point, of a complex field that is not an object, and of a property inside a complex field
  that the index does not have, take the shape of Azure's document errors, but their words are floci-az's.
  So do a `413`, and a batch whose body is not JSON. Whether `allowUnsafeKeys` also lifts the rule on a
  leading underscore is not documented; floci-az keeps that rule.
- **A key holding `/` or `?` cannot be looked up.** With `allowUnsafeKeys` a key may hold either, but
  a lookup reads the path after decoding it, so such a key is not found.
- **Index sections are stored, not checked.** Scoring profiles, analyzers, tokenizers, suggesters, CORS
  options, and the semantic and vector configuration come back as they were sent. Only their JSON type
  is checked: a suggester that names a field the index does not have is accepted, where Azure refuses
  it, and so are an analyzer name that is not defined and a vector profile whose algorithm is not.
  The one reference checked is a vector field's profile. A property a newer api-version adds is kept
  and answered too. These rules of the spec are not enforced either, and wait for the phases that use
  the sections:
  - `analyzer` only on a searchable field, never together with `searchAnalyzer` or `indexAnalyzer`
  - `searchAnalyzer` and `indexAnalyzer` set together, and neither naming a language analyzer
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
- **Some data-plane error texts are floci-az's.** The codes and messages of the index operations above
  are Azure's. The rest are descriptive and written by floci-az: the refusals of a definition
  that break a rule (`OperationNotAllowed`, with Azure's code but floci-az's words, apart from the
  missing key field), a missing `api-version` (`MissingRequiredParameter`), an unserved path
  (`ResourceNotFound`), a wrong method (`MethodNotAllowed`), and the `403` for a query key, an
  undeclared service or a service with `disableLocalAuth`. Match on the status code, as the SDKs do.
- **`Location` names no port.** Azure writes `https://{service}.search.windows.net:443/indexes(...)`
  with the port spelled out. floci-az writes the URL the request addressed, with no port added.
- **An index write can outlive its service's delete.** An index write that started before the
  service's ARM delete can finish after it, and leave that index behind for a service of the same
  name. Under the default lenient mode an index can be written to a service ARM does not hold anyway.
- **An update without `similarity` keeps the stored one.** Whether Azure treats a missing `similarity`
  on an existing index as no change or refuses it is not documented.
- **An ETag changes only on a write.** After an update with `allowIndexDowntime=true`, the recorded
  service later answered the index under a new ETag that no request explains, presumably as the index
  came back online. floci-az takes nothing offline, so its tag stays until the next write.
- **`encryptionKey` is stored, not used.** Nothing is encrypted, the Key Vault key is not checked,
  and a `free` service accepts a key, although the spec says customer-managed keys are not available
  for free services.
- **A `PUT` always answers the definition.** The spec requires `Prefer: return=representation`, which
  every SDK sends, so a `PUT` without it is answered the same way.
- **Limits are reported, not enforced.** `servicestats` reports the quotas of the service's SKU from
  Microsoft's [service limits](https://learn.microsoft.com/azure/search/search-limits-quotas-capacity)
  page (`standard` with one partition for a service ARM does not hold), and the field limits Azure
  documents. Nothing stops a service or an index beyond them.
  - indexes, indexers, data sources and skillsets: 3 on `free`, 15 on `basic`, 50 on `standard`, 200
    on `standard2` and `standard3`, 10 on the storage-optimized tiers. A `standard3` service in
    `HighDensity` mode holds 1000 of each per partition, up to 3000
  - synonym maps: 3 on `free` and `basic`, 5, 10 and 20 on `standard` to `standard3`, 10 on the
    storage-optimized tiers
  - storage and vector index size are per partition, so they grow with `partitionCount`: 15 and 5 GB
    on `basic`, 160 and 35 GB on `standard`, 512 and 150 GB on `standard2`, 1024 and 300 GB on
    `standard3`, 2048 and 150 GB on `storage_optimized_l1`, 4096 and 300 GB on `storage_optimized_l2`.
    A `free` service has 50 MB of storage and no vector quota (`null`), as the recorded one reported
  - `maxStoragePerIndex` is the service's storage, capped at 1.88 TB on `standard2`, 2.34 TB on
    `standard3` and 100 GB in `HighDensity` mode
  - the figures are those of a service created after February 2025 in a region with the higher
    capacity. Older services and some regions (such as Israel Central and South India) have smaller
    partitions on Azure. The page writes GB; the recorded `free` service reports its 50 MB as 52428800
    bytes, so floci-az counts every size in binary units

  The counters are those of the request's api-version: `aliasesCount` in the previews from
  `2021-04-30-Preview` and the stable versions from `2026-04-01`, and the two knowledge counters from
  `2026-05-01-preview` and in a stable version newer than `2026-04-01`. A real `2025-09-01` service
  answers without them. The `@odata.context` of `search.stats` and `servicestats` names the
  api-version's model (`$metadata#Microsoft.Azure.Search.V2025_09_01.ServiceStatistics`); a preview's
  is written `V2025_11_01_Preview`, following the stable form.
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
