# Azure SQL Database

Management-plane operations work without Docker by default. Set the data-plane provider to
`managed` to use `mssql-jdbc`, `pyodbc`, `System.Data.SqlClient`, or another TDS-speaking client
against a real SQL Server 2025 container.

---

## Features

- **Servers** — create, get, list, delete; immediate ARM state by default
- **Databases** — create (with optional collation), get, list, delete; guarded against dropping `master`
- **Firewall rules** — full CRUD; metadata-only (no actual IP filtering in dev mode)
- **Connection policy** — GET returns `Default` (read-only)
- **Name availability check** — `POST .../checkNameAvailability`
- **Data-plane providers** — `none` (default), `managed`, and reserved `external`
- **Asynchronous managed provisioning** — server `PUT` returns `202` with Azure `Location` polling
- **Connection strings** — managed mode returns JDBC, ADO.NET, pyodbc, and EF Core strings
- **EULA guard** — managed server creation requires `FLOCI_AZ_SERVICES_SQL_ACCEPT_EULA=Y`

---

## Data-plane providers

| Provider | Behavior |
|---|---|
| `none` | Default. Azure-compatible ARM state only; no Docker access, image pull, EULA, or TDS endpoint. |
| `managed` | Starts one real SQL Server container per logical server. Requires Docker and EULA acceptance. |
| `external` | Reserved for a later release. Requests currently return `DataPlaneProviderUnavailable`. |

When `none` is selected, `/connect` returns `409 DataPlaneNotEnabled`. ARM responses still expose
the Azure-shaped `{server}.database.windows.net` hostname and never include emulator-only port data.

The deprecated `mocked` setting remains a compatibility alias when `data-plane.provider` is absent:
`true` maps to `none`; `false` maps to `managed`.

For upgrade compatibility, `accept-eula: "Y"` also selects `managed` when neither
`data-plane.provider` nor `mocked` is configured. An explicit provider or legacy `mocked` value
always takes precedence.

Managed server creation does not wait for image pull or engine startup on the request thread. A new
server is persisted with `state=Creating` and returns `202 Accepted`, `Location`, and `Retry-After`.
Poll `Location` until it returns `200` with `status=Succeeded`; failed provisioning returns a non-2xx
ARM error envelope containing the provisioning error code and message. Server GET exposes the
corresponding `Creating`, `Ready`, or `Failed` resource state. Equivalent PUT retries reuse the same
operation; conflicting updates during provisioning return `409 ConflictingServerOperation`.

---

## EULA Requirement (managed provider only)

SQL Server is covered by the [Microsoft SQL Server EULA](https://go.microsoft.com/fwlink/?linkid=857698).
You must explicitly accept it before the emulator will start any container:

```bash
# Environment variable (docker-compose / CLI)
FLOCI_AZ_SERVICES_SQL_ACCEPT_EULA=Y

# JVM system property (quarkus:dev)
-Dfloci-az.services.sql.accept-eula=Y
```

Without it, managed `PUT /servers/{name}` returns:

```json
{ "error": { "code": "EulaNotAccepted", "message": "..." } }
```

---

## Endpoints

### ARM path (used by Azure SDKs)

```
/subscriptions/{subscriptionId}/resourceGroups/{resourceGroup}/providers/Microsoft.Sql/servers/{serverName}
/subscriptions/{subscriptionId}/resourceGroups/{resourceGroup}/providers/Microsoft.Sql/servers/{serverName}/databases/{dbName}
```

### Convenience path (quick testing)

```
/{account}-sql/servers/{serverName}
/{account}-sql/servers/{serverName}/databases/{dbName}
/{account}-sql/servers/{serverName}/connect
/{account}-sql/servers/{serverName}/databases/{dbName}/connect
```

The `/connect` endpoints are a **floci-az addition** — they return all connection string formats in one call.

---

## Quickstart

This data-plane quickstart assumes `data-plane.provider: managed`, Docker access, and accepted EULA.
For ARM-only use, keep the default `none` provider and stop after creating management resources.

### 1 — Create a server

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.Sql/servers/myserver?api-version=2021-11-01" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "administratorLogin": "sa",
      "administratorLoginPassword": "YourStrong!Passw0rd"
    }
  }'
```

> In managed mode, first call returns promptly with `202 Accepted`; image pull and SQL Server startup
> continue in the background. Follow the `Location` header before requesting connection strings.

### 2 — Get connection strings

```bash
curl -s "http://localhost:4577/devstoreaccount1-sql/servers/myserver/connect"
```

Response:

```json
{
  "server": "myserver",
  "host": "localhost",
  "port": 59743,
  "jdbcUrl": "jdbc:sqlserver://localhost:59743;databaseName=master;user=sa;password=YourStrong!Passw0rd;encrypt=true;trustServerCertificate=true;",
  "connectionString": "Server=tcp:localhost,59743;Initial Catalog=master;...",
  "pyodbc": "DRIVER={ODBC Driver 18 for SQL Server};SERVER=localhost,59743;...",
  "entityFramework": "Server=localhost,59743;Database=master;..."
}
```

### 3 — Create a database

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.Sql/servers/myserver/databases/mydb?api-version=2021-11-01" \
  -H "Content-Type: application/json" \
  -d '{"location": "eastus", "properties": {}}'
```

### 4 — Connect via JDBC

```java
String jdbcUrl = "jdbc:sqlserver://localhost:59743;"
               + "databaseName=mydb;user=sa;password=YourStrong!Passw0rd;"
               + "encrypt=true;trustServerCertificate=true;";

try (Connection conn = DriverManager.getConnection(jdbcUrl);
     Statement  stmt = conn.createStatement()) {
    stmt.executeUpdate("CREATE TABLE greet (msg NVARCHAR(100))");
    stmt.executeUpdate("INSERT INTO greet VALUES ('Hello from floci-az!')");
    ResultSet rs = stmt.executeQuery("SELECT msg FROM greet");
    while (rs.next()) System.out.println(rs.getString(1));
}
```

> **Important:** use `encrypt=true;trustServerCertificate=true` to accept the container's
> self-signed certificate while retaining encrypted connections with `mssql-jdbc` 12.x.

---

## SDK Connection

=== "Java (JDBC)"

    ```java
    // pom.xml
    // <dependency>
    //   <groupId>com.microsoft.sqlserver</groupId>
    //   <artifactId>mssql-jdbc</artifactId>
    //   <version>12.6.1.jre11</version>
    // </dependency>

    String jdbcUrl = "jdbc:sqlserver://localhost:59743;"
                   + "databaseName=mydb;"
                   + "user=sa;"
                   + "password=YourStrong!Passw0rd;"
                   + "encrypt=true;"
                   + "trustServerCertificate=true;";

    try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
        // use conn
    }
    ```

=== "Python (pyodbc)"

    ```python
    import pyodbc

    conn_str = (
        "DRIVER={ODBC Driver 18 for SQL Server};"
        "SERVER=localhost,59743;"
        "DATABASE=mydb;"
        "UID=sa;"
        "PWD=YourStrong!Passw0rd;"
        "TrustServerCertificate=yes;"
        "Encrypt=yes;"
    )

    conn = pyodbc.connect(conn_str)
    cursor = conn.cursor()
    cursor.execute("SELECT @@VERSION")
    print(cursor.fetchone()[0])
    ```

=== "Python (SQLAlchemy)"

    ```python
    from sqlalchemy import create_engine, text

    engine = create_engine(
        "mssql+pyodbc://sa:YourStrong!Passw0rd@localhost:59743/mydb"
        "?driver=ODBC+Driver+18+for+SQL+Server"
        "&TrustServerCertificate=yes"
        "&Encrypt=yes",
        echo=False,
    )

    with engine.connect() as conn:
        result = conn.execute(text("SELECT @@VERSION"))
        print(result.scalar())
    ```

=== ".NET (ADO.NET)"

    ```csharp
    var connStr = "Server=tcp:localhost,59743;"
                + "Initial Catalog=mydb;"
                + "User ID=sa;"
                + "Password=YourStrong!Passw0rd;"
                + "Encrypt=True;"
                + "TrustServerCertificate=True;"
                + "Connection Timeout=30;";

    using var conn = new SqlConnection(connStr);
    conn.Open();
    ```

=== ".NET (EF Core)"

    ```csharp
    // In Program.cs / Startup.cs
    builder.Services.AddDbContext<AppDbContext>(options =>
        options.UseSqlServer(
            "Server=localhost,59743;Database=mydb;"
            + "User Id=sa;Password=YourStrong!Passw0rd;"
            + "Encrypt=True;TrustServerCertificate=True;"));
    ```

---

## Getting the Port

The container port is dynamically assigned by the OS. Retrieve it from:

**The `/connect` response:**

```bash
PORT=$(curl -s "http://localhost:4577/devstoreaccount1-sql/servers/myserver/connect" \
       | python3 -c "import sys,json; print(json.load(sys.stdin)['port'])")
```

ARM resources intentionally omit the emulator-specific port. Use `/connect` for reachable endpoint
details in data-plane modes.

---

## REST API Reference

### Servers

| Method | Path | Description |
|---|---|---|
| `PUT` | `.../servers/{name}` | Create or update a server |
| `GET` | `.../servers/{name}` | Get server properties |
| `DELETE` | `.../servers/{name}` | Delete server and stop its container |
| `GET` | `.../servers` | List all servers in the resource group |
| `POST` | `.../checkNameAvailability` | Check if a server name is available |

### Databases

| Method | Path | Description |
|---|---|---|
| `PUT` | `.../servers/{name}/databases/{db}` | Create or update a database |
| `GET` | `.../servers/{name}/databases/{db}` | Get database properties |
| `DELETE` | `.../servers/{name}/databases/{db}` | Drop database (blocked for `master`) |
| `GET` | `.../servers/{name}/databases` | List all databases |

### Firewall Rules

| Method | Path | Description |
|---|---|---|
| `PUT` | `.../servers/{name}/firewallRules/{rule}` | Create or update a firewall rule |
| `GET` | `.../servers/{name}/firewallRules/{rule}` | Get a firewall rule |
| `DELETE` | `.../servers/{name}/firewallRules/{rule}` | Delete a firewall rule |
| `GET` | `.../servers/{name}/firewallRules` | List all firewall rules |

### Connection Policy

| Method | Path | Description |
|---|---|---|
| `GET` | `.../servers/{name}/connectionPolicies/default` | Get connection policy (always returns `Default`) |

### Convenience (floci-az only)

| Method | Path | Description |
|---|---|---|
| `GET` | `/{account}-sql/servers/{name}/connect` | All connection strings for the server |
| `GET` | `/{account}-sql/servers/{name}/databases/{db}/connect` | All connection strings for a database |

---

## Configuration

```yaml
floci-az:
  services:
    sql:
      enabled: true
      data-plane:
        provider: managed                           # none (default) | managed | external (reserved)
      accept-eula: "Y"                              # Required to start containers
      image: "mcr.microsoft.com/mssql/server:2025-latest"
      startup-timeout-seconds: 60
```

Microsoft supports SQL Server Linux container images only on Intel and AMD x86-64 hosts.
ARM64 hosts require an explicitly configured alternative image or unsupported CPU emulation.

| Environment Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_SQL_ENABLED` | `true` | Enable or disable the SQL service |
| `FLOCI_AZ_SERVICES_SQL_DATA_PLANE_PROVIDER` | `none` | Data-plane provider: `none`, `managed`, or reserved `external` |
| `FLOCI_AZ_SERVICES_SQL_MOCKED` | _(unset)_ | Deprecated alias used only when provider is unset: `true` = `none`, `false` = `managed` |
| `FLOCI_AZ_SERVICES_SQL_ACCEPT_EULA` | `N` | Set to `Y` to accept the Microsoft SQL Server EULA in managed mode |
| `FLOCI_AZ_SERVICES_SQL_IMAGE` | `mcr.microsoft.com/mssql/server:2025-latest` | Docker image to use for SQL Server containers |
| `FLOCI_AZ_SERVICES_SQL_STARTUP_TIMEOUT_SECONDS` | `60` | Seconds to wait for the SQL Server engine to become ready |

---

## Docker Compose

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
      - "4578:4578"        # HTTPS (Cosmos Java SDK)
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock   # required for SQL + Functions
    environment:
      FLOCI_AZ_SERVICES_SQL_DATA_PLANE_PROVIDER: managed
      FLOCI_AZ_SERVICES_SQL_ACCEPT_EULA: "Y"
      # SQL Server containers bind a random port directly to the host.
      # Do NOT add those ports here — floci-az manages them via Docker socket.
```

> **Sidecar ports:** SQL Server containers bind a random host port directly via the Docker daemon.
> These ports are **not** published on the `floci-az` service — add them to the `floci-az` service
> only if you need a fixed port (use `FLOCI_AZ_SERVICES_SQL_DEFAULT_PORT`; `0` = random).

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Your App                                                    │
│                                                              │
│  ARM REST calls ──────► floci-az :4577 ──► SqlHandler        │
│  (create server,                          (state, routing)   │
│   create database,                                           │
│   get conn strings)                                          │
│                                                              │
│  TDS / JDBC ──────────────────────────────────────────────►  │
│  (SQL queries, DDL)          SQL Server container :59743     │
└──────────────────────────────────────────────────────────────┘
```

The management plane (ARM API) always goes through floci-az on port 4577. With `provider=managed`,
the data plane connects **directly** to the SQL Server container on its dynamic port. With
`provider=none`, no data-plane container or port exists.
