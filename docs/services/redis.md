# Azure Cache for Redis

Compatible with the `azure-mgmt-redis` SDK, the `az redis` CLI, Terraform's `azurerm_redis_cache`,
and any ARM-speaking client for the management plane — plus **any standard Redis client**
(redis-py, StackExchange.Redis, Jedis, `redis-cli`, …) for the data plane.

> **Real sidecar.** When `mocked=false`, each cache is backed by a real container
> (`valkey/valkey:8-alpine` — a drop-in, RESP-compatible Redis fork) that clients connect to with
> the Redis protocol. The default in unit-test profiles is `mocked=true` (management plane only, no
> Docker).

---

## Features

- **Lifecycle** — CreateOrUpdate, Get, Delete, Patch (tags / config), List (by subscription and by resource group)
- **Access keys** — `listKeys` and `regenerateKey`; the create response embeds `properties.accessKeys`
- **Real data plane** — a Redis container per cache; the primary access key is the Redis password
  (`--requirepass`), and both the primary and secondary key authenticate via a Redis ACL on the
  `default` user
- **Async provisioning** — non-mocked caches start as `provisioningState=Creating` and flip to
  `Succeeded` once the container answers `PING`; mocked caches return `Succeeded` immediately

---

## Endpoints

All management operations use ARM paths:

```
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Cache/redis/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Cache/redis/{name}
PATCH  /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Cache/redis/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Cache/redis/{name}
POST   .../redis/{name}/listKeys
POST   .../redis/{name}/regenerateKey      # body: {"keyType":"Primary"|"Secondary"}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Cache/redis
GET    /subscriptions/{sub}/providers/Microsoft.Cache/redis
```

---

## Quickstart

### 1 — Create a cache

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.Cache/redis/my-cache?api-version=2024-11-01" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "sku": {"name": "Basic", "family": "C", "capacity": 0},
      "enableNonSslPort": true,
      "minimumTlsVersion": "1.2"
    }
  }'
```

The response contains the connection details and keys:

```json
{
  "name": "my-cache",
  "type": "Microsoft.Cache/Redis",
  "properties": {
    "provisioningState": "Succeeded",
    "hostName": "localhost",
    "port": 6379,
    "sslPort": 6380,
    "accessKeys": {"primaryKey": "…", "secondaryKey": "…"}
  }
}
```

### 2 — Connect with a Redis client

```python
import redis
client = redis.Redis(host="localhost", port=6379, password="<primaryKey>")
client.set("greeting", "hello")
print(client.get("greeting"))   # b'hello'
```

```bash
redis-cli -h localhost -p 6379 -a "<primaryKey>" ping   # PONG
```

### 3 — Rotate a key

```bash
curl -s -X POST \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.Cache/redis/my-cache/regenerateKey?api-version=2024-11-01" \
  -H "Content-Type: application/json" -d '{"keyType":"Primary"}'
```

In non-mocked mode the new key is applied to the running container immediately (no restart).

---

## Configuration

```yaml
floci-az:
  services:
    redis:
      enabled: true
      mocked: true              # true = no Docker, management plane only. false = real cache container per cache
      default-image: "valkey/valkey:8-alpine"
      base-port: 6379           # host port range start for cache containers
      max-port: 6399            # host port range end
      max-memory: "256mb"       # per-instance maxmemory
```

| Env var | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_REDIS_ENABLED` | `true` | Enable/disable the service |
| `FLOCI_AZ_SERVICES_REDIS_MOCKED` | `true` | Mocked mode (no Docker) |
| `FLOCI_AZ_SERVICES_REDIS_DEFAULT_IMAGE` | `valkey/valkey:8-alpine` | Cache container image (RESP-compatible) |
| `FLOCI_AZ_SERVICES_REDIS_BASE_PORT` | `6379` | Host port range start |
| `FLOCI_AZ_SERVICES_REDIS_MAX_PORT` | `6399` | Host port range end |
| `FLOCI_AZ_SERVICES_REDIS_MAX_MEMORY` | `256mb` | Per-instance `maxmemory` |

---

## Notes & limitations

- **Endpoint resolution.** `hostName` returns the actually-reachable host — `localhost` natively,
  or the container name when floci-az itself runs in Docker — not the real
  `{name}.redis.cache.windows.net` FQDN, so standard Redis clients can connect to the sidecar.
  `port` is the dynamically allocated host port mapped to the container's `6379`.
- **Non-SSL only (for now).** The data plane is served on the non-SSL port; `sslPort` (6380) is
  reported for API fidelity but TLS termination is not yet wired. Connect over the non-SSL port.
- **Single node.** Clustering (`shardCount`), geo-replication, private endpoints, firewall rules,
  and patch schedules are accepted on the management plane but not enforced.
- Mocked mode returns `hostName=localhost` with no backing container — useful for provisioning
  tests, but data-plane connections will fail.
