# Azure Container Instances (ACI)

Compatible with the `azure-mgmt-containerinstance` SDK, the `az container` CLI, Terraform's
`azurerm_container_group`, and any ARM-speaking client.

> **Mocked mode (default): no Docker required.** Container groups are emulated as pure ARM
> control-plane resources: they provision instantly with a synthetic IP and report a `Running`
> instance view.
>
> **Container-backed mode:** set `FLOCI_AZ_SERVICES_ACI_MOCKED=false` to back each container
> group with real Docker containers. Group members share a network namespace (`localhost`
> between containers, like real ACI), published ports are mapped onto the host (preferring the
> container port itself, falling back to the configured range), `logs` returns the real
> container output (honoring `tail` and `timestamps`), and `instanceView` reports the real
> Docker state (`Running` / `Terminated` with exit code / `Waiting`). Groups provision
> asynchronously (`Creating` → `Succeeded` once every container runs, or an honest `Failed`
> when Docker cannot start them).

---

## Features

- **Lifecycle** — CreateOrUpdate, Get, Delete, List (by subscription and by resource group), UpdateTags
- **Actions** — `start`, `stop`, `restart` with the spec's exact LRO shapes (`Location`-header
  polling); in container-backed mode they map onto the backing containers (the netns-owning
  primary restarts first, then the secondaries)
- **Container logs** — `GET .../containers/{name}/logs?tail=&timestamps=` (real Docker logs in
  container-backed mode, empty in mocked mode)
- **Pod semantics** — multi-container groups share one network namespace: the first container
  owns the network and the published ports; the rest join it and reach each other on `localhost`
- **Volumes** — `emptyDir` (named Docker volume) and `secret` (files injected before start)
- **instanceView** — group state plus per-container `currentState`/`restartCount` on single GETs
  (list responses omit it, matching the spec's list model); real Docker state when unmocked
- **Terraform-safe read-backs** — container `ports` and `resources.requests` are always present
  (server defaults `cpu: 1.0`, `memoryInGB: 1.5` when omitted, matching the `az` CLI's behaviour),
  and enum casing is normalized to canonical Azure values (`Linux`, `Always`, `TCP`, `Public`)
- **Secret hygiene** — `secureValue` environment variables, `imageRegistryCredentials` passwords,
  and secret-volume contents are accepted but never echoed back
- **Resource index** — groups appear in `GET .../resourceGroups/{rg}/resources`, so
  `terraform destroy` sees them before removing a resource group

---

## Endpoints

All operations use ARM paths:

```
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerInstance/containerGroups/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerInstance/containerGroups/{name}
PATCH  /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerInstance/containerGroups/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerInstance/containerGroups/{name}
POST   .../containerGroups/{name}/{start|stop|restart}
GET    .../containerGroups/{name}/containers/{container}/logs?tail=&timestamps=
GET    .../containerGroups/{name}/outboundNetworkDependenciesEndpoints
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerInstance/containerGroups
GET    /subscriptions/{sub}/providers/Microsoft.ContainerInstance/containerGroups
GET    /subscriptions/{sub}/providers/Microsoft.ContainerInstance/locations/{loc}/{cachedImages|capabilities|usages}
```

---

## Quickstart

### 1 — Create a container group

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.ContainerInstance/containerGroups/my-app?api-version=2023-05-01" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "containers": [
        {
          "name": "web",
          "properties": {
            "image": "hashicorp/http-echo:latest",
            "command": ["/http-echo", "-text=hello"],
            "ports": [{"port": 5678}],
            "resources": {"requests": {"cpu": 0.5, "memoryInGB": 0.5}}
          }
        }
      ],
      "osType": "Linux",
      "ipAddress": {"type": "Public", "ports": [{"port": 5678}], "dnsNameLabel": "my-app"}
    }
  }'
```

The group is returned with `properties.provisioningState = "Succeeded"`, an IP, and
`fqdn = "my-app.eastus.azurecontainer.io"`.

### 2 — Read logs and state

```bash
BASE="http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.ContainerInstance/containerGroups/my-app"
curl -s "$BASE?api-version=2023-05-01"                              # full resource + instanceView
curl -s "$BASE/containers/web/logs?api-version=2023-05-01"          # {"content": "..."}
```

### 3 — Actions

```bash
curl -si -X POST "$BASE/stop?api-version=2023-05-01"      # 204, synchronous
curl -si -X POST "$BASE/start?api-version=2023-05-01"     # 202 + Location (poll -> Succeeded)
curl -si -X POST "$BASE/restart?api-version=2023-05-01"   # 204 + Location
```

---

## Configuration

```yaml
floci-az:
  services:
    aci:
      enabled: true
      mocked: true              # true = no Docker, pure ARM state. false = container-backed
      base-port: 7500           # host-port range for published group ports
      max-port: 7599
```

| Env var | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_ACI_ENABLED` | `true` | Enable/disable the service |
| `FLOCI_AZ_SERVICES_ACI_MOCKED` | `true` | Mocked mode (no Docker); `false` backs each group with real Docker containers |
| `FLOCI_AZ_SERVICES_ACI_BASE_PORT` | `7500` | Start of the host port range for published ports (container-backed mode only) |
| `FLOCI_AZ_SERVICES_ACI_MAX_PORT` | `7599` | End of the host port range (container-backed mode only) |

---

## Notes & limitations

- `exec` and `attach` return an honest **501** — they hand out a live websocket in real Azure,
  which the emulator does not provide.
- `azureFile` and `gitRepo` volumes are rejected with a **400**; `emptyDir` and `secret` volumes
  are supported.
- Liveness/readiness probes, `identity`, `diagnostics`, `dnsConfig`, `subnetIds`, GPU resources,
  and confidential/spot SKUs are stored and echoed but not enforced. CPU requests are stored but
  not enforced as a Docker limit (memory requests are).
- `restartPolicy` is stored and echoed but not mapped onto Docker restart policies — an exited
  container stays `Terminated` in the instance view.
- `containerGroupProfiles` and `ngroups` (2025-09-01 additions) are not implemented.
- The `ipAddress.fqdn` is cosmetic — nothing resolves `*.azurecontainer.io` locally.
- Secondary (non-first) containers cannot resolve emulated hostnames: Docker rejects DNS options
  in the shared-netns mode they use, so floci-az's embedded DNS is only injected into the primary.
- `command` replaces the image's entrypoint (real ACI semantics), not just its CMD.
- When floci-az itself runs inside Docker, images referencing the emulated ACR's `loginServer`
  (`floci-az-acr-registry:5000/...`) cannot be pulled by the host daemon — pull from the
  emulated ACR works natively only.
- Mocked mode reports every container as `Running` without running anything; use
  container-backed mode for real state.
