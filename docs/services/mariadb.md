# Azure Database for MariaDB

Compatible with MariaDB Connector/J (JDBC), MySQL Connector/J, `mysqlclient` / `PyMySQL`,
`MySqlConnector` for .NET, and any client that speaks the MySQL protocol.

> **Requires Docker** — each logical server maps to one `mariadb` container.
> The data plane (port 3306) goes **directly** to the container; floci-az only handles
> the management plane (ARM REST API). There is **no EULA** — the `mariadb` image is
> GPL-licensed.

> **Single-server model.** Azure Database for MariaDB uses
> `Microsoft.DBforMariaDB/servers`, not `flexibleServers`. That is the real Azure resource
> type, and it is why the paths below differ from MySQL and PostgreSQL.

---

## Features

- **Servers** — create, get, list, update (PATCH), delete; one Docker container per logical server
- **Databases** — create, get, list, delete (metadata only — see note below)
- **Firewall rules** — full CRUD; metadata-only (no actual IP filtering in dev mode)
- **Configurations** — get, list, put (server parameters stored as metadata)
- **Name availability check** — `POST .../checkNameAvailability`
- **Connection strings** — convenience endpoint returns JDBC, URI, CLI, and .NET strings
- **Mocked mode** — management plane only, no Docker, for fast `plan`/CI

---

## TLS note (local-only divergence)

Azure Database for MariaDB **enforces TLS** in the cloud. The stock `mariadb` image does
**not** serve TLS by default, so connection strings returned by floci-az disable it. This is a
deliberate local-only difference; your production connection string still uses TLS.

---

## Databases are metadata-only

Creating a `Microsoft.DBforMariaDB/servers/databases` resource records the database in the
management plane but does **not** run `CREATE DATABASE` inside the container (the same model
floci-az uses for Azure SQL, PostgreSQL and MySQL). Create the real database and schema from
your application or migration tooling using the connection details from `/connect`.

---

## Endpoints

### ARM path (used by Azure SDKs / Terraform)

```
/subscriptions/{subscriptionId}/resourceGroups/{resourceGroup}/providers/Microsoft.DBforMariaDB/servers/{serverName}
/subscriptions/{subscriptionId}/resourceGroups/{resourceGroup}/providers/Microsoft.DBforMariaDB/servers/{serverName}/databases/{dbName}
```

### Convenience path (quick testing)

```
/{account}-mariadb/servers/{serverName}
/{account}-mariadb/servers/{serverName}/connect
```

The `/connect` endpoint is a **floci-az addition** — it returns all connection string formats in one call.

---

## Quickstart

### 1 — Create a server

```bash
curl -X PUT "http://localhost:4577/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/my-rg/providers/Microsoft.DBforMariaDB/servers/my-server?api-version=2018-06-01" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "administratorLogin": "mariaadmin",
      "administratorLoginPassword": "Str0ng!Passw0rd",
      "version": "10.3"
    }
  }'
```

### 2 — Get connection strings

```bash
curl "http://localhost:4577/devstoreaccount1-mariadb/servers/my-server/connect"
```

```json
{
  "server": "my-server",
  "host": "localhost",
  "port": 54322,
  "jdbcUrl": "jdbc:mariadb://localhost:54322/?user=mariaadmin&password=Str0ng!Passw0rd&useSsl=false",
  "uri": "mysql://mariaadmin:Str0ng!Passw0rd@localhost:54322",
  "mysql": "mysql --host localhost --port 54322 --user mariaadmin --password=Str0ng!Passw0rd",
  "dotNet": "Server=localhost;Port=54322;Uid=mariaadmin;Pwd=Str0ng!Passw0rd;SslMode=None;"
}
```

### 3 — Connect via the mysql CLI

```bash
mysql --host localhost --port 54322 --user mariaadmin --password=Str0ng!Passw0rd
```

---

## SDK Connection

=== "Java (JDBC)"

    ```java
    String url = "jdbc:mariadb://localhost:54322/?useSsl=false";
    try (Connection c = DriverManager.getConnection(url, "mariaadmin", "Str0ng!Passw0rd")) {
        c.createStatement().execute("CREATE DATABASE IF NOT EXISTS appdb");
    }
    ```

=== "Python (PyMySQL)"

    ```python
    import pymysql

    conn = pymysql.connect(
        host="localhost", port=54322,
        user="mariaadmin", password="Str0ng!Passw0rd",
    )
    with conn.cursor() as cur:
        cur.execute("CREATE DATABASE IF NOT EXISTS appdb")
    ```

=== ".NET (MySqlConnector)"

    ```csharp
    await using var conn = new MySqlConnection(
        "Server=localhost;Port=54322;Uid=mariaadmin;Pwd=Str0ng!Passw0rd;SslMode=None;");
    await conn.OpenAsync();
    ```

---

## Terraform / OpenTofu

```hcl
resource "azurerm_mariadb_server" "example" {
  name                         = "my-server"
  resource_group_name          = azurerm_resource_group.rg.name
  location                     = azurerm_resource_group.rg.location
  administrator_login          = "mariaadmin"
  administrator_login_password = "Str0ng!Passw0rd"
  sku_name                     = "B_Gen5_1"
  version                      = "10.3"
  ssl_enforcement_enabled      = false
}
```

---

## REST API Reference

### Servers

| Method | Path | Description |
|---|---|---|
| PUT | `.../servers/{name}` | Create or update a server |
| GET | `.../servers/{name}` | Get a server |
| GET | `.../servers` | List servers in the resource group |
| PATCH | `.../servers/{name}` | Update a server |
| DELETE | `.../servers/{name}` | Delete a server and stop its container |
| POST | `.../locations/{location}/checkNameAvailability` | Check name availability |

### Databases

| Method | Path | Description |
|---|---|---|
| PUT | `.../servers/{name}/databases/{db}` | Create a database record |
| GET | `.../servers/{name}/databases/{db}` | Get a database |
| GET | `.../servers/{name}/databases` | List databases |
| DELETE | `.../servers/{name}/databases/{db}` | Delete a database record |

### Firewall rules

| Method | Path | Description |
|---|---|---|
| PUT | `.../servers/{name}/firewallRules/{rule}` | Create or update a rule |
| GET | `.../servers/{name}/firewallRules/{rule}` | Get a rule |
| GET | `.../servers/{name}/firewallRules` | List rules |
| DELETE | `.../servers/{name}/firewallRules/{rule}` | Delete a rule |

### Configurations

| Method | Path | Description |
|---|---|---|
| GET | `.../servers/{name}/configurations/{setting}` | Get a server parameter |
| GET | `.../servers/{name}/configurations` | List server parameters |
| PUT | `.../servers/{name}/configurations/{setting}` | Set a server parameter |

### Convenience (floci-az only)

| Method | Path | Description |
|---|---|---|
| GET | `/{account}-mariadb/servers/{name}/connect` | All connection string formats |

---

## Configuration

```yaml
floci-az:
  services:
    maria-db:
      enabled: true
      mocked: false
      image: "mariadb:10.11"
      startup-timeout-seconds: 60
      default-port: 0
```

Note the config key is `maria-db`: SmallRye derives it from the `mariaDb()` accessor, so the
environment prefix is `FLOCI_AZ_SERVICES_MARIA_DB_`.

With `mocked: true` no container is started: servers are created in state and transition
immediately to `userVisibleState=Ready`, with no live endpoint.

| Environment Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_MARIA_DB_ENABLED` | `true` | Enable the service |
| `FLOCI_AZ_SERVICES_MARIA_DB_MOCKED` | `false` | Skip Docker; management plane only |
| `FLOCI_AZ_SERVICES_MARIA_DB_IMAGE` | `mariadb:10.11` | Container image per server |
| `FLOCI_AZ_SERVICES_MARIA_DB_STARTUP_TIMEOUT_SECONDS` | `60` | Readiness wait per container |
| `FLOCI_AZ_SERVICES_MARIA_DB_DEFAULT_PORT` | `0` | Host port; `0` lets the OS pick a free one |

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

> **Sidecar ports:** each server binds its own host port, chosen by the OS unless
> `default-port` is set. Read the actual port from `/connect`.

---

## Architecture

```
   client ──JDBC/3306──────────────┐
                                   ▼
   Azure SDK / Terraform     ┌───────────────┐
        │  ARM REST          │ mariadb:10.11 │
        ▼                    │  container    │
   ┌─────────────┐  docker   └───────────────┘
   │  floci-az   │──────────────┘
   └─────────────┘
```

floci-az owns the management plane only: it creates, starts and stops one `mariadb` container
per logical server and reports its host port. Client traffic never passes through the
emulator, so the wire protocol is real MariaDB rather than an emulation of it.
