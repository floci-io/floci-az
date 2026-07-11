# Azure Resource Manager (ARM)

The central management-plane handler. It serves the generic ARM surface —
subscriptions, resource groups, and the resource/provider listings — that the
`hashicorp/azurerm` Terraform provider, OpenTofu, and the Azure CLI expect, and it acts as the
fallthrough for management-plane paths not claimed by a more specific handler (AKS, SQL, Redis,
Managed Identity, and the other `Microsoft.*` providers).

> **HTTP-only — no Docker.** All ARM resource state is in-memory and ephemeral; it does not persist
> across restarts regardless of `storage.mode`, matching how the other control-plane resources
> behave.

!!! note "Disabling ARM disables the management plane"
    `arm.enabled: false` turns off `/subscriptions` and `/providers` routing, which the
    provider-specific ARM services (Redis, SQL, AKS, …) depend on. Leave it enabled unless you are
    deliberately testing a data-plane-only configuration.

---

## Features

- **Subscription** — `GET /subscriptions` (enumeration, used by `az login`) and
  `GET /subscriptions/{sub}`; a single fixed subscription and tenant GUID
- **Resource groups** — CreateOrUpdate, Get, Delete, List
  (`/subscriptions/{sub}/resourceGroups/{rg}`); accepts both `resourceGroups` and the lowercase
  `resourcegroups` spelling
- **Storage accounts** — an ARM shell that bridges `Microsoft.Storage/storageAccounts` to the live
  [Blob](blob.md) and [Queue](queue.md) backends, returning the well-known development account key
- **Key vaults** — an ARM shell for `Microsoft.KeyVault/vaults` whose `vaultUri` points at the
  live [Key Vault](key-vault.md) handler
- **Resource & provider listing** — `GET /subscriptions/{sub}/resources`,
  `GET /subscriptions/{sub}/providers[/{namespace}]`, and
  `POST .../{namespace}/checkNameAvailability`
- **Provider fallthrough** — management-plane paths for providers without a dedicated handler are
  answered here so Terraform reads and dependency lookups resolve

## Endpoints

```
GET    /subscriptions
GET    /subscriptions/{sub}

PUT    /subscriptions/{sub}/resourceGroups/{rg}
GET    /subscriptions/{sub}/resourceGroups/{rg}
DELETE /subscriptions/{sub}/resourceGroups/{rg}
GET    /subscriptions/{sub}/resourceGroups

GET    /subscriptions/{sub}/resources
GET    /subscriptions/{sub}/providers[/{namespace}]
POST   /subscriptions/{sub}/providers/{namespace}/checkNameAvailability
```

## Quickstart

Point Terraform at the emulator with a minimal `azurerm` provider block (see the
[Terraform guide](../terraform.md) for the full skip-provider-registration setup):

```hcl
provider "azurerm" {
  features {}
  skip_provider_registration = true
  # metadata_host / endpoints redirected at localhost:4577 — see the Terraform guide
}

resource "azurerm_resource_group" "example" {
  name     = "my-rg"
  location = "eastus"
}
```

Or drive it directly:

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/00000000-0000-0000-0000-000000000001/resourceGroups/my-rg?api-version=2021-04-01" \
  -H "Content-Type: application/json" \
  -d '{"location":"eastus"}'
```

## Configuration

```yaml
floci-az:
  services:
    arm:
      enabled: true
```

| Property | Env var | Default | Description |
|---|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_ARM_ENABLED` | `true` | Enables the ARM management plane. Disabling it turns off every ARM-based service |

The subscription and tenant GUIDs are fixed
(`00000000-0000-0000-0000-000000000001` / `…000000000002`) and shared with the
[Entra ID](entra.md) and [Managed Identity](managed-identity.md) emulation.

## Intentional deviations

- **A single fixed subscription and tenant** — the emulator does not model multiple subscriptions.
- **ARM state is in-memory** — resource groups and the ARM shells do not persist across restarts.
- **Resource-group deletion does not cascade** — deleting a group leaves resources created under it
  behind (they still appear in `GET .../resources`, so Terraform's
  `prevent_deletion_if_contains_resources` works).
- **`checkNameAvailability` always reports `nameAvailable: true`** — the emulator does not track
  global name uniqueness.
- **Shared Key / ARM auth is accepted but not verified** — any bearer token or Shared Key is
  honored, consistent with the rest of the emulator's permissive dev auth.
