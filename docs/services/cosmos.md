# Azure Cosmos DB (SQL API)

Compatible with the `azure-cosmos` SDK (Java, Python, JavaScript, .NET).

## Features

- **Databases** — create, get, list, delete (cascade-deletes all containers and documents)
- **Containers** — create, get, list, replace, delete; configurable partition key path; default indexing policy
- **Documents** — create, get, replace, delete, list; upsert via `x-ms-documentdb-is-upsert` header
- **Time to live (TTL)** — container `defaultTtl` (set on create or replace) with per-document `ttl` overrides; expired documents disappear from reads, lists, queries, and batches
- **Queries** — in-process SQL engine with full Cosmos DB SQL dialect support:
  - `SELECT *`, `SELECT c.field1, c.field2`, `SELECT VALUE c.field`, `SELECT TOP n`
  - `WHERE` with `=`, `!=`, `<>`, `>`, `>=`, `<`, `<=`, `IN`, `BETWEEN`, `NOT`, `AND`, `OR`
  - `WHERE` functions: `IS_DEFINED`, `IS_NULL`, `IS_STRING`, `IS_NUMBER`, `IS_BOOL`, `IS_ARRAY`, `IS_OBJECT`, `CONTAINS`, `STARTSWITH`, `ENDSWITH`, `ARRAY_CONTAINS`
  - `ORDER BY field [ASC|DESC]`, multiple fields
  - `OFFSET n LIMIT m` pagination
  - `SELECT VALUE COUNT(1)` aggregation
  - Named parameters (`@param`)
- **System properties** — `_rid`, `_self`, `_etag`, `_ts`, `_attachments` auto-generated on every write
- **Partition keys** — resolved from `x-ms-documentdb-partitionkey` header or extracted from document body using the container's configured path

## Endpoint

```
http://localhost:4577/{accountName}-cosmos
```

Default account: `devstoreaccount1`  
Default endpoint: `http://localhost:4577/devstoreaccount1-cosmos`

## SDK Connection

=== "Java"

    ```java
    import com.azure.cosmos.CosmosClient;
    import com.azure.cosmos.CosmosClientBuilder;

    CosmosClient client = new CosmosClientBuilder()
            .endpoint("https://localhost:4578/devstoreaccount1-cosmos")
            .key("C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==")
            .gatewayMode()
            .endpointDiscoveryEnabled(false)
            .buildClient();
    ```

=== "Python"

    ```python
    from azure.cosmos import CosmosClient

    client = CosmosClient(
        url="http://localhost:4577/devstoreaccount1-cosmos",
        credential="C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==",
        connection_verify=False,
    )
    ```

=== "JavaScript / TypeScript"

    ```typescript
    import { CosmosClient } from "@azure/cosmos";

    const client = new CosmosClient({
        endpoint: "http://localhost:4577/devstoreaccount1-cosmos",
        key: "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==",
    });
    ```

> [!TIP]
> In `dev` auth mode (the default) any key is accepted — the well-known Cosmos DB emulator key above works out of the box with all SDKs.

> [!NOTE]
> **Port difference by SDK:** The **Java SDK** enforces TLS in gateway mode and cannot use plain HTTP, so it connects
> to `https://localhost:4578` (HTTPS, bundled self-signed cert — no import required). **Python and Node.js SDKs** accept
> plain HTTP and connect to `http://localhost:4577/...`.

## API Reference

### Databases

| Method | Path | Description |
|---|---|---|
| `POST` | `/dbs` | Create a database |
| `GET` | `/dbs` | List all databases |
| `GET` | `/dbs/{dbId}` | Get a database |
| `DELETE` | `/dbs/{dbId}` | Delete a database (cascades to containers and documents) |

### Containers (Collections)

| Method | Path | Description |
|---|---|---|
| `POST` | `/dbs/{dbId}/colls` | Create a container |
| `GET` | `/dbs/{dbId}/colls` | List containers |
| `GET` | `/dbs/{dbId}/colls/{collId}` | Get a container |
| `PUT` | `/dbs/{dbId}/colls/{collId}` | Replace container properties (e.g. `defaultTtl`; id and partition key are immutable) |
| `DELETE` | `/dbs/{dbId}/colls/{collId}` | Delete a container (cascades to documents) |

### Documents

| Method | Path | Description |
|---|---|---|
| `POST` | `/dbs/{dbId}/colls/{collId}/docs` | Create a document |
| `GET` | `/dbs/{dbId}/colls/{collId}/docs` | List all documents |
| `GET` | `/dbs/{dbId}/colls/{collId}/docs/{docId}` | Get a document |
| `PUT` | `/dbs/{dbId}/colls/{collId}/docs/{docId}` | Replace a document |
| `DELETE` | `/dbs/{dbId}/colls/{collId}/docs/{docId}` | Delete a document |

### Queries

`POST /dbs/{dbId}/colls/{collId}/docs` with header `x-ms-documentdb-isquery: True` (or `Content-Type: application/query+json`).

## Request / Response Examples

### Create database

```bash
curl -X POST http://localhost:4577/devstoreaccount1-cosmos/dbs \
  -H "Content-Type: application/json" \
  -d '{"id": "mydb"}'
```

### Create container

```bash
curl -X POST http://localhost:4577/devstoreaccount1-cosmos/dbs/mydb/colls \
  -H "Content-Type: application/json" \
  -d '{"id": "items", "partitionKey": {"paths": ["/category"], "kind": "Hash"}}'
```

### Create document

```bash
curl -X POST http://localhost:4577/devstoreaccount1-cosmos/dbs/mydb/colls/items/docs \
  -H "Content-Type: application/json" \
  -H "x-ms-documentdb-partitionkey: [\"electronics\"]" \
  -d '{"id": "laptop-1", "category": "electronics", "name": "Laptop Pro", "price": 1299}'
```

### Query documents

```bash
curl -X POST http://localhost:4577/devstoreaccount1-cosmos/dbs/mydb/colls/items/docs \
  -H "Content-Type: application/query+json" \
  -H "x-ms-documentdb-isquery: True" \
  -H "x-ms-documentdb-query-enablecrosspartition: True" \
  -d '{
    "query": "SELECT * FROM c WHERE c.price > @minPrice ORDER BY c.price DESC",
    "parameters": [{"name": "@minPrice", "value": 500}]
  }'
```

### Query with COUNT

```bash
curl -X POST .../docs \
  -H "x-ms-documentdb-isquery: True" \
  -d '{"query": "SELECT VALUE COUNT(1) FROM c WHERE c.category = '\''electronics'\''"}'
```

Response: `{"_rid": "...", "_count": 1, "Documents": [2]}`

## Supported SQL functions

Full [Azure Cosmos DB SQL grammar](https://learn.microsoft.com/en-us/azure/cosmos-db/nosql/query/overview) implemented in-process.

### Predicates & type checks

| Function | Description |
|---|---|
| `IS_DEFINED(c.field)` | True if the field exists on the document |
| `IS_NULL(c.field)` | True if the field is null or missing |
| `IS_STRING(c.field)` | True if the value is a string |
| `IS_NUMBER(c.field)` | True if the value is a number |
| `IS_INTEGER(c.field)` | True if the value is an integer |
| `IS_BOOL(c.field)` | True if the value is a boolean |
| `IS_ARRAY(c.field)` | True if the value is an array |
| `IS_OBJECT(c.field)` | True if the value is an object |
| `IS_PRIMITIVE(c.field)` | True if the value is a scalar (string, number, bool, or null) |
| `CONTAINS(c.field, 'str' [, true])` | String contains; optional 3rd arg for case-insensitive |
| `STARTSWITH(c.field, 'prefix' [, true])` | String starts-with; optional 3rd arg for case-insensitive |
| `ENDSWITH(c.field, 'suffix' [, true])` | String ends-with; optional 3rd arg for case-insensitive |
| `STRINGEQUALS(c.field, 'val' [, true])` | Case-insensitive equality when 3rd arg is true |
| `REGEXMATCH(c.field, 'pattern' [, 'flags'])` | Regular-expression match |
| `ARRAY_CONTAINS(c.arr, value)` | Array contains value |
| `LIKE` | Pattern matching (`%` wildcard, `_` single char) |

### String functions

| Function | Description |
|---|---|
| `LOWER(c.field)` | Convert to lower case |
| `UPPER(c.field)` | Convert to upper case |
| `LENGTH(c.field)` | String length |
| `CONCAT(s1, s2, ...)` | Concatenate strings |
| `SUBSTRING(s, start, length)` | Extract substring |
| `TRIM(s)` | Remove leading and trailing spaces |
| `LTRIM(s)` / `RTRIM(s)` | Remove leading / trailing spaces |
| `REPLACE(s, old, new)` | Replace occurrences |
| `REVERSE(s)` | Reverse string |
| `INDEX_OF(s, sub)` | Position of first occurrence (−1 if not found) |
| `LEFT(s, n)` / `RIGHT(s, n)` | Extract leftmost / rightmost n characters |
| `TOSTRING(val)` | Convert value to string |
| `STRINGJOIN(separator, arr)` | Join array elements with separator |
| `STRINGSPLIT(s, delimiter)` | Split string into array |

### Math functions

| Function | Description |
|---|---|
| `ABS(n)` | Absolute value |
| `CEILING(n)` | Smallest integer ≥ n |
| `FLOOR(n)` | Largest integer ≤ n |
| `ROUND(n)` | Round to nearest integer |
| `SQRT(n)` | Square root |
| `POWER(base, exp)` | Exponentiation |
| `LOG(n [, base])` | Natural log or log base |
| `LOG10(n)` | Base-10 logarithm |
| `EXP(n)` | e^n |
| `SIGN(n)` | −1, 0, or 1 |
| `TRUNC(n)` | Truncate toward zero |
| `PI()` | π |
| `RAND()` | Random number in [0, 1) |

### Array functions

| Function | Description |
|---|---|
| `ARRAY_LENGTH(arr)` | Number of elements |
| `ARRAY_SLICE(arr, start [, count])` | Extract slice |
| `ARRAY_CONCAT(arr1, arr2, ...)` | Concatenate arrays |

### Conditional

| Function | Description |
|---|---|
| `IIF(condition, trueVal, falseVal)` | Inline if-else expression |

## Upsert

Set `x-ms-documentdb-is-upsert: True` on `POST /docs` to create or silently overwrite:

```bash
curl -X POST .../docs \
  -H "x-ms-documentdb-is-upsert: True" \
  -d '{"id": "laptop-1", "category": "electronics", "price": 999}'
```

## Time to live (TTL)

Containers accept Azure's `defaultTtl` property on create and replace (`-1`, or a positive
number of seconds up to `2147483647`; `0` is rejected with `400`, matching Azure). Documents
may override it with their own `ttl` property. Expiry follows the
[Azure TTL semantics](https://learn.microsoft.com/en-us/azure/cosmos-db/time-to-live), measured
from the document's last modification (`_ts`):

| Container `defaultTtl` | Document `ttl` | Result |
|---|---|---|
| absent | anything | TTL disabled — nothing expires |
| `-1` | absent or `-1` | never expires |
| `-1` | `m` | expires `m` seconds after `_ts` |
| `n` | absent | expires `n` seconds after `_ts` |
| `n` | `-1` | never expires |
| `n` | `m` | expires `m` seconds after `_ts` |

```bash
# Create a container whose documents expire after one hour
curl -X POST http://localhost:4577/devstoreaccount1-cosmos/dbs/mydb/colls \
  -H "Content-Type: application/json" \
  -d '{"id": "audit", "partitionKey": {"paths": ["/tenant"], "kind": "Hash"}, "defaultTtl": 3600}'

# Switch TTL off again (omit defaultTtl on replace)
curl -X PUT http://localhost:4577/devstoreaccount1-cosmos/dbs/mydb/colls/audit \
  -H "Content-Type: application/json" \
  -d '{"id": "audit"}'
```

Expired documents vanish from point reads, lists, queries, and transactional batches
immediately (and no longer block re-creating the same id). Physical deletion is lazy — an
expired document is purged when a read next encounters it — mirroring Azure's contract that
expired items leave query results at once while background deletion timing is unspecified.

## Storage Mode

```yaml
# docker-compose.yml
environment:
  FLOCI_AZ_STORAGE_MODE: memory
  FLOCI_AZ_STORAGE_SERVICES_COSMOS_MODE: wal    # full durability for Cosmos documents
```

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_COSMOS_ENABLED` | `true` | Enable or disable Cosmos DB |
| `FLOCI_AZ_SERVICES_COSMOS_MOCKED` | `false` | Master switch — when `true`, no engine containers are started for any API (equivalent to `engines.startup=disabled`). The in-process NoSQL/Table paths are unaffected. |

## Multi-API engines

The API reference above covers the always-on **SQL / NoSQL** endpoint (`{account}-cosmos` and
`{account}-cosmos-nosql`). Floci AZ also emulates the other Cosmos DB APIs through API-specific engines.

All engines are **disabled by default** — enable only the APIs your application uses. Four APIs are
**Docker-backed** (MongoDB, PostgreSQL, Cassandra, Gremlin) — they launch a sidecar container on first
request. Two APIs are **embedded** (NoSQL and Table) — in-process, no Docker pull, instant startup.

### Docker-backed engines

| Variable                                              | Default | Engine image                | Native port |
|-------------------------------------------------------|---------|-----------------------------|-------------|
| `FLOCI_AZ_SERVICES_COSMOS_ENGINES_MONGODB_ENABLED`    | `false` | `mongo:7`                   | `27017`     |
| `FLOCI_AZ_SERVICES_COSMOS_ENGINES_POSTGRESQL_ENABLED` | `false` | `citusdata/citus`           | `5432`      |
| `FLOCI_AZ_SERVICES_COSMOS_ENGINES_CASSANDRA_ENABLED`  | `false` | `scylladb/scylla:6.2`       | `9042`      |
| `FLOCI_AZ_SERVICES_COSMOS_ENGINES_GREMLIN_ENABLED`    | `false` | `tinkerpop/gremlin-server`  | `8182`      |

You can override the Docker image or host port for any Docker-backed engine:

| Variable                                              | Description                       |
|-------------------------------------------------------|-----------------------------------|
| `FLOCI_AZ_SERVICES_COSMOS_ENGINES_MONGODB_IMAGE`      | Override the MongoDB image        |
| `FLOCI_AZ_SERVICES_COSMOS_ENGINES_MONGODB_PORT`       | Override the MongoDB host port    |
| `FLOCI_AZ_SERVICES_COSMOS_ENGINES_STARTUP`            | `on-demand` (default) or `eager`  |

**docker-compose.yml example — enable MongoDB and PostgreSQL:**

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
      - "27017:27017"   # MongoDB (Cosmos MongoDB API)
      - "5432:5432"     # PostgreSQL (Cosmos PostgreSQL API)
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_AZ_SERVICES_COSMOS_ENGINES_MONGODB_ENABLED: "true"
      FLOCI_AZ_SERVICES_COSMOS_ENGINES_POSTGRESQL_ENABLED: "true"
```

**How it works:** when you first send a request to `/{account}-cosmos-mongo/`, floci-az pulls `mongo:7`
and starts the container. Subsequent requests go directly to the container's native port
(`localhost:27017`). The `/connect` endpoint returns the connection string:

```bash
curl http://localhost:4577/devstoreaccount1-cosmos-mongo/connect
# → {"api":"MONGODB","host":"localhost","port":27017,"connectionString":"mongodb://localhost:27017/","status":"running"}
```

> Do not publish engine ports (`27017`, `5432`, etc.) on the `floci-az` service. Engines are launched as
> sibling containers by the host Docker daemon, so they bind ports directly on the host.

### Embedded engines — NoSQL and Table API (no Docker)

Both engines run entirely inside floci-az — no Docker pull, no container boot time. Data lives in memory;
restarting floci-az clears it.

| Variable                                              | Default | Backend                                            |
|-------------------------------------------------------|---------|----------------------------------------------------|
| `FLOCI_AZ_SERVICES_COSMOS_ENGINES_NOSQL_ENABLED`      | `false` | In-process SQL engine — full Cosmos DB SQL dialect |
| `FLOCI_AZ_SERVICES_COSMOS_ENGINES_TABLE_ENABLED`      | `false` | In-memory OData engine (ConcurrentHashMap)         |

**NoSQL engine** — activating this endpoint enables the same embedded SQL engine already powering
`/{account}-cosmos`. The `/connect` endpoint returns `https://localhost:4577` as the connection URL
(Java SDK requires TLS; enable `FLOCI_AZ_TLS_ENABLED=true` and fetch the runtime cert from `GET /_floci/tls-cert`).

**Table engine** — supported operations: create/delete table · insert/get/replace/merge/delete entity ·
OData `$filter` · `$top` · `$select`. OData operators: `eq`, `ne`, `gt`, `ge`, `lt`, `le`, `and`, `or`, `not`.

```bash
# Enable the Table engine
export FLOCI_AZ_SERVICES_COSMOS_ENGINES_TABLE_ENABLED=true

# Trigger engine activation and retrieve connection string
curl http://localhost:4577/devstoreaccount1-cosmos-table/connect
# → {"api":"TABLE","status":"running","connectionString":"DefaultEndpointsProtocol=http;...","notes":"..."}
```

Use the `host` and `port` from the `/connect` response to build the endpoint, then connect
with `AzureNamedKeyCredential` — the **official Cosmos DB for Table pattern**
([quickstart](https://learn.microsoft.com/en-us/azure/cosmos-db/table/quickstart-java)):

=== "Java"

    ```java
    // official Cosmos DB for Table SDK pattern
    String endpoint = "http://" + host + ":" + port + "/devstoreaccount1-cosmos-table";

    TableServiceClient client = new TableServiceClientBuilder()
        .endpoint(endpoint)
        .credential(new AzureNamedKeyCredential(
            "devstoreaccount1",
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0=="))
        .buildClient();
    ```

=== "Python"

    ```python
    from azure.data.tables import TableServiceClient
    from azure.core.credentials import AzureNamedKeyCredential

    credential = AzureNamedKeyCredential("devstoreaccount1",
        "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==")
    client = TableServiceClient(
        endpoint=f"http://{host}:{port}/devstoreaccount1-cosmos-table",
        credential=credential)
    ```

The `connectionString` field in the `/connect` response is also available for SDK clients that prefer the
Azure Storage connection string format.

## Known Limitations

- **Stored procedures, triggers, and UDFs** are not executed.
- **JOIN** with nested arrays is not supported.
- **Change feed** is not emulated.
- **Full-text search, vector search, and geospatial queries** are not supported.
- **RU/s throughput governance and multi-region replication** are out of scope.
- Partition key paths must be a single top-level field (e.g. `/category`), not nested paths.
