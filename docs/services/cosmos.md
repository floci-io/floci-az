# Azure Cosmos DB (SQL API)

Compatible with the `azure-cosmos` SDK (Java, Python, JavaScript, .NET).

## Features

- **Databases** — create, get, list, delete (cascade-deletes all containers and documents)
- **Containers** — create, get, list, delete; configurable partition key path; default indexing policy
- **Documents** — create, get, replace, delete, list; upsert via `x-ms-documentdb-is-upsert` header
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

## Multi-API engines

This page covers the always-on **SQL / NoSQL** endpoint (`{account}-cosmos` and `{account}-cosmos-nosql`).
For MongoDB, PostgreSQL, Cassandra, Gremlin, and Table Cosmos APIs see the
[Cosmos DB configuration reference](https://github.com/floci-io/floci-az/blob/main/README.md#cosmos-db-multi-api-engines) in the main README.

## Known Limitations

- **Stored procedures, triggers, and UDFs** are not executed.
- **JOIN** with nested arrays is not supported.
- **Change feed** is not emulated.
- **Full-text search, vector search, and geospatial queries** are not supported.
- **RU/s throughput governance and multi-region replication** are out of scope.
- Partition key paths must be a single top-level field (e.g. `/category`), not nested paths.
