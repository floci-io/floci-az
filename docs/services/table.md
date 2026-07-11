# Table Storage

Compatible with the `azure-data-tables` SDKs (Java, Python, Node.js) and Azurite-style connection
strings. Speaks the Azure Table REST protocol with OData/JSON payloads and Shared Key
authentication.

> **HTTP-only — no Docker.** Data is held by the configured [storage backend](../configuration/storage.md)
> (`memory` by default; `persistent`, `hybrid`, or `wal` for durability).

---

## Features

- **Tables** — Create, Delete, List; duplicate create returns `409 TableAlreadyExists`
- **Entities** — Insert, Update (replace/merge), Upsert, Delete, Get by
  `PartitionKey` + `RowKey`; missing entity returns the Azure `404 ResourceNotFound` shape
- **Query** — `$filter` OData expressions: equality on `PartitionKey`/`RowKey`, numeric
  comparisons, and combinations; `$select` projects a subset of properties
- **Pagination** — large result sets are paged with continuation tokens
  (`x-ms-continuation-Next*` headers)
- **Optimistic concurrency** — `ETag` / `If-Match` on update and delete; a stale ETag is rejected
- **Batch transactions** — `$batch` multipart change sets are applied atomically

## Endpoint

```
http://localhost:4577/{account}-table/Tables                                   # table operations
http://localhost:4577/{account}-table/{table}(PartitionKey='p',RowKey='r')     # entity operations
http://localhost:4577/{account}-table/$batch                                   # batch transactions
```

## Quickstart

=== "Python"

    ```python
    from azure.data.tables import TableServiceClient

    conn = ("DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
            "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==;"
            "TableEndpoint=http://localhost:4577/devstoreaccount1-table;")
    svc = TableServiceClient.from_connection_string(conn)
    table = svc.create_table("people")
    table.upsert_entity({"PartitionKey": "team", "RowKey": "alice", "role": "eng"})
    for e in table.query_entities("PartitionKey eq 'team'"):
        print(e["RowKey"], e["role"])
    ```

The well-known Azurite development key (`Eby8vdM0…`) is accepted; any account name works.

## Configuration

```yaml
floci-az:
  services:
    table:
      enabled: true
  storage:
    services:
      table:
        # mode: persistent     # override the global storage.mode for table only
        flush-interval-ms: 5000
```

| Property | Env var | Default | Description |
|---|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_TABLE_ENABLED` | `true` | Enables the Table Storage service |
| `storage.services.table.mode` | `FLOCI_AZ_STORAGE_SERVICES_TABLE_MODE` | *(inherits `storage.mode`)* | Per-service backend override (`memory` / `persistent` / `hybrid` / `wal`) |
| `storage.services.table.flush-interval-ms` | `FLOCI_AZ_STORAGE_SERVICES_TABLE_FLUSH_INTERVAL_MS` | `5000` | Background flush-to-disk interval for the `hybrid` mode only; ignored by `memory` / `persistent` / `wal` (`wal` compacts on `storage.wal.compaction-interval-ms` instead) |

## Intentional deviations

- **Shared Key signatures are accepted but not cryptographically verified.**
- **The `$filter` grammar is a practical subset** — equality, numeric comparison, and basic
  boolean composition are supported; full OData functions (`substringof`, `startswith`, datetime
  arithmetic) are not.
- **No SAS enforcement** — SAS query parameters are parsed but not validated.
