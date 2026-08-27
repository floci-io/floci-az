# Azure Container Instances (ACI)

Compatible with the `azure-mgmt-containerinstance` SDK, the `az container` CLI, Terraform's
`azurerm_container_group`, and any ARM-speaking client.

> **Mocked mode (default): no Docker required.** Container groups are emulated as pure ARM
> control-plane resources: they provision instantly with a synthetic IP and report a `Running`
> instance view.
>
> **Container-backed mode** (planned, PR 2) will back each container group with real Docker
> containers — group members share a network namespace (`localhost` between containers, like
> real ACI), published ports are mapped onto the host, and `logs` returns the real container
> output. Until it lands, `FLOCI_AZ_SERVICES_ACI_MOCKED=false` is accepted but logs a startup
> warning and behaves exactly like mocked mode.

---

## Features

- **Lifecycle** — CreateOrUpdate, Get, Delete, List (by subscription and by resource group), UpdateTags
- **Actions** — `start`, `stop`, `restart` with the spec's exact LRO shapes (`Location`-header polling)
- **Container logs** — `GET .../containers/{name}/logs` (empty in mocked mode)
- **instanceView** — group state plus per-container `currentState`/`restartCount` on single GETs
  (list responses omit it, matching the spec's list model)
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
      mocked: true              # true = no Docker, pure ARM state. false = container-backed (PR 2)
      base-port: 7500           # host-port range for published group ports
      max-port: 7599
```

| Env var | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_ACI_ENABLED` | `true` | Enable/disable the service |
| `FLOCI_AZ_SERVICES_ACI_MOCKED` | `true` | Mocked mode (no Docker). `false` is reserved for container-backed mode (PR 2) and currently behaves as `true` with a startup warning |
| `FLOCI_AZ_SERVICES_ACI_BASE_PORT` | `7500` | Start of the host port range for published ports (container-backed mode only) |
| `FLOCI_AZ_SERVICES_ACI_MAX_PORT` | `7599` | End of the host port range (container-backed mode only) |

---

## Notes & limitations

- `exec` and `attach` return an honest **501** — they hand out a live websocket in real Azure,
  which the emulator does not provide.
- `azureFile` and `gitRepo` volumes are rejected with a **400**; `emptyDir` and `secret` volumes
  are supported.
- Liveness/readiness probes, `identity`, `diagnostics`, `dnsConfig`, `subnetIds`, GPU resources,
  and confidential/spot SKUs are stored and echoed but not enforced.
- `containerGroupProfiles` and `ngroups` (2025-09-01 additions) are not implemented.
- The `ipAddress.fqdn` is cosmetic — nothing resolves `*.azurecontainer.io` locally.
- Mocked mode reports every container as `Running` without running anything; switch to
  container-backed mode (PR 2) for real state.
