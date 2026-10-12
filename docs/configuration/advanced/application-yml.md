# application.yml Reference

Floci-AZ is a Quarkus application. All settings can be overridden via environment variables
(replace `.` with `_` and uppercase, e.g. `floci-az.storage.mode` → `FLOCI_AZ_STORAGE_MODE`).

```yaml
floci-az:
  port: 4577
  base-url: http://localhost:4577

  # When set, overrides the hostname used in URLs returned by the API
  # (e.g. blob SAS URLs, function invoke URLs).
  # hostname: myhost.internal

  auth:
    # Not enforced yet: every value, strict included, accepts any credentials
    # without SharedKey signature validation (see the note below the table).
    mode: dev

  # Azure parity level: compat (default) | azure | strict. Each override
  # defaults from it; see the ARM page for what each one changes.
  parity: compat
  # parity-overrides:
  #   subscription-listing: true   # default: on from azure
  #   strict-subscriptions: true   # default: on from strict
  #   resource-group-semantics: true # default: on from azure
  #   resource-group-delete: true  # default: on from azure
  #   strict-resource-groups: true # default: on from strict

  # Cloud of the default tenant: AzureCloud | AzureUSGovernment | AzureChinaCloud
  cloud:
    default: AzureCloud

  # Extra tenants and subscriptions for the ARM directory (only id is required)
  # tenants:
  #   - id: 11111111-0000-0000-0000-000000000000
  #     cloud: AzureUSGovernment
  # subscriptions:
  #   - id: 22222222-0000-0000-0000-000000000000
  #     tenant-id: 11111111-0000-0000-0000-000000000000
  #     state: Disabled

  storage:
    # Global default: applies to every service unless overridden below.
    # Supported: memory | persistent | hybrid | wal
    mode: memory
    path: /app/data

    wal:
      compaction-interval-ms: 30000

    hybrid:
      flush-interval-ms: 5000

    # Per-service storage overrides (uncomment to activate)
    services:
      blob:
        # mode: wal
        flush-interval-ms: 5000
      queue:
        # mode: hybrid
        flush-interval-ms: 5000
      table:
        # mode: persistent
        flush-interval-ms: 5000
      app-config:
        # mode: persistent
        flush-interval-ms: 5000
      container-apps:
        # mode: persistent
        flush-interval-ms: 5000
      directory:              # ARM tenants and subscriptions
        # mode: persistent
        flush-interval-ms: 5000

  dns:
    # When floci-az runs inside Docker, an embedded DNS server starts on UDP/53
    # and is injected into every Azure Functions container as their resolver.
    # It resolves *.hostname (above) and any extra suffixes to floci-az's
    # Docker-network IP so virtual-hosted URLs work from inside function containers.
    # extra-suffixes:
    #   - myapp.internal

  docker:
    docker-host: unix:///var/run/docker.sock
    endpoint-mode: auto                # auto · published: address sidecars at the Docker daemon's host + published port (remote daemons)
    log-max-size: "10m"
    log-max-file: "3"
    resource-namespace: ""            # Optional; inserted into sidecar container/volume names (floci-az-<ns>-...)

  services:
    blob:
      enabled: true
    queue:
      enabled: true
    table:
      enabled: true
    functions:
      enabled: true
      # Directory where extracted function code is stored on the host.
      code-path: ~/.floci-az/functions
      # ephemeral: true  →  fresh container per invocation (no warm reuse)
      ephemeral: false
      # Evict warm containers idle longer than this (seconds); 0 disables eviction
      container-idle-timeout-seconds: 300
    app-config:
      enabled: true
    container-apps:
      enabled: true
      mocked: true
      dns-suffix: azurecontainerapps.io
      ingress-timeout-seconds: 60
```

## Key Environment Variables

| Environment Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_PORT` | `4577` | Listening port |
| `FLOCI_AZ_AUTH_MODE` | `dev` | Not enforced yet: every value behaves like `dev` and accepts any credentials |
| `FLOCI_AZ_PARITY` | `compat` | Azure parity level: `compat`, `azure` or `strict`; the default for every `parity-overrides` key |
| `FLOCI_AZ_PARITY_OVERRIDES_SUBSCRIPTION_LISTING` | _(on from `azure`)_ | List subscriptions registered on first use, and echo the directory's subscription id |
| `FLOCI_AZ_PARITY_OVERRIDES_STRICT_SUBSCRIPTIONS` | _(on from `strict`)_ | Refuse unknown subscriptions with `404 SubscriptionNotFound` |
| `FLOCI_AZ_PARITY_OVERRIDES_RESOURCE_GROUP_SEMANTICS` | _(on from `azure`)_ | Resource-group re-PUT answers `200`, a missing group is `404 ResourceGroupNotFound`, a resource PUT creates a missing group, and groups persist in the directory store |
| `FLOCI_AZ_PARITY_OVERRIDES_RESOURCE_GROUP_DELETE` | _(on from `azure`)_ | Resource-group `DELETE` is a `202` long-running operation that deletes every resource in the group |
| `FLOCI_AZ_PARITY_OVERRIDES_STRICT_RESOURCE_GROUPS` | _(on from `strict`)_ | Refuse a request for a resource under a missing group with `404 ResourceGroupNotFound` |
| `FLOCI_AZ_CLOUD_DEFAULT` | `AzureCloud` | Cloud of the default tenant |
| `FLOCI_AZ_STORAGE_MODE` | `memory` | Global storage mode |
| `FLOCI_AZ_STORAGE_PATH` | `/app/data` | Persistence directory |
| `FLOCI_AZ_STORAGE_SERVICES_BLOB_MODE` | _(global)_ | Per-service blob mode |
| `FLOCI_AZ_STORAGE_SERVICES_QUEUE_MODE` | _(global)_ | Per-service queue mode |
| `FLOCI_AZ_STORAGE_SERVICES_TABLE_MODE` | _(global)_ | Per-service table mode |
| `FLOCI_AZ_STORAGE_SERVICES_APP_CONFIG_MODE` | _(global)_ | Per-service App Configuration mode |
| `FLOCI_AZ_STORAGE_SERVICES_CONTAINER_APPS_MODE` | _(global)_ | Per-service Container Apps mode |
| `FLOCI_AZ_STORAGE_SERVICES_DIRECTORY_MODE` | _(global)_ | Per-service mode of the ARM tenant and subscription directory |
| `FLOCI_AZ_SERVICES_FUNCTIONS_EPHEMERAL` | `false` | Fresh container per invocation |
| `FLOCI_AZ_SERVICES_FUNCTIONS_CONTAINER_IDLE_TIMEOUT_SECONDS` | `300` | Evict warm containers idle longer than this (seconds); `0` disables eviction |
| `FLOCI_AZ_SERVICES_APP_CONFIG_ENABLED` | `true` | Enable/disable App Configuration |
| `FLOCI_AZ_SERVICES_CONTAINER_APPS_MOCKED` | `true` | Keep Container Apps ARM state without Docker runtimes |
| `FLOCI_AZ_SERVICES_FUNCTIONS_CODE_PATH` | `~/.floci-az/functions` | Function code directory |
| `FLOCI_AZ_DOCKER_DOCKER_HOST` | `unix:///var/run/docker.sock` | Docker daemon socket |
| `FLOCI_AZ_DOCKER_RESOURCE_NAMESPACE` | _(unset)_ | Namespace inserted into the name of every container and volume floci-az creates (`floci-az-<ns>-...`) and added as the `floci_namespace` label, so parallel emulator instances on one Docker host don't collide |

`auth.mode` is not read by any service today. Setting it to `strict` does not turn on SharedKey
or SAS signature validation: requests are accepted exactly as in `dev`. The one signature floci-az
does check, the Blob service SAS, is validated against the per-account keys below whatever
`auth.mode` says.

Blob service SAS signing keys are configured per account (independent of `auth.mode`):

```yaml
floci-az:
  auth:
    storage-account-keys:
      myaccount: "<base64-account-key>"
```

`devstoreaccount1` defaults to the standard Azurite account key. For that existing entry, the
environment override is `FLOCI_AZ_AUTH_STORAGE_ACCOUNT_KEYS_DEVSTOREACCOUNT1`.
Use YAML to declare additional account names. User delegation SAS keeps its separate,
process-local signing keys.

Accounts without an explicit entry use the standard Azurite account key, including accounts
created through ARM. Explicit account entries take precedence over this fallback.
