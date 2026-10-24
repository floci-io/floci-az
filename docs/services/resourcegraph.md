# Azure Resource Graph

Implements the `Microsoft.ResourceGraph` query endpoint of the Resource Graph REST specification
(`stable/2024-04-01`): `POST /providers/Microsoft.ResourceGraph/resources` runs a Kusto Query Language
(KQL) query over the resources, resource containers and policy resources the emulator holds, and
answers in the `objectArray` or `table` result format with the service's paging contract. The
request and response shapes are verified by the emulator's own test suite over HTTP; the Azure CLI
`resource-graph` extension and the Resource Graph SDKs, which call this endpoint, have not been run
against it yet.

> **HTTP-only, no Docker.** Queries read the live state of the other services; nothing is indexed or
> persisted separately.

---

## Features

- **Tables**: `resources` (everything the ARM resource index lists, across every subscription the
  emulator knows), `resourcecontainers` (subscriptions and resource groups), `policyresources`
  (policy definitions, set definitions, assignments and exemptions) and `authorizationresources`
  (empty: role assignments and definitions are not stored). Table names are case-insensitive.
- **Columns** as Resource Graph publishes them: `id`, `name`, `type`, `tenantId`, `kind`, `location`,
  `resourceGroup`, `subscriptionId`, `managedBy`, `sku`, `plan`, `properties`, `tags`, `identity`,
  `zones`, `extendedLocation` and `systemData` (null when the resource carries none); `type`, `location`
  and `resourceGroup` are lowercased the way the service normalises them. A reference to a column the
  schema does not have, or to a function the engine does not know, is a `SemanticError` before the
  query runs, whatever the table holds, as in Kusto; properties below `properties`, `tags` and the
  other dynamic columns are data and read as null when absent. Comparisons with a null operand are null,
  so `not(x < now())` keeps rows with no `x` out, as Kusto does. Dynamic property keys (`tags.env`,
  `properties.x`) are case-sensitive and `/` on two integers is integer division, as in Kusto.
- **Shared queries** (`Microsoft.ResourceGraph/queries`, the namespace's one resource type): create,
  read, update, delete and list in a resource group or subscription, in memory and `global`, as the
  `GraphQuery` resource of the specification; they sit in the ARM resource index, so the `resources`
  table lists them and a resource-group delete removes them.
- **Operators**: `where`, `extend`, `project`, `project-away`, `summarize ... by`, `order by` /
  `sort by`, `distinct`, `take` / `limit`, `union [kind=outer|inner]`, `join kind=inner|innerunique|leftouter|leftsemi|leftanti|fullouter ... on`
  and `count`.
- **Expressions**: string (`"..."`, `'...'`, verbatim `@"..."` with doubled quotes, `\\u` escapes),
  numeric, timespan (`7d`, `30m`), boolean, `null`, `dynamic(...)` and `datetime(...)` literals;
  dotted and indexed property paths (`properties.details.parent.id`, `parse_json(x)[0].name`);
  `==` / `!=` / `=~` / `!~` / `<` / `<=` / `>` / `>=`; `in` / `!in` / `in~` / `!in~` over literals or
  dynamic arrays; `has`, `contains`, `startswith`, `endswith` and their `_cs` and `!` forms;
  `matches regex`; `between`; arithmetic; `and` / `or` / `not`.
- **Functions**: `tolower`, `toupper`, `tostring`, `toint`, `tolong`, `todouble`, `tobool`,
  `todatetime`, `totimespan`, `todynamic`, `parse_json`, `isnull`, `isnotnull`, `isempty`,
  `isnotempty`, `now`, `ago`, `strcat`, `strcat_delim`, `strlen`, `substring`, `split`, `trim`,
  `replace_string`, `replace_regex`, `indexof`, `extract`, `iff` / `iif`, `case`, `coalesce`,
  `array_length`, `array_concat`, `bag_keys`, `pack` / `bag_pack`, `pack_array`, `abs`, `round`,
  `floor`, `ceiling`, `startofday`; aggregates `count`, `countif`, `dcount`, `sum`, `avg`, `min`,
  `max`, `make_list`, `make_set`, `any` / `take_any`, `arg_max`, `arg_min`.
- **Paging** with `options.$top` (1 to 1000, default 1000), `options.$skip` and the opaque
  `options.$skipToken` the previous page returned; `totalRecords`, `count`, `resultTruncated` and
  `$skipToken` in the response, as the service answers them.
- **Scoping** with the request's `subscriptions` list; `managementGroups` is accepted and ignored.
- **Management groups** in `resourcecontainers` (`microsoft.management/managementgroups`, with their parent
  and ancestors under `properties.details`) and each subscription's `properties.managementGroupAncestorsChain`,
  direct parent first up to the tenant root group, from the emulator's management-group hierarchy.
- **Azure error shapes**: a query that does not parse or names an unknown table or function answers
  `400 BadRequest` with `details` of `InvalidQuery` plus `ParserFailure` or `SemanticError`;
  `NullOrEmptyQuery` for a missing query; `InvalidSkipToken`; `InvalidQueryOption` for a `$skip` out of range; `InvalidResourceType` for other paths
  under the namespace.

## Endpoints

```text
POST /providers/Microsoft.ResourceGraph/resources?api-version=2024-04-01   (any api-version is accepted)
```

## Quickstart

```bash
curl -s -X POST "http://localhost:4577/providers/Microsoft.ResourceGraph/resources?api-version=2024-04-01" \
  -H "Content-Type: application/json" \
  -d '{"query": "resources | where type =~ \"Microsoft.Storage/storageAccounts\" | project name, location, tags",
       "subscriptions": ["00000000-0000-0000-0000-000000000001"]}'
```

```bash
# Exemptions of one assignment
curl -s -X POST "http://localhost:4577/providers/Microsoft.ResourceGraph/resources?api-version=2024-04-01" \
  -H "Content-Type: application/json" -d @- <<'JSON'
{"query": "policyresources | where type =~ \"microsoft.authorization/policyexemptions\" | where tolower(tostring(properties.policyAssignmentId)) == tolower(\"/subscriptions/00000000-0000-0000-0000-000000000001/providers/Microsoft.Authorization/policyAssignments/a1\") | extend expiresOn = todatetime(properties.expiresOn) | project id, name, expiresOn",
 "options": {"$top": 100, "resultFormat": "objectArray"}}
JSON
```

## Configuration

```yaml
floci-az:
  services:
    resource-graph:
      enabled: true
```

| Property | Env var | Default | Description |
|---|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_RESOURCE_GRAPH_ENABLED` | `true` | Serves the `Microsoft.ResourceGraph` query endpoint. When disabled, the path falls through to the generic ARM handler and answers `404` |

---

## Intentional deviations

- **`authorizationresources` is empty** and `policyresources` holds no `microsoft.policyinsights/policystates`
  rows: role assignments, role definitions and compliance states are not modelled.
- **`properties` of `resources` rows** are what the owning service's resource index publishes: the
  services whose index entries carry the full body (storage accounts, key vaults, Network resources
  and others) expose their properties; an index entry without them answers an empty object.
- **Queries run in memory** on every request over the live state; there is no indexing lag, no
  eventual consistency and no throttling (`x-ms-user-quota-remaining` headers are not sent).
- **The KQL subset is the one Resource Graph clients send.** Unsupported constructs (`mv-expand`,
  `parse`, `let`, subqueries inside `in`, `join` kinds beyond those listed, functions beyond the list
  above) answer `400 BadRequest` with a `SemanticError` or `ParserFailure` detail.
- **Facets are not computed**: `facets` is always an empty array.
