# Azure Monitor / Log Analytics

Compatible with the `azure-monitor-ingestion` and `azure-monitor-query` SDKs, the
`Microsoft.OperationalInsights` / `Microsoft.Insights` ARM management plane, and any HTTP client.
Floci AZ emulates the **Logs Ingestion API** (push custom logs through a Data Collection Rule) and
the **Log Analytics query API** (read them back with a subset of KQL), all in-process.

> **HTTP-only: no Docker.** Workspaces, Data Collection Endpoints/Rules, ingestion, and querying
> are all in-process. There is no sidecar.

---

## Features

- **Log Analytics workspaces**: `Microsoft.OperationalInsights/workspaces` CreateOrUpdate, Get,
  Delete; a `customerId` (workspace GUID) is generated when not supplied, kept across redeploys, and
  indexed for query resolution. `sharedKeys` (and the `listKeys` form ARM templates call) answer
  `primarySharedKey` and `secondarySharedKey`, stable for the workspace's life
- **Data Collection Endpoints (DCE)**: `Microsoft.Insights/dataCollectionEndpoints` CreateOrUpdate,
  Get, Delete; the returned `logsIngestion.endpoint` points back at the emulator base URL
- **Data Collection Rules (DCR)**: `Microsoft.Insights/dataCollectionRules` CreateOrUpdate, Get,
  Delete; an `immutableId` is generated when not supplied and used to route ingestion
- **Logs Ingestion API**: `POST /dataCollectionRules/{immutableId}/streams/{stream}` accepts a JSON
  array of log records (plain or `Content-Encoding: gzip`, which the ingestion SDKs always send), resolves the DCR's Log Analytics destination, and stores each record against
  the destination workspace (`TimeGenerated` is honoured or defaulted to now)
- **Log query API**: `POST /v1/workspaces/{workspaceId}/query` runs a KQL subset over the stored
  records and returns the standard `{tables:[{name,columns,rows}]}` shape with inferred column types

---

## Endpoints

Management operations use ARM paths; ingestion and query use the Monitor data-plane paths.

```
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.OperationalInsights/workspaces/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.OperationalInsights/workspaces/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.OperationalInsights/workspaces/{name}
POST   /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.OperationalInsights/workspaces/{name}/sharedKeys
POST   /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.OperationalInsights/workspaces/{name}/listKeys

PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Insights/dataCollectionEndpoints/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Insights/dataCollectionEndpoints/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Insights/dataCollectionEndpoints/{name}

PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Insights/dataCollectionRules/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Insights/dataCollectionRules/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Insights/dataCollectionRules/{name}

POST   /dataCollectionRules/{immutableId}/streams/{stream}   # data-plane ingestion
POST   /v1/workspaces/{workspaceId}/query                    # data-plane query
```

---

## Quickstart

### 1: Create a workspace, DCE, and DCR

```bash
BASE="http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers"
API="?api-version=2023-09-01"

# Workspace: capture the returned properties.customerId for queries
curl -s -X PUT "$BASE/Microsoft.OperationalInsights/workspaces/my-ws$API" \
  -H "Content-Type: application/json" \
  -d '{"location":"eastus","properties":{}}'

# Data Collection Endpoint
curl -s -X PUT "$BASE/Microsoft.Insights/dataCollectionEndpoints/my-dce$API" \
  -H "Content-Type: application/json" \
  -d '{"location":"eastus","properties":{}}'

# Data Collection Rule: destination is the workspace; capture properties.immutableId
curl -s -X PUT "$BASE/Microsoft.Insights/dataCollectionRules/my-dcr$API" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "destinations": {
        "logAnalytics": [{
          "name": "ws",
          "workspaceResourceId": "/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.OperationalInsights/workspaces/my-ws"
        }]
      }
    }
  }'
```

### 2: Ingest logs

Post a JSON array of records to the DCR's `immutableId` and stream name:

```bash
curl -s -X POST \
  "http://localhost:4577/dataCollectionRules/<immutableId>/streams/Custom-MyTable_CL?api-version=2023-01-01" \
  -H "Content-Type: application/json" \
  -d '[
    {"TimeGenerated":"2026-06-25T10:00:00Z","Level":"INFO","Message":"hello"},
    {"TimeGenerated":"2026-06-25T10:01:00Z","Level":"ERROR","Message":"boom"}
  ]'
# → 204 No Content
```

### 3: Query logs

```python
from azure.identity import DefaultAzureCredential
from azure.monitor.query import LogsQueryClient

client = LogsQueryClient(DefaultAzureCredential(), endpoint="http://localhost:4577")
response = client.query_workspace(
    workspace_id="<customerId>",
    query="MyTable_CL | where Level == 'ERROR' | project TimeGenerated, Message | take 10",
    timespan=None,
)
for table in response.tables:
    print(table.columns, table.rows)
```

The workspace can be addressed by its `customerId` GUID or by its workspace name; the stream/table
name is matched with or without the `Custom-` prefix and the `_CL` suffix used by custom logs.

---

## Activity Log

`GET /subscriptions/{sub}/providers/Microsoft.Insights/eventtypes/management/values` serves the
subscription Activity Log the way the Activity Logs list operation does. Every management-plane PUT,
PATCH, DELETE and POST action that passes the ARM request gate is recorded as an `Administrative`
entry: a `BeginRequest` entry with status `Started` when the request enters, and an `EndRequest`
entry with `Succeeded` or `Failed`, the HTTP status word as `subStatus` and `properties.statusCode`,
when its handler answers. Reads and listings are not logged, as in Azure. Entries carry the caller and
claims of the request's bearer token, the `x-ms-correlation-request-id` and `x-ms-client-request-id`
headers, the `authorization` action and scope, the `httpRequest` method, uri and client address, and
the resource's id, type, provider, group and subscription. Operation names follow ARM:
`Microsoft.Storage/storageAccounts/write`, `.../delete`, `.../listKeys/action`,
`Microsoft.Resources/subscriptions/resourceGroups/write`.

```bash
curl -s "http://localhost:4577/subscriptions/00000000-0000-0000-0000-000000000001/providers/Microsoft.Insights/eventtypes/management/values?api-version=2015-04-01&\$filter=eventTimestamp%20ge%20'2026-01-01T00:00:00Z'%20and%20resourceGroupName%20eq%20'rg1'" | jq .
```

- **`$filter`** (required) takes `eventTimestamp ge '...'` plus `eventTimestamp le`, `resourceGroupName eq`,
  `resourceUri eq` / `resourceId eq`, `resourceProvider eq`, `correlationId eq`, `eventChannels eq`,
  `levels eq`, `operations eq`, `status eq`, `subStatus eq`, `caller eq`, `category eq` and
  `searchText eq`, joined with `and`; the list-valued fields take comma-separated values. Values are
  quoted or bare tokens, and a date-time without an offset is read as UTC, which is how
  `az monitor activity-log list` sends the window it computes. Anything else answers `400 BadRequest`,
  as does a filter without a start time; errors use this API's flat `{"code": ..., "message": ...}`
  shape rather than ARM's `error` wrapper.
- **`$select`** narrows each entry to the named properties.
- **Paging**: `activity-log.page-size` entries per page (default 1000), the rest behind `nextLink`
  with an opaque `$skiptoken`. Entries are newest first.
- **`Policy` entries**: services that evaluate policy can record
  `Microsoft.Authorization/policies/{effect}/action` entries through `ActivityLog.recordPolicyEffect`,
  with the `properties.policies` JSON the Policy category carries.
- Any `api-version` is accepted, the stable `2015-04-01` and the `2017-03-01-preview` alike.

## Supported KQL subset

The query engine implements a pragmatic subset of KQL: enough for the assertions test suites and
local dashboards typically make:

| Operator | Notes |
|---|---|
| `where <expr>` | comparisons `==`, `!=`, `>`, `<`, `>=`, `<=` against string/number/datetime columns |
| `project <cols>` | comma-separated column projection |
| `take N` / `limit N` | row cap |
| `timespan` | request-level `timespan` (ISO-8601 duration such as `PT1H`, or a start/end range) filters by `TimeGenerated` before the query runs |

Column types in the response are inferred from the data (`datetime` for `TimeGenerated`, `bool`,
`long`, `real`, otherwise `string`).

---

## Configuration

```yaml
floci-az:
  services:
    monitor:
      enabled: true
      activity-log:
        enabled: true
        capacity: 10000
        page-size: 1000
```

| Env var | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_MONITOR_ENABLED` | `true` | Enable/disable the service |
| `FLOCI_AZ_SERVICES_MONITOR_ACTIVITY_LOG_ENABLED` | `true` | Records `Administrative` Activity Log entries for every ARM write, delete and action and serves the list operation |
| `FLOCI_AZ_SERVICES_MONITOR_ACTIVITY_LOG_CAPACITY` | `10000` | Activity Log entries kept in memory; the oldest are dropped beyond it |
| `FLOCI_AZ_SERVICES_MONITOR_ACTIVITY_LOG_PAGE_SIZE` | `1000` | Entries per page of the Activity Log list operation |

Stored log records use the global `StorageBackend` (`FLOCI_AZ_STORAGE_MODE`), so logs can survive
restarts in `persistent`/`hybrid`/`wal` modes.

---

## Notes & limitations

- **KQL is a subset.** `summarize`, `extend`, `join`, `order by`, `parse`, scalar functions, and
  aggregations are not implemented: only `where` / `project` / `take` / `limit` plus `timespan`
  filtering.
- **The Activity Log is in memory** (`activity-log.capacity` entries, default 10000, `0` keeps none) and covers the
  management plane only: no `ServiceHealth`, `Alert`, `Autoscale`, `Security` or `Recommendation`
  categories, no tenant-level log (`/providers/Microsoft.Insights/eventtypes/management/values` without a
  subscription), and `authorization.role` is not reported.
- **Metrics are out of scope.** Only the logs surface (ingestion + Log Analytics query) is emulated;
  `Microsoft.Insights/metrics`, alerts, action groups, and autoscale are not.
- **Auth is permissive.** Bearer tokens are accepted but not validated (dev mode), matching the rest
  of the emulator.
- **Transformations are ignored.** A DCR's `dataFlows`/`transformKql` are stored but not applied;
  records are written to the workspace as posted.
