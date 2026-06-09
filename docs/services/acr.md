# Azure Container Registry

Compatible with the `azure-mgmt-containerregistry` SDK, the `az acr` CLI, Terraform's
`azurerm_container_registry`, and any ARM-speaking client for the management plane — plus **any
standard Docker client** (`docker login` / `push` / `pull`, OCI tooling) for the data plane.

## Features

- **Registry lifecycle** — create, get, update, delete; list by resource group and by subscription
- **Admin credentials** — `listCredentials` / `regenerateCredential` (username + two passwords)
- **Name availability** — `checkNameAvailability`
- **Usages** — `listUsages` (static quota report)
- **Data plane** — a single shared `registry:2` sidecar exposing the **Docker Registry HTTP API V2**
  (`/v2/…`), so images push and pull with the standard Docker client. All registries are backed by
  one container and isolated by an internal repository prefix (`{registryName}/{repo}`)

## Endpoint

Management plane (ARM) goes through port `4577`:

```
PUT|GET|PATCH|DELETE  /subscriptions/{s}/resourceGroups/{rg}/providers/Microsoft.ContainerRegistry/registries/{name}
POST                  .../registries/{name}/listCredentials | regenerateCredential | importImage
GET                   .../registries/{name}/listUsages
POST                  /subscriptions/{s}/providers/Microsoft.ContainerRegistry/checkNameAvailability
```

The **data plane** is served directly by the registry sidecar (not proxied through `4577`).

> **`loginServer` deviation (path style).** Because one shared registry backs every ACR, the registry
> name moves from the host into the path: `loginServer` is `localhost:{port}/{registryName}` natively
> (or `{container}:5000/{registryName}` when floci-az runs in Docker) — **not** `{name}.azurecr.io`.
> Docker handles this transparently: `docker login localhost:{port}` ignores the path, and an image
> ref like `localhost:{port}/{registryName}/app` parses as registry `localhost:{port}` + repo
> `{registryName}/app`. Docker treats `localhost:PORT` as insecure (plain-HTTP) automatically, so no
> daemon config is needed. The data plane is on by default (`mocked: false`). In **mocked** mode
> (no Docker) `loginServer` is the cosmetic `{name}.azurecr.io` for management-plane fidelity.

## Authentication

The shared backing registry runs **anonymous** (mirroring the AWS ECR design in the sibling emulator):
`docker push`/`pull` work without logging in. The management plane still issues admin credentials
(`listCredentials` / `regenerateCredential`, username = registry name), and `docker login` with them
succeeds — but the credentials are **not enforced** at the data plane. Real ACR enforces admin-user
basic auth and AAD tokens; reproducing per-registry auth on a single shared registry is out of scope.

## Example (non-mocked)

```bash
# create (az / terraform / raw ARM) → loginServer like localhost:5000/myregistry
docker tag busybox localhost:5000/myregistry/demo/busybox:v1
docker push localhost:5000/myregistry/demo/busybox:v1
curl http://localhost:5000/v2/_catalog     # {"repositories":["myregistry/demo/busybox", ...]}
docker pull localhost:5000/myregistry/demo/busybox:v1
```

## Configuration

```yaml
floci-az:
  services:
    acr:
      enabled: true
      mocked: false             # false (default) = one shared registry:2 for all registries. true = management plane only, no Docker
      default-image: "registry:2"
      base-port: 5000           # host port range start for registry containers
      max-port: 5099            # host port range end
```

| Env var | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_ACR_ENABLED` | `true` | Enable/disable the service |
| `FLOCI_AZ_SERVICES_ACR_MOCKED` | `false` | Mocked mode (management plane only, no Docker) |
| `FLOCI_AZ_SERVICES_ACR_DEFAULT_IMAGE` | `registry:2` | Registry container image |
| `FLOCI_AZ_SERVICES_ACR_BASE_PORT` | `5000` | Host port range start |
| `FLOCI_AZ_SERVICES_ACR_MAX_PORT` | `5099` | Host port range end |

## Out of scope (future work)

- AAD token auth (`/oauth2/token`) and the `az acr login` token flow — use the admin user + `docker login`.
- `importImage` actual layer copy (accepted as a `202` no-op).
- Geo-replication, webhooks, ACR Tasks, private link, content trust, retention/quarantine policies
  (accepted and echoed as static properties, not enforced).
- SKU behavioral differences (Basic/Standard/Premium accepted; no functional difference).
