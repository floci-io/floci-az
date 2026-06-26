# Azure Monitor / Log Analytics

Compatible with the `azure-monitor-ingestion` and `azure-monitor-query` SDKs, the
`Microsoft.OperationalInsights` / `Microsoft.Insights` ARM management plane, and any HTTP client.
Floci AZ emulates the **Logs Ingestion API** (push custom logs through a Data Collection Rule) and
the **Log Analytics query API** (read them back with a subset of KQL), all in-process.

> **HTTP-only — no Docker.** Workspaces, Data Collection Endpoints/Rules, ingestion, and querying
> are all in-process. There is no sidecar.

---

## Features

- **Log Analytics workspaces** — `Microsoft.OperationalInsights/workspaces` CreateOrUpdate, Get,
  Delete; a `customerId` (workspace GUID) is generated when not supplied and indexed for query
  resolution
- **Data Collection Endpoints (DCE)** — `Microsoft.Insights/dataCollectionEndpoints` CreateOrUpdate,
  Get, Delete; the returned `logsIngestion.endpoint` points back at the emulator base URL
- **Data Collection Rules (DCR)** — `Microsoft.Insights/dataCollectionRules` CreateOrUpdate, Get,
  Delete; an `immutableId` is generated when not supplied and used to route ingestion
- **Logs Ingestion API** — `POST /dataCollectionRules/{immutableId}/streams/{stream}` accepts a JSON
  array of log records, resolves the DCR's Log Analytics destination, and stores each record against
  the destination workspace (`TimeGenerated` is honoured or defaulted to now)
- **Log query API** — `POST /v1/workspaces/{workspaceId}/query` runs a KQL subset over the stored
  records and returns the standard `{tables:[{name,columns,rows}]}` shape with inferred column types

---

## Endpoints

Management operations use ARM paths; ingestion and query use the Monitor data-plane paths.

```
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.OperationalInsights/workspaces/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.OperationalInsights/workspaces/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.OperationalInsights/workspaces/{name}

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

### 1 — Create a workspace, DCE, and DCR

```bash
BASE="http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers"
API="?api-version=2023-09-01"

# Workspace — capture the returned properties.customerId for queries
curl -s -X PUT "$BASE/Microsoft.OperationalInsights/workspaces/my-ws$API" \
  -H "Content-Type: application/json" \
  -d '{"location":"eastus","properties":{}}'

# Data Collection Endpoint
curl -s -X PUT "$BASE/Microsoft.Insights/dataCollectionEndpoints/my-dce$API" \
  -H "Content-Type: application/json" \
  -d '{"location":"eastus","properties":{}}'

# Data Collection Rule — destination is the workspace; capture properties.immutableId
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

### 2 — Ingest logs

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

### 3 — Query logs

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

## Supported KQL subset

The query engine implements a pragmatic subset of KQL — enough for the assertions test suites and
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
```

| Env var | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_MONITOR_ENABLED` | `true` | Enable/disable the service |

Stored log records use the global `StorageBackend` (`FLOCI_AZ_STORAGE_MODE`), so logs can survive
restarts in `persistent`/`hybrid`/`wal` modes.

---

## Notes & limitations

- **KQL is a subset.** `summarize`, `extend`, `join`, `order by`, `parse`, scalar functions, and
  aggregations are not implemented — only `where` / `project` / `take` / `limit` plus `timespan`
  filtering.
- **Metrics are out of scope.** Only the logs surface (ingestion + Log Analytics query) is emulated;
  `Microsoft.Insights/metrics`, alerts, action groups, and autoscale are not.
- **Auth is permissive.** Bearer tokens are accepted but not validated (dev mode), matching the rest
  of the emulator.
- **Transformations are ignored.** A DCR's `dataFlows`/`transformKql` are stored but not applied;
  records are written to the workspace as posted.
