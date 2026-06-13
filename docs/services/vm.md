# Azure Virtual Machines (VM)

Compatible with the `azure-mgmt-compute` SDK, the `az vm` CLI, Terraform's `azurerm_linux_virtual_machine`, and any ARM-speaking client.

> **No Docker required** (mocked mode, the default). VMs are emulated as pure ARM control-plane
> resources: they provision instantly and report power state. Real Docker-backed VMs are planned
> (set `FLOCI_AZ_SERVICES_VM_MOCKED=false` once available).

---

## Features

- **Lifecycle** — CreateOrUpdate, Get, Delete, List (by subscription and by resource group), UpdateTags
- **Power actions** — `start`, `powerOff`, `deallocate`, `restart`, `redeploy`, `reapply`
- **instanceView** — reports `ProvisioningState/*` and `PowerState/*` statuses
- **Network integration** — VM ARM resources can reference `Microsoft.Network` network
  interfaces. The Network emulator supplies VNet, subnet, NIC, public IP, and NSG ARM resources
  so `azurerm_linux_virtual_machine` and its dependencies apply end-to-end.
- **Long-running operations** — power actions return `202` with an `Azure-AsyncOperation` header
  pointing at an operation-status endpoint, so SDK pollers complete cleanly.

---

## Endpoints

All operations use ARM paths:

```
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Compute/virtualMachines/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Compute/virtualMachines/{name}
PATCH  /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Compute/virtualMachines/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Compute/virtualMachines/{name}
GET    .../virtualMachines/{name}/instanceView
POST   .../virtualMachines/{name}/{start|powerOff|deallocate|restart|redeploy|reapply}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Compute/virtualMachines
GET    /subscriptions/{sub}/providers/Microsoft.Compute/virtualMachines

# Network resources are handled by the Microsoft.Network emulator.
```

See [Azure Virtual Network](network.md) for VNet, subnet, NIC, public IP, and NSG scope.

Use `?$expand=instanceView` on a Get to embed the instance view under `properties.instanceView`.

---

## Quickstart

### 1 — Create a VM

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.Compute/virtualMachines/my-vm?api-version=2024-11-01" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "hardwareProfile": {"vmSize": "Standard_B1s"},
      "storageProfile": {
        "imageReference": {
          "publisher": "Canonical",
          "offer": "0001-com-ubuntu-server-jammy",
          "sku": "22_04-lts",
          "version": "latest"
        },
        "osDisk": {"createOption": "FromImage", "caching": "ReadWrite"}
      },
      "osProfile": {"adminUsername": "azureuser", "computerName": "my-vm"},
      "networkProfile": {"networkInterfaces": [{"id": ".../networkInterfaces/my-nic"}]}
    }
  }'
```

The VM is returned with `properties.provisioningState = "Succeeded"` and starts in the
`PowerState/running` state.

### 2 — Power actions

```bash
BASE="http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.Compute/virtualMachines/my-vm"
curl -s -X POST "$BASE/powerOff?api-version=2024-11-01"     # -> PowerState/stopped
curl -s -X POST "$BASE/start?api-version=2024-11-01"        # -> PowerState/running
curl -s -X POST "$BASE/deallocate?api-version=2024-11-01"   # -> PowerState/deallocated
```

### 3 — Read power state

```bash
curl -s "$BASE/instanceView?api-version=2024-11-01"
# { "computerName": "my-vm", "osName": "Linux",
#   "statuses": [ {"code":"ProvisioningState/succeeded",...}, {"code":"PowerState/running",...} ] }
```

---

## Configuration

```yaml
floci-az:
  services:
    vm:
      enabled: true
      mocked: true              # true = no Docker, pure ARM state. false = container-backed (planned)
      default-image: "ubuntu:22.04"
```

| Env var | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_VM_ENABLED` | `true` | Enable/disable the service |
| `FLOCI_AZ_SERVICES_VM_MOCKED` | `true` | Mocked mode (no Docker) |
| `FLOCI_AZ_SERVICES_VM_DEFAULT_IMAGE` | `ubuntu:22.04` | Fallback Docker image for unresolved image references |

---

## Notes & limitations

- Mocked mode does not run a real OS — there is no SSH, no guest agent, and `runCommand` is not executed.
- Network resources are ARM control-plane emulation only. NIC private IPs and public IPs are
  synthesized by the Network emulator, not allocated from a real address pool.
- Real container-backed VMs (image resolution via `imageReference`, `docker start/stop/restart`)
  are planned for a follow-up.
