# Azure Cache for Redis

Compatible with the `azure-mgmt-redis` SDK, the `az redis` CLI, Terraform's `azurerm_redis_cache`,
and any ARM-speaking client for the management plane: plus **any standard Redis client**
(redis-py, StackExchange.Redis, Jedis, `redis-cli`, …) for the data plane.

> **Real sidecar (default).** With `mocked=false` (the default), each cache is backed by a real container
> (`valkey/valkey:8-alpine`: a drop-in, RESP-compatible Redis fork) that clients connect to with
> the Redis protocol. Unit-test profiles force `mocked=true` (management plane only, no
> Docker).

---

## Features

- **Lifecycle**: CreateOrUpdate, Get, Delete, Patch (tags / config), List (by subscription and by resource group)
- **Access keys**: `listKeys` and `regenerateKey`; the create response embeds `properties.accessKeys`
- **Real data plane**: a Redis container per cache; the primary access key is the Redis password
  (`--requirepass`), and both the primary and secondary key authenticate via a Redis ACL on the
  `default` user
- **TLS on the SSL port (opt-in)**: with `FLOCI_AZ_SERVICES_REDIS_TLS_ENABLED=true`, each cache
  also serves TLS on `sslPort`, so the connection strings Azure tooling builds
  (`{hostName}:{sslPort},password=...,ssl=True`, as azurerm's `primary_connection_string`) connect
  as they are. See [TLS](#tls)
- **Async provisioning**: non-mocked caches start as `provisioningState=Creating` and flip to
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
POST   /subscriptions/{sub}/providers/Microsoft.Cache/checkNameAvailability   # body: {"name", "type"}
```

A cache name is a global DNS name: the first subscription to create it owns it in its cloud, and another
subscription's create is `409 NameNotAvailable` until the owner deletes it. `checkNameAvailability` answers `200`
with no body for a free name and the same `409 NameNotAvailable` for a taken one; Azure's answer for a taken name
is not recorded, so that one is an emulator choice.

---

## Quickstart

### 1: Create a cache

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

With floci-az running on the host, `port` is the host port published for the container's 6379,
taken from the configured range; the values above are what a cache reports when floci-az itself
runs in Docker. `sslPort` is `6380` unless [TLS](#tls) is enabled.

### 2: Connect with a Redis client

```python
import redis
client = redis.Redis(host="localhost", port=6379, password="<primaryKey>")
client.set("greeting", "hello")
print(client.get("greeting"))   # b'hello'
```

```bash
redis-cli -h localhost -p 6379 -a "<primaryKey>" ping   # PONG
```

### 3: Rotate a key

```bash
curl -s -X POST \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.Cache/redis/my-cache/regenerateKey?api-version=2024-11-01" \
  -H "Content-Type: application/json" -d '{"keyType":"Primary"}'
```

In non-mocked mode the new key is applied to the running container immediately (no restart).

---

## TLS

TLS is opt-in. By default a cache serves plain RESP on `port` only, and `sslPort` is reported as
`6380` but not served. Enable it with:

```bash
FLOCI_AZ_SERVICES_REDIS_TLS_ENABLED=true
```

or `floci-az.services.redis.tls-enabled: true` in `application.yml`. Each cache then also serves
TLS on the container's `6380`, and:

- **Two host ports per cache.** The TLS port is published on a second host port from the same
  range as the plain one (`base-port` to `max-port`), and `sslPort` reports it (the container port
  `6380` when floci-az runs in Docker). The default range of 21 ports fits 10 caches instead of
  21; widen it with `FLOCI_AZ_SERVICES_REDIS_MAX_PORT` if you need more.
- **`enableNonSslPort` defaults to `false`**, the Azure default, when a create omits it. Without
  TLS it defaults to `true`, since the plain port is then the only one served. The plain port is
  served either way.

Connect over TLS on `sslPort`, as Azure clients do by default:

```python
import redis
client = redis.Redis(host="localhost", port=<sslPort>, password="<primaryKey>",
                     ssl=True, ssl_ca_certs="floci-az-redis-ca.crt")
print(client.ping())   # True
```

```bash
redis-cli -h localhost -p <sslPort> -a "<primaryKey>" --tls --cacert floci-az-redis-ca.crt ping   # PONG
```

The SSL port is trusted the same way as floci-az's own HTTPS endpoint:

- **floci-az TLS on** (`FLOCI_AZ_TLS_ENABLED=true`): the cache's certificate is issued by the CA
  that `GET /_floci/tls-cert` serves, for the cache's `hostName`, its container name,
  `localhost`, `127.0.0.1` and `host.docker.internal`. Trust that CA once and both the HTTPS
  endpoint and every cache verify:

    ```bash
    curl -s http://localhost:4577/_floci/tls-cert -o floci-az-redis-ca.crt
    ```

    Issuing those certificates needs the CA's private key, which floci-az keeps next to the CA
    (`tls/floci-az-selfsigned-ca.key` under the persistent path, owner-only) only when it
    generates the CA with `FLOCI_AZ_SERVICES_REDIS_TLS_ENABLED=true` set in the environment.
    With a certificate of your own (`FLOCI_AZ_TLS_CERT_PATH` and `FLOCI_AZ_TLS_KEY_PATH`), or a CA
    generated without its key (Redis TLS was off then, or enabled only in `application.yml`), the
    caches serve the emulator's own certificate and key unchanged; delete the `tls` directory under
    the persistent path and restart with the environment variable set to regenerate the CA with
    its key.

- **floci-az TLS off** (the default): floci-az generates a CA in memory when it starts and issues
  each cache's certificate from it. Nothing is written to disk, and the CA changes on every
  restart. Copy it out of any cache container:

    ```bash
    docker cp floci-az-redis-<id>:/tls/ca.crt floci-az-redis-ca.crt
    ```

    Or skip verification in the client (`ssl_cert_reqs="none"` in redis-py), which is what
    `redis-cli --tls --insecure` does.

`minimumTlsVersion` is accepted and echoed but not enforced; the container accepts TLS 1.2 and 1.3.

---

## Configuration

```yaml
floci-az:
  services:
    redis:
      enabled: true
      mocked: false             # false (default) = real cache container per cache. true = no Docker, management plane only
      default-image: "valkey/valkey:8-alpine"
      base-port: 6379           # host port range start for cache containers
      max-port: 6399            # host port range end
      max-memory: "256mb"       # per-instance maxmemory
      tls-enabled: false        # true = also serve TLS on sslPort (a second host port per cache)
```

| Env var | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_REDIS_ENABLED` | `true` | Enable/disable the service |
| `FLOCI_AZ_SERVICES_REDIS_MOCKED` | `false` | Mocked mode (management plane only, no Docker) |
| `FLOCI_AZ_SERVICES_REDIS_DEFAULT_IMAGE` | `valkey/valkey:8-alpine` | Cache container image (RESP-compatible) |
| `FLOCI_AZ_SERVICES_REDIS_BASE_PORT` | `6379` | Host port range start (each cache takes one port, two with TLS enabled) |
| `FLOCI_AZ_SERVICES_REDIS_MAX_PORT` | `6399` | Host port range end |
| `FLOCI_AZ_SERVICES_REDIS_MAX_MEMORY` | `256mb` | Per-instance `maxmemory` |
| `FLOCI_AZ_SERVICES_REDIS_TLS_ENABLED` | `false` | Serve TLS on `sslPort` (see [TLS](#tls)) |

---

## Notes & limitations

- **Endpoint resolution.** `hostName` returns the actually-reachable host: `localhost` natively,
  or the container name when floci-az itself runs in Docker, not the real
  `{name}.redis.cache.windows.net` FQDN, so standard Redis clients can connect to the sidecar.
  `port` is the host port allocated from the range for the container's `6379` (the container
  port itself when floci-az runs in Docker).
- **SSL port only with TLS enabled.** By default `sslPort` (6380) is reported for API fidelity but
  nothing serves it: connect over the non-SSL port, or enable [TLS](#tls).
- **The plain port is always served.** `enableNonSslPort` is stored and reported, but the plain
  port stays open either way so existing plain-text clients keep working.
- **Single node.** Clustering (`shardCount`), geo-replication, private endpoints, firewall rules,
  and patch schedules are accepted on the management plane but not enforced.
- Mocked mode returns `hostName=localhost` with no backing container: useful for provisioning
  tests, but data-plane connections will fail.
