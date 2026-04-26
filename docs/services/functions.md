# Azure Functions

Floci-AZ emulates Azure Functions by spawning real Azure Functions runtime Docker containers
on demand and proxying HTTP invocations to them.

## Requirements

- Docker daemon reachable at `/var/run/docker.sock` (bind-mounted into the floci-az container)
- Internet access on first use to pull the runtime images

## Supported Runtimes

| Runtime | Image |
|---|---|
| `node` | `mcr.microsoft.com/azure-functions/node:4` |
| `python` | `mcr.microsoft.com/azure-functions/python:4` |
| `java` | `mcr.microsoft.com/azure-functions/java:4` |
| `dotnet` | `mcr.microsoft.com/azure-functions/dotnet-isolated:4` |

## Endpoint

```
http://localhost:4577/{accountName}-functions
```

Default account: `devstoreaccount1`

---

## Management API

### Apps

| Method | Path | Description |
|---|---|---|
| `PUT` | `/admin/apps/{appName}` | Create or update a function app |
| `GET` | `/admin/apps/{appName}` | Get a function app |
| `GET` | `/admin/apps` | List all function apps |
| `DELETE` | `/admin/apps/{appName}` | Delete a function app and all its functions |

**Create app request body:**
```json
{
  "runtime": "node",
  "environment": {
    "MY_VAR": "hello"
  }
}
```

### Functions

| Method | Path | Description |
|---|---|---|
| `PUT` | `/admin/apps/{appName}/functions/{funcName}` | Deploy a function (ZIP upload) |
| `GET` | `/admin/apps/{appName}/functions/{funcName}` | Get function details |
| `GET` | `/admin/apps/{appName}/functions` | List functions in an app |
| `DELETE` | `/admin/apps/{appName}/functions/{funcName}` | Delete a function |

**Deploy function request body:**
```json
{
  "handler": "index.handler",
  "timeoutSeconds": 60,
  "zipBase64": "<base64-encoded ZIP>"
}
```

The ZIP must contain your function code in the Azure Functions v4 layout:
```
host.json
{funcName}/function.json
{funcName}/index.js   (or handler file for the runtime)
```

### Invocation

| Method | Path | Description |
|---|---|---|
| `GET` / `POST` | `/api/{appName}/{funcName}[?...]` | Invoke an HTTP-triggered function |

---

## Warm Container Pool

By default, floci-az keeps function containers warm after first use (LIFO pool, one per function).
Containers are evicted after `idle-timeout-ms` of inactivity (default 5 minutes).

To disable warm reuse and get a fresh container per invocation:

```yaml
environment:
  FLOCI_AZ_SERVICES_FUNCTIONS_EPHEMERAL: "true"
```

---

## Embedded DNS (Docker-in-Docker)

When floci-az is itself running inside Docker, it starts an embedded UDP/53 DNS server and
injects it into every spawned function container. This lets function containers resolve
custom hostnames (configured via `floci-az.hostname` or `floci-az.dns.extra-suffixes`) to
floci-az's Docker-network IP — useful when functions connect to Blob Storage using a
hostname-based endpoint rather than a raw IP.

On the host, no DNS setup is needed; the embedded server is a no-op.

---

## Linux Native Docker — Firewall Note

On native Linux Docker (not Docker Desktop), function containers reach the host via
`host.docker.internal` (automatically mapped to `host-gateway`). If you run UFW with the
default `INPUT DROP` policy, invocations will time out. Fix:

```bash
sudo ufw allow in on docker0
```

This is not needed on Docker Desktop (macOS/Windows) or when floci-az runs inside Docker.
