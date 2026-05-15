# Ports Reference

Floci-AZ uses a **single port** for all services — no per-service port mapping needed.

| Port | Protocol | Services |
|---|---|---|
| `4577` | HTTP | Blob, Queue, Table, Functions, App Configuration |

Services are distinguished by URL path prefix:

| Service | Path prefix |
|---|---|
| Blob Storage | `/{account}/` |
| Queue Storage | `/{account}-queue/` |
| Table Storage | `/{account}-table/` |
| Azure Functions | `/{account}-functions/` |
| App Configuration | `/{account}-appconfig/` |

Change the port with `FLOCI_AZ_PORT`:

```yaml
environment:
  FLOCI_AZ_PORT: "4578"
```

When changing the port, also update `FLOCI_AZ_BASE_URL` so URLs embedded in API responses (SAS tokens, operation locations) are correct:

```yaml
environment:
  FLOCI_AZ_PORT: "4578"
  FLOCI_AZ_BASE_URL: "http://localhost:4578"
```
