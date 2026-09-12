# Azure Container Registry

Compatible with the `azure-mgmt-containerregistry` SDK, the `az acr` CLI, Terraform's
`azurerm_container_registry`, and any ARM-speaking client for the management plane, plus **any
standard Docker client** (`docker login` / `push` / `pull`, OCI tooling) for the data plane,
either anonymously on the registry container's published port or with an Entra token on
`{name}.azurecr.io`.

## Features

- **Registry lifecycle**: create, get, update, delete; list by resource group and by subscription
- **Admin credentials**: `listCredentials` / `regenerateCredential` (username + two passwords)
- **Name availability**: `checkNameAvailability`
- **Usages**: `listUsages` (static quota report)
- **Entra token exchange**: the bearer challenge on `GET /v2/`, `POST /oauth2/exchange` and
  `/oauth2/token`, so `az acr login` and the Azure SDK container-registry clients work
- **Data plane**: a single shared `registry:2` sidecar exposing the **Docker Registry HTTP API V2**
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

The data plane has two surfaces onto the same storage.

**The registry container's published port** is the zero-configuration one. It is plain HTTP,
anonymous, and Docker treats `localhost:PORT` as insecure automatically, so nothing has to be
configured:

```
localhost:5000/{registryName}/{repo}
```

The port comes from the `base-port`-`max-port` range below and is allocated when the shared
container starts, on the first registry you create. It is `5000` unless that port is taken, and
because `loginServer` names the Azure host it cannot carry the port, so the registry resource
reports it as `properties.localPort`:

```bash
az acr show -n myregistry -g my-rg --query properties.localPort   # 5000
```

This is a floci-az convenience field, not part of the real ARM contract, and it is the same field
[PostgreSQL](postgresql.md) and [MySQL](mysql.md) servers use to report the port their container
published. It is absent in `mocked` mode, where no container runs. The emulator also logs the port
at startup, and `docker port floci-az-acr-registry 5000` reports it:

```text
INFO  [io.flo.az.ser.acr.AcrRegistryManager] Started shared ACR registry floci-az-acr-registry on host port 5000
```

**`{name}.azurecr.io`** is what `loginServer` reports and what Azure-native clients use. It is
served by floci-az on 443 when TLS is on (`FLOCI_AZ_TLS_ENABLED=true`), and carries the endpoints
those clients need:

```
GET       {name}.azurecr.io/v2/                 bearer challenge
POST      {name}.azurecr.io/oauth2/exchange     Entra access token → ACR refresh token
GET|POST  {name}.azurecr.io/oauth2/token        ACR refresh token → scoped access token
*         {name}.azurecr.io/v2/**               Docker Registry HTTP API V2
```

The registry name is not part of a repository reference here: `{name}.azurecr.io/app` is the
repository `app`, exactly as in Azure. floci-az adds the `{registryName}/` prefix internally when it
proxies to the shared container, and strips it again from upload `Location` headers, `tags/list` and
`_catalog`.

> **Name resolution is required.** `{name}.azurecr.io` must resolve to floci-az, the same
> requirement `*.vault.azure.net` already carries for Key Vault. Add a hosts entry (or point your
> resolver at the emulator) and trust the emulator's certificate, which carries `*.azurecr.io`:
>
> ```bash
> echo "127.0.0.1 myregistry.azurecr.io" | sudo tee -a /etc/hosts
> curl -s http://localhost:4577/_floci/tls-cert -o floci-az.crt   # then trust it
> ```
>
> Docker needs the certificate in `/etc/docker/certs.d/myregistry.azurecr.io/ca.crt`. Without name
> resolution, use the published port instead: it needs none of this.
>
> A hosts entry points one name at the emulator, but a wildcard resolver points all of them, so
> floci-az serves `{name}.azurecr.io` only for a registry that was actually created. A name with no
> registry behind it answers `404 NAME_UNKNOWN` at every endpoint, challenge and token included,
> rather than minting tokens for something that does not exist.
>
> TLS is off by default. The challenge realm carries the scheme the request arrived with (honouring
> `X-Forwarded-Proto` behind a terminating proxy), so the token flow is exercisable over plain HTTP,
> but `az acr login` and Docker both address a named registry as `https://` and need TLS on.

## Authentication

`az acr login` and the SDK container-registry clients perform Azure's Entra token exchange rather
than a Docker login: `GET /v2/` answers `401` with a `Bearer` challenge naming the realm and
service, an Entra access token is traded for a registry refresh token at `/oauth2/exchange`, and
that refresh token is traded for a scoped access token at `/oauth2/token`. Docker then logs in with
the username `00000000-0000-0000-0000-000000000000` and the access token as the password. floci-az
serves all of it, in both `mocked` modes.

Intentional deviations, all of them a consequence of the emulator's existing stance that ARM and
Shared Key credentials are accepted without verification:

- **Tokens are issued, not verified.** Both tokens are real RS256 JWTs signed by the emulator's
  Entra signing key, so clients that decode them keep working (the Azure CLI reads the access
  token's `access` claim). Nothing checks the Entra token presented at `/oauth2/exchange`, and
  nothing checks the refresh token presented at `/oauth2/token`.
- **No authorization is enforced.** The requested scope is recorded in the access token's `access`
  claim and then ignored: any token, and any identity, reaches every repository. Only `GET /v2/`
  challenges; repository paths are served whether or not a token is presented. Admin credentials
  are accepted at the token endpoint so the `password` grant is truthful, but they are not required.
- **The shared backing registry runs anonymous** (mirroring the AWS ECR design in the sibling
  emulator), so its published port serves push and pull with no credentials at all.

Real ACR enforces per-registry auth and repository-scoped permissions; reproducing that on a single
shared registry is out of scope.

## Example

Anonymous, through the published port. `5000` below is the first free port in the configured range,
so substitute the one the startup log reports (`Started shared ACR registry ... on host port`):

```bash
docker tag busybox localhost:5000/myregistry/demo/busybox:v1
docker push localhost:5000/myregistry/demo/busybox:v1
curl http://localhost:5000/v2/_catalog     # {"repositories":["myregistry/demo/busybox", ...]}
docker pull localhost:5000/myregistry/demo/busybox:v1
```

The same image, through the Azure login server (hosts entry and trusted certificate in place):

```bash
az acr create -n myregistry -g my-rg --sku Basic     # loginServer: myregistry.azurecr.io
az acr login -n myregistry
docker tag busybox myregistry.azurecr.io/demo/busybox:v1
docker push myregistry.azurecr.io/demo/busybox:v1
docker pull myregistry.azurecr.io/demo/busybox:v1
```

`az acr login --expose-token` returns the refresh token without needing a Docker daemon.

In **mocked** mode (no Docker) the management plane and the whole auth surface still work;
repository operations answer with the registry's `UNSUPPORTED` error, since no container is running.

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

- Token signature verification, repository-level authorization and scope enforcement.
- Scope maps and tokens as ACR resources, content trust, quarantine, anonymous pull configuration.
- `importImage` actual layer copy (accepted as a `202` no-op).
- Geo-replication, webhooks, ACR Tasks, private link, retention policies
  (accepted and echoed as static properties, not enforced).
- SKU behavioral differences (Basic/Standard/Premium accepted; no functional difference).
