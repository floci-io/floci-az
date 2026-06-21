# Azure Database for PostgreSQL (Flexible Server)

Compatible with the `pgjdbc` (JDBC), `psycopg`, `Npgsql`, and any libpq-speaking client.

> **Requires Docker** — each logical flexible server maps to one `postgres` container.
> The data plane (port 5432) goes **directly** to the container; floci-az only handles
> the management plane (ARM REST API). Unlike Azure SQL there is **no EULA** — the
> `postgres` image is PostgreSQL-licensed.

---

## Features

- **Flexible servers** — create, get, list, update (PATCH), delete; one Docker container per logical server
- **Databases** — create, get, list, delete (metadata only — see note below)
- **Firewall rules** — full CRUD; metadata-only (no actual IP filtering in dev mode)
- **Configurations** — get, list, put (server parameters stored as metadata)
- **Name availability check** — `POST .../checkNameAvailability`
- **Connection strings** — convenience endpoint returns JDBC, libpq URI, `psql`, and Npgsql strings
- **Mocked mode** — management plane only, no Docker, for fast `plan`/CI

---

## TLS note (local-only divergence)

Azure Database for PostgreSQL Flexible Server **enforces TLS** in the cloud
(`require_secure_transport=ON`, TLS 1.2+). The stock `postgres` image does **not** serve
TLS by default, so connection strings returned by floci-az use **`sslmode=disable`**. This
is a deliberate local-only difference; your production connection string still uses TLS.

---

## Databases are metadata-only

Creating a `Microsoft.DBforPostgreSQL/flexibleServers/databases` resource records the
database in the management plane but does **not** run `CREATE DATABASE` inside the
container (the same model floci-az uses for Azure SQL). Create the real database/schema
from your application or migration tooling (Flyway, Liquibase, EF Core, `psql`, etc.) using
the connection details from the `/connect` endpoint. The default `postgres` database is
always available.

---

## Endpoints

### ARM path (used by Azure SDKs / Terraform)

```
/subscriptions/{subscriptionId}/resourceGroups/{resourceGroup}/providers/Microsoft.DBforPostgreSQL/flexibleServers/{serverName}
/subscriptions/{subscriptionId}/resourceGroups/{resourceGroup}/providers/Microsoft.DBforPostgreSQL/flexibleServers/{serverName}/databases/{dbName}
```

### Convenience path (quick testing)

```
/{account}-postgres/flexibleServers/{serverName}
/{account}-postgres/flexibleServers/{serverName}/connect
```

The `/connect` endpoint is a **floci-az addition** — it returns all connection string formats in one call.

---

## Quickstart

### 1 — Create a server

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.DBforPostgreSQL/flexibleServers/myserver?api-version=2025-08-01" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "sku": { "name": "Standard_B1ms", "tier": "Burstable" },
    "properties": {
      "administratorLogin": "psqladmin",
      "administratorLoginPassword": "FlociAz_Strong123!",
      "version": "16",
      "storage": { "storageSizeGB": 32 }
    }
  }'
```

> First call starts the container and waits for PostgreSQL to accept connections
> (a few seconds with a cached image, longer on the first pull of `postgres:17-alpine`).

### 2 — Get connection strings

```bash
curl -s "http://localhost:4577/devstoreaccount1-postgres/flexibleServers/myserver/connect"
```

Response:

```json
{
  "server": "myserver",
  "host": "localhost",
  "port": 54983,
  "jdbcUrl": "jdbc:postgresql://localhost:54983/postgres?user=psqladmin&password=FlociAz_Strong123!&sslmode=disable",
  "uri": "postgresql://psqladmin:FlociAz_Strong123!@localhost:54983/postgres?sslmode=disable",
  "psql": "psql \"host=localhost port=54983 dbname=postgres user=psqladmin password=FlociAz_Strong123! sslmode=disable\"",
  "dotNet": "Host=localhost;Port=54983;Database=postgres;Username=psqladmin;Password=FlociAz_Strong123!;SSL Mode=Disable;"
}
```

### 3 — Connect via psql

```bash
psql "host=localhost port=54983 dbname=postgres user=psqladmin password=FlociAz_Strong123! sslmode=disable"
```

---

## SDK Connection

=== "Java (JDBC)"

    ```java
    String jdbcUrl = "jdbc:postgresql://localhost:54983/postgres"
                   + "?user=psqladmin&password=FlociAz_Strong123!&sslmode=disable";

    try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
        // use conn
    }
    ```

=== "Python (psycopg)"

    ```python
    import psycopg

    conn = psycopg.connect(
        "host=localhost port=54983 dbname=postgres "
        "user=psqladmin password=FlociAz_Strong123! sslmode=disable"
    )
    ```

=== ".NET (Npgsql)"

    ```csharp
    var connStr = "Host=localhost;Port=54983;Database=postgres;"
                + "Username=psqladmin;Password=FlociAz_Strong123!;SSL Mode=Disable;";

    using var conn = new NpgsqlConnection(connStr);
    conn.Open();
    ```

---

## Terraform / OpenTofu

```hcl
resource "azurerm_postgresql_flexible_server" "pg" {
  name                          = "myserver"
  resource_group_name           = azurerm_resource_group.rg.name
  location                      = azurerm_resource_group.rg.location
  version                       = "16"
  administrator_login           = "psqladmin"
  administrator_password        = "FlociAz_Strong123!"
  storage_mb                    = 32768
  sku_name                      = "B_Standard_B1ms"
  public_network_access_enabled = true
}

resource "azurerm_postgresql_flexible_server_database" "db" {
  name      = "appdb"
  server_id = azurerm_postgresql_flexible_server.pg.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}
```

---

## REST API Reference

### Flexible servers

| Method | Path | Description |
|---|---|---|
| `PUT` | `.../flexibleServers/{name}` | Create or update a server |
| `GET` | `.../flexibleServers/{name}` | Get server properties |
| `PATCH` | `.../flexibleServers/{name}` | Update server metadata (no container restart) |
| `DELETE` | `.../flexibleServers/{name}` | Delete server and stop its container |
| `GET` | `.../flexibleServers` | List all servers in the resource group |
| `POST` | `.../locations/{loc}/checkNameAvailability` | Check if a server name is available |

### Databases

| Method | Path | Description |
|---|---|---|
| `PUT` | `.../flexibleServers/{name}/databases/{db}` | Create or update a database (metadata) |
| `GET` | `.../flexibleServers/{name}/databases/{db}` | Get database properties |
| `DELETE` | `.../flexibleServers/{name}/databases/{db}` | Delete database (metadata) |
| `GET` | `.../flexibleServers/{name}/databases` | List all databases |

### Firewall rules

| Method | Path | Description |
|---|---|---|
| `PUT` | `.../flexibleServers/{name}/firewallRules/{rule}` | Create or update a firewall rule |
| `GET` | `.../flexibleServers/{name}/firewallRules/{rule}` | Get a firewall rule |
| `DELETE` | `.../flexibleServers/{name}/firewallRules/{rule}` | Delete a firewall rule |
| `GET` | `.../flexibleServers/{name}/firewallRules` | List all firewall rules |

### Configurations

| Method | Path | Description |
|---|---|---|
| `GET` | `.../flexibleServers/{name}/configurations/{cfg}` | Get a server parameter |
| `PUT` | `.../flexibleServers/{name}/configurations/{cfg}` | Set a server parameter (metadata) |
| `GET` | `.../flexibleServers/{name}/configurations` | List server parameters |

### Convenience (floci-az only)

| Method | Path | Description |
|---|---|---|
| `GET` | `/{account}-postgres/flexibleServers/{name}/connect` | All connection strings for the server |

---

## Configuration

```yaml
floci-az:
  services:
    postgres:
      enabled: true
      mocked: false                 # false (default) = real postgres container. true = management plane only, no Docker
      image: "postgres:17-alpine"
      startup-timeout-seconds: 60
```

In **mocked** mode (`mocked: true`) servers are created in state and report
`state=Ready` / `provisioningState=Succeeded` with no container — useful for management-plane
testing without Docker. The data plane is unavailable (no live endpoint), so the `/connect`
endpoint returns no usable port.

| Environment Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_POSTGRES_ENABLED` | `true` | Enable or disable the PostgreSQL service |
| `FLOCI_AZ_SERVICES_POSTGRES_MOCKED` | `false` | Mocked mode (management plane only, no Docker) |
| `FLOCI_AZ_SERVICES_POSTGRES_IMAGE` | `postgres:17-alpine` | Docker image to use for PostgreSQL containers |
| `FLOCI_AZ_SERVICES_POSTGRES_STARTUP_TIMEOUT_SECONDS` | `60` | Seconds to wait for PostgreSQL to become ready |

---

## Docker Compose

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock   # required for PostgreSQL containers
```

> **Sidecar ports:** PostgreSQL containers bind a random host port directly via the Docker
> daemon. These ports are **not** published on the `floci-az` service — use
> `FLOCI_AZ_SERVICES_POSTGRES_DEFAULT_PORT` (`0` = random) only if you need a fixed port.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Your App                                                    │
│                                                              │
│  ARM REST calls ──────► floci-az :4577 ──► PostgresHandler   │
│  (create server,                          (state, routing)   │
│   create database,                                           │
│   get conn strings)                                          │
│                                                              │
│  libpq / JDBC ────────────────────────────────────────────►  │
│  (SQL queries, DDL)          PostgreSQL container :54983     │
└──────────────────────────────────────────────────────────────┘
```

The management plane (ARM API) goes through floci-az on port 4577.
The data plane (PostgreSQL wire protocol) connects **directly** to the container on its dynamic port — floci-az is not in the data path.
