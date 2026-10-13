# Emulator Endpoints

Besides the Azure APIs, floci-az serves a few endpoints of its own on the main port (`4577`). They
are not part of any Azure contract: use them for health probes, test isolation and TLS setup.

| Method | Path | Response |
|---|---|---|
| `GET` | `/_floci/health` | `200` with the health body below, or `503` when a service reports a problem |
| `GET` | `/health` | Same as `/_floci/health` |
| `GET` | `/ready` | `200` `{"status": "UP"}` once startup has finished, `503` `{"status": "DOWN"}` before |
| `GET` | `/_floci/tls-cert` | `200` with the TLS certificate (`application/x-pem-file`), or `404` with a JSON explanation when TLS is off |
| `POST` | `/_admin/reset` | `204`, after clearing the state of every service |

## Health

`GET /_floci/health` answers as soon as the HTTP server is up:

```json
{"status": "UP", "version": "0.14.0", "edition": "floci-az-always-free"}
```

| Field | Meaning |
|---|---|
| `status` | `UP`, or `DOWN` with a `503` and a `problems` map when a service reports a problem |
| `version` | The floci-az release the image was built from (`FLOCI_AZ_VERSION`), or `dev` for a local build |
| `edition` | Always `floci-az-always-free` |

The image's `HEALTHCHECK` (`/usr/local/bin/healthcheck.sh`) probes this path. `/health` is an alias
with the same body. See [Health Check](docker-compose.md#health-check) for using it from Docker Compose.

## Readiness

`GET /ready` answers `503` with `{"status": "DOWN"}` until every service has finished its startup work (for
example Service Bus `start-on-boot` or eager Cosmos engines) and again once shutdown begins. In between it
answers `200` with `{"status": "UP"}`, or `503` when a service reports a problem. `/health` does not wait for
startup to finish.

## TLS certificate

When `FLOCI_AZ_TLS_ENABLED=true`, `GET /_floci/tls-cert` returns the certificate clients should trust:
the generated CA certificate by default, or the supplied certificate file with custom TLS settings.
With TLS off, or before the certificate exists, it answers
`404` with a JSON body (`tlsEnabled`, `error`, `message`) that says which. See
[Terraform / OpenTofu](../terraform.md) for a worked example.

## Reset

`POST /_admin/reset` wipes all emulator state across every service and returns `204 No Content`.
Call it between test suites to start from a clean emulator without restarting the container:

```bash
curl -X POST http://localhost:4577/_admin/reset
```

The reset is best effort: a service whose reset fails is logged and skipped, and the others are still
cleared.

Clearing a service's state does not always stop the containers behind it. SQL, PostgreSQL, MySQL,
MariaDB, Event Hubs, Container Instances and Container Apps stop their containers as part of the
reset. Redis, Container Registry, Virtual Machines, AKS, Service Bus and Functions clear their
records only, and containers they started keep running.
