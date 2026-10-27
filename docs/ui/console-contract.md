# Floci Console Contract v2

**Status:** draft. Implemented by floci-az and floci-aws; Floci UI follows.
**Audience:** authors of a web console that runs against a Floci emulator, and authors of an emulator that
starts one.

A Floci emulator can run a web console as a sidecar container. This page is the contract between the two,
for every cloud: an emulator that implements it can start any conforming console, and a console that
implements it learns everything it needs from its environment and from the emulator's manifest.

v2 only adds to floci-aws's Console Contract v1. A v1 console keeps working with floci-aws unchanged. Once
Floci UI adopts v2, this page moves to the Floci UI repository.

If you are running a console rather than writing one, see [Web Console](index.md).

The key words MUST, MUST NOT, SHOULD, SHOULD NOT and MAY are to be interpreted as described in
[RFC 2119](https://www.rfc-editor.org/rfc/rfc2119).

## The short version

1. The console listens for HTTP on the port in `PORT`, default `4500`.
2. It serves `GET /api/health`.
3. It reads `FLOCI_CLOUD` to know which cloud's emulator started it, and that cloud's profile variables to
   reach it.
4. It MAY read the emulator's manifest at `FLOCI_INFO_URL` for everything else.

## What the emulator gives the console

### Core variables (every cloud)

| Variable | Example | Notes |
|---|---|---|
| `FLOCI_CLOUD` | `az` | The emulator's cloud: `aws`, `az`, `gcp` or `oci`. These are emulator ids; a console maps them to its own (`az` is Azure) |
| `FLOCI_ENDPOINT` | `http://floci-az:4577` | The endpoint of the emulator named by `FLOCI_CLOUD`. See [Compatibility](#compatibility) |
| `FLOCI_HOSTNAME` | `floci-az` | Host part of the endpoint |
| `FLOCI_INFO_URL` | `http://floci-az:4577/_floci/info` | Absolute URL of the [manifest](#the-manifest). Absolute, so an emulator may serve it anywhere |
| `PORT` | `4500` | The port the console listens on inside its container |

The endpoint is always plain HTTP, the address the console container can reach the emulator at. It is not
necessarily the address your browser uses.

### Cloud profiles

**`aws`:** the v1 variables, unchanged: `AWS_ENDPOINT_URL` (MUST), `AWS_REGION` and `AWS_DEFAULT_REGION`
(MUST), and placeholder `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and `AWS_SESSION_TOKEN` (MUST, never the
emulator's own credentials). For `aws`, `FLOCI_ENDPOINT` is the AWS endpoint, so floci-aws always sets it.

**`az`:**

| Variable | Requirement | Example |
|---|---|---|
| `FLOCI_AZURE_ENDPOINT` | MUST | `http://floci-az:4577` |
| `FLOCI_AZURE_SUBSCRIPTION_ID` | MUST | `00000000-0000-0000-0000-000000000001` |
| `FLOCI_AZURE_TENANT_ID` | SHOULD | `00000000-0000-0000-0000-000000000002` |
| `FLOCI_AZURE_LOCATION` | SHOULD | `eastus` |
| `FLOCI_AZURE_ACCOUNT_NAME` | SHOULD | `devstoreaccount1` |
| `FLOCI_AZURE_RESOURCE_GROUP` | MAY | not set by floci-az |

An emulator MUST NOT set the Azure SDK's own `AZURE_*` variables. A partial `EnvironmentCredential` setup
changes what `DefaultAzureCredential` tries, inside the console and in anything it launches.

**`gcp`, `oci`:** reserved. Each emulator defines its profile when it implements the contract.

### Compatibility

An emulator MUST NOT set `FLOCI_ENDPOINT` for a known console that reads it as another cloud's endpoint.
`floci/floci-ui` up to 0.7.x reads it as the AWS endpoint, so floci-az leaves it unset for that image;
otherwise the console would send AWS calls to floci-az and report AWS as reachable.

## What the console gives the emulator

### Health

`GET /api/health` answers `200` with a JSON body:

```json
{"status": "ok"}
```

| `status` | Meaning |
|---|---|
| `ok` | The console is up and can reach the emulator |
| `unavailable` | The console is up but cannot reach the emulator. It SHOULD add an `error` string saying why |

`ok` means the emulator answered, even when the emulator's own health is `DOWN` (floci-az answers its
health with `503` and a body when a service reports a problem). The emulator polls this endpoint after
starting the console and redirects the browser once it reports `ok`.

### One cloud when an emulator starts it

When `FLOCI_CLOUD` is set, the console SHOULD open on that cloud and SHOULD offer only that cloud: the other
clouds have no emulator behind them inside the sidecar. When `FLOCI_CLOUD` is unset, the console runs
standalone and MAY offer every cloud it supports.

### The container

The console MUST listen on `PORT` on all interfaces and MUST NOT need a volume, a privileged mode or the
Docker socket. The emulator publishes the port on the host and removes the container when it stops.

## The manifest

`GET /_floci/info` (the URL in `FLOCI_INFO_URL`) describes the emulator. Keys are snake_case. A console MUST
ignore keys it does not know, and an emulator only adds keys within one `manifest_version`.

```json
{
  "manifest_version": 1,
  "console_contract": 2,
  "cloud": "az",
  "emulator": "floci-az",
  "version": "0.14.0",
  "edition": "floci-az-always-free",
  "endpoint": "http://localhost:4577",
  "health_url": "/_floci/health",
  "tls": {"enabled": false},
  "services": {"blob": "enabled", "cosmos": "enabled", "sql": "disabled"},
  "defaults": {
    "cloud": "AzureCloud",
    "subscription_id": "00000000-0000-0000-0000-000000000001",
    "tenant_id": "00000000-0000-0000-0000-000000000002",
    "location": "eastus",
    "storage_account": "devstoreaccount1"
  },
  "ui": {"enabled": true, "port": 4500}
}
```

| Key | Meaning |
|---|---|
| `manifest_version` | The manifest's shape. `1` |
| `console_contract` | The contract version the emulator implements. `2` |
| `cloud` | Same as `FLOCI_CLOUD` |
| `emulator`, `version`, `edition` | Which emulator and release |
| `endpoint` | The emulator's base URL as clients should use it. The console reaches it through the profile endpoint instead |
| `health_url` | The emulator's health path, relative to `endpoint` |
| `tls.enabled` | Whether the emulator also serves HTTPS |
| `services` | Each service id, `enabled` or `disabled` |
| `defaults` | The cloud-specific defaults, listed below |
| `ui` | Whether the emulator starts a console, and its host port |

`defaults` per cloud:

| Cloud | Keys | floci default |
|---|---|---|
| `aws` | `region`, `account_id` | `us-east-1`, `000000000000` |
| `az` | `cloud`, `subscription_id`, `tenant_id`, `location`, `storage_account` | `AzureCloud`, `00000000-0000-0000-0000-000000000001`, `00000000-0000-0000-0000-000000000002`, `eastus`, `devstoreaccount1` |
| `gcp`, `oci` | reserved | defined with each emulator's profile |

An emulator MAY add keys of its own to the manifest, outside `defaults`. floci-aws keeps the `edition`,
`original_edition` and `dynamodb_backend` keys its `/_floci/info` returned before the manifest.

## Known consoles

| Console | Notes |
|---|---|
| `floci/floci-ui` up to 0.7.x | Predates v2. floci-az uses a built-in profile for it: it probes `GET /api/clouds/azure/status` and treats `runtime: reachable` as ready, leaves `FLOCI_ENDPOINT` unset, and sends the browser to `/console/azure` because the console opens on AWS at its root. The profile matches the image name with any tag, digest or Docker Hub prefix |

## Conformance checklist

A console conforms when:

- [ ] it listens on `PORT`, default `4500`
- [ ] `GET /api/health` answers `200` with `status` `ok` or `unavailable`, and `ok` whenever the emulator
      answers at all
- [ ] it reaches the emulator through the profile variables of `FLOCI_CLOUD`, not a hardcoded address
- [ ] with `FLOCI_CLOUD` set, it opens on and offers only that cloud
- [ ] it ignores manifest keys it does not know

An emulator conforms when:

- [ ] it sets every core variable and the MUST variables of its profile
- [ ] it withholds `FLOCI_ENDPOINT` from a console known to misread it
- [ ] it serves the manifest at the URL in `FLOCI_INFO_URL`
- [ ] a console that fails to start never stops the emulator
