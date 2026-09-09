# Azure Database for MySQL (Flexible Server)

Compatible with MySQL Connector/J (JDBC), `mysqlclient` / `PyMySQL`, `MySqlConnector` for .NET,
and any client that speaks the MySQL protocol.

> **Requires Docker** — each logical flexible server maps to one `mysql` container.
> The data plane (port 3306) goes **directly** to the container; floci-az only handles
> the management plane (ARM REST API). There is **no EULA** — the `mysql` image is
> GPL-licensed.

---

## Features

- **Flexible servers** — create, get, list, update (PATCH), delete; one Docker container per logical server
- **Databases** — create, get, list, delete (metadata only — see note below)
- **Firewall rules** — full CRUD; metadata-only (no actual IP filtering in dev mode)
- **Configurations** — get, list, put (server parameters stored as metadata)
- **Name availability check** — `POST .../checkNameAvailability`
- **Connection strings** — convenience endpoint returns JDBC, URI, `mysql` CLI, and .NET strings
- **Mocked mode** — management plane only, no Docker, for fast `plan`/CI

---

## TLS note (local-only divergence)

Azure Database for MySQL Flexible Server **enforces TLS** in the cloud
(`require_secure_transport=ON`, TLS 1.2+). The stock `mysql` image does **not** serve TLS by
default, so the JDBC string returned by floci-az disables it (`useSSL=false`) and the CLI
string passes no SSL option at all. This is a deliberate local-only difference; your
production connection string still uses TLS.

---

## Databases are metadata-only

Creating a `Microsoft.DBforMySQL/flexibleServers/databases` resource records the database in
the management plane but does **not** run `CREATE DATABASE` inside the container (the same
model floci-az uses for Azure SQL and PostgreSQL). Create the real database and schema from
your application or migration tooling (Flyway, Liquibase, EF Core, `mysql`) using the
connection details from the `/connect` endpoint.

---

## Endpoints

### ARM path (used by Azure SDKs / Terraform)

```
/subscriptions/{subscriptionId}/resourceGroups/{resourceGroup}/providers/Microsoft.DBforMySQL/flexibleServers/{serverName}
/subscriptions/{subscriptionId}/resourceGroups/{resourceGroup}/providers/Microsoft.DBforMySQL/flexibleServers/{serverName}/databases/{dbName}
```

### Convenience path (quick testing)

```
/{account}-mysql/flexibleServers/{serverName}
/{account}-mysql/flexibleServers/{serverName}/connect
```

The `/connect` endpoint is a **floci-az addition** — it returns all connection string formats in one call.

---

## Quickstart

### 1 — Create a server

```bash
curl -X PUT "http://localhost:4577/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/my-rg/providers/Microsoft.DBforMySQL/flexibleServers/my-server?api-version=2023-06-30" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "administratorLogin": "mysqladmin",
      "administratorLoginPassword": "Str0ng!Passw0rd",
      "version": "8.0"
    }
  }'
```

### 2 — Get connection strings

```bash
curl "http://localhost:4577/devstoreaccount1-mysql/flexibleServers/my-server/connect"
```

```json
{
  "server": "my-server",
  "host": "localhost",
  "port": 54321,
  "jdbcUrl": "jdbc:mysql://localhost:54321/floci?user=mysqladmin&password=Str0ng!Passw0rd&useSSL=false&allowPublicKeyRetrieval=true",
  "uri": "mysql://mysqladmin:Str0ng!Passw0rd@localhost:54321/floci",
  "mysql": "mysql -h localhost -P 54321 -u mysqladmin -pStr0ng!Passw0rd floci",
  "dotNet": "Server=localhost;Port=54321;Database=floci;Uid=mysqladmin;Pwd=Str0ng!Passw0rd;SslMode=none;"
}
```

Every generated string selects **`floci`**, a database the container creates at init
(`MYSQL_DATABASE=floci`). It is ready to use immediately, unlike the `databases` ARM
resources below, which are metadata only.

`allowPublicKeyRetrieval=true` is not decoration: `mysql:8.0` authenticates with
`caching_sha2_password`, which refuses to hand over its public key over a non-TLS socket
unless the client opts in. Drop it and the connection fails with "Public Key Retrieval is
not allowed".

In mocked mode `/connect` still answers, but the server was never started, so `port` is `0`
and every string points at `localhost:0`.

### 3 — Connect via the mysql CLI

```bash
mysql -h localhost -P 54321 -u mysqladmin -pStr0ng!Passw0rd floci
```

---

## SDK Connection

=== "Java (JDBC)"

    ```java
    String url = "jdbc:mysql://localhost:54321/floci?useSSL=false&allowPublicKeyRetrieval=true";
    try (Connection c = DriverManager.getConnection(url, "mysqladmin", "Str0ng!Passw0rd")) {
        c.createStatement().execute("CREATE DATABASE IF NOT EXISTS appdb");
    }
    ```

=== "Python (PyMySQL)"

    ```python
    import pymysql

    conn = pymysql.connect(
        host="localhost", port=54321,
        user="mysqladmin", password="Str0ng!Passw0rd",
    )
    with conn.cursor() as cur:
        cur.execute("CREATE DATABASE IF NOT EXISTS appdb")
    ```

=== ".NET (MySqlConnector)"

    ```csharp
    await using var conn = new MySqlConnection(
        "Server=localhost;Port=54321;Database=floci;Uid=mysqladmin;Pwd=Str0ng!Passw0rd;SslMode=none;");
    await conn.OpenAsync();
    ```

---

## Terraform / OpenTofu

```hcl
resource "azurerm_mysql_flexible_server" "example" {
  name                   = "my-server"
  resource_group_name    = azurerm_resource_group.rg.name
  location               = azurerm_resource_group.rg.location
  administrator_login    = "mysqladmin"
  administrator_password = "Str0ng!Passw0rd"
  sku_name               = "B_Standard_B1ms"
  version                = "8.0.21"
}
```

---

## REST API Reference

### Flexible servers

| Method | Path | Description |
|---|---|---|
| PUT | `.../flexibleServers/{name}` | Create or update a server |
| GET | `.../flexibleServers/{name}` | Get a server |
| GET | `.../flexibleServers` | List servers in the resource group |
| PATCH | `.../flexibleServers/{name}` | Update a server |
| DELETE | `.../flexibleServers/{name}` | Delete a server and stop its container |
| POST | `.../locations/{location}/checkNameAvailability` | Check name availability |

### Databases

| Method | Path | Description |
|---|---|---|
| PUT | `.../flexibleServers/{name}/databases/{db}` | Create a database record |
| GET | `.../flexibleServers/{name}/databases/{db}` | Get a database |
| GET | `.../flexibleServers/{name}/databases` | List databases |
| DELETE | `.../flexibleServers/{name}/databases/{db}` | Delete a database record |

### Firewall rules

| Method | Path | Description |
|---|---|---|
| PUT | `.../flexibleServers/{name}/firewallRules/{rule}` | Create or update a rule |
| GET | `.../flexibleServers/{name}/firewallRules/{rule}` | Get a rule |
| GET | `.../flexibleServers/{name}/firewallRules` | List rules |
| DELETE | `.../flexibleServers/{name}/firewallRules/{rule}` | Delete a rule |

### Configurations

| Method | Path | Description |
|---|---|---|
| GET | `.../flexibleServers/{name}/configurations/{setting}` | Get a server parameter |
| GET | `.../flexibleServers/{name}/configurations` | List server parameters |
| PUT | `.../flexibleServers/{name}/configurations/{setting}` | Set a server parameter |

### Convenience (floci-az only)

| Method | Path | Description |
|---|---|---|
| GET | `/{account}-mysql/flexibleServers/{name}/connect` | All connection string formats |

---

## Configuration

```yaml
floci-az:
  services:
    mysql:
      enabled: true
      mocked: false
      image: "mysql:8.0"
      startup-timeout-seconds: 60
      default-port: 0
```

With `mocked: true` no container is started: servers are created in state and transition
immediately to `state=Ready`, with no live endpoint. Use it for fast CI runs and for
`terraform plan` against a machine without Docker.

| Environment Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_MYSQL_ENABLED` | `true` | Enable the service |
| `FLOCI_AZ_SERVICES_MYSQL_MOCKED` | `false` | Skip Docker; management plane only |
| `FLOCI_AZ_SERVICES_MYSQL_IMAGE` | `mysql:8.0` | Container image per server |
| `FLOCI_AZ_SERVICES_MYSQL_STARTUP_TIMEOUT_SECONDS` | `60` | Readiness wait per container |
| `FLOCI_AZ_SERVICES_MYSQL_DEFAULT_PORT` | `0` | Preferred host port; `0` lets the OS pick a free one per server |

---

## Docker Compose

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

> **Sidecar ports:** each server binds its own host port, assigned by the OS. Read the
> actual port from `/connect` or from the server's `fullyQualifiedDomainName` — do not
> assume 3306.

---

## Architecture

```
   client ──JDBC/3306──────────────┐
                                   ▼
   Azure SDK / Terraform     ┌──────────────┐
        │  ARM REST          │  mysql:8.0   │
        ▼                    │  container   │
   ┌─────────────┐  docker   └──────────────┘
   │  floci-az   │──────────────┘
   └─────────────┘
```

floci-az owns the management plane only: it creates, starts and stops one `mysql` container
per logical flexible server and reports its host port. Client traffic never passes through
the emulator, so the wire protocol is real MySQL rather than an emulation of it.
