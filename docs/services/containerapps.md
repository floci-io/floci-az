# Azure Container Apps

Compatible with Terraform's `azurerm_container_app_environment`/`azurerm_container_app`, the
`az containerapp` CLI, and any ARM-speaking client, covering all three resource roots under
`Microsoft.App`: `managedEnvironments`, `containerApps`, and `jobs`.

> **Mocked mode (default): no Docker required.** Environments, apps, and jobs provision instantly
> as pure ARM control-plane resources — `provisioningState` is `Succeeded` immediately, with
> synthetic (but stable across GETs) domains, IPs, and revision names. This is the only mode
> implemented today; container-backed mode is a later PR, mirroring ACI's PR 1/PR 2 split.

---

## Features

- **Lifecycle** — CreateOrUpdate, Get, Delete, List (by subscription and by resource group) for
  all three resource types; environments also accept the provider's internal `PATCH` update
- **Terraform-safe read-backs** — enum casing normalized to canonical values
  (`activeRevisionsMode`, ingress `transport`), and defaults filled in for fields the client
  omitted, so a second `terraform plan` shows no diff
- **Idempotence** — PUT → GET → PUT (identical body) → GET does not drift `revisionSuffix`,
  `latestRevisionName`, or `ingress.fqdn`
- **Secret hygiene** — `secrets[].value` is accepted and stored but only ever surfaced via
  `listSecrets`, never on a plain GET
- **Resource index** — all three resource types appear in `GET .../resourceGroups/{rg}/resources`,
  so `terraform destroy` sees them before removing a resource group

---

## Endpoints

```
GET    subscriptions/{sub}/providers/Microsoft.App/managedEnvironments                    (list all)
GET    subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/managedEnvironments (list in rg)
PUT    .../managedEnvironments/{name}
GET    .../managedEnvironments/{name}
PATCH  .../managedEnvironments/{name}
DELETE .../managedEnvironments/{name}

GET    .../containerApps                      (list, sub- and rg-scoped, as above)
PUT    .../containerApps/{name}
GET    .../containerApps/{name}
DELETE .../containerApps/{name}
POST   .../containerApps/{name}/listSecrets

GET    .../jobs                                (list, sub- and rg-scoped, as above)
PUT    .../jobs/{name}
GET    .../jobs/{name}
DELETE .../jobs/{name}
POST   .../jobs/{name}/listSecrets
```

---

## Quickstart

### 1 — Create a managed environment

A real `azurerm_container_app_environment` also needs a Log Analytics workspace — see
[monitor.md](monitor.md) for `workspaces`, `sharedKeys`, and the subscription-wide workspace list
that the azurerm provider calls during environment Create/Read.

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.App/managedEnvironments/my-env?api-version=2025-07-01" \
  -H "Content-Type: application/json" \
  -d '{"location": "eastus", "properties": {}}'
```

The environment is returned with `provisioningState = "Succeeded"`, a synthetic `defaultDomain`,
and a `staticIp`.

### 2 — Create a container app

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.App/containerApps/my-app?api-version=2025-07-01" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "managedEnvironmentId": "/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.App/managedEnvironments/my-env",
      "configuration": {"activeRevisionsMode": "single"},
      "template": {"containers": [{"name": "web", "image": "mcr.microsoft.com/k8se/quickstart:latest"}]}
    }
  }'
```

`activeRevisionsMode` comes back canonicalized (`Single`), and a `latestRevisionName` is
synthesized deterministically from the resource's storage key.

---

## Configuration

```yaml
floci-az:
  services:
    containerapps:
      enabled: true
      mocked: true      # true = no Docker, pure ARM state. false is reserved for a later PR
```

| Env var | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_CONTAINERAPPS_ENABLED` | `true` | Enable/disable the service |
| `FLOCI_AZ_SERVICES_CONTAINERAPPS_MOCKED` | `true` | Mocked mode (no Docker). `false` is reserved for a later container-backed PR |

---

## Notes & limitations

- Mocked mode reports every app/job as `Succeeded` without running anything; there is no real
  container runtime behind it yet.
- `log_analytics_workspace_id` on a managed environment requires [`monitor`](monitor.md)'s
  `Microsoft.OperationalInsights/workspaces` support (workspace CRUD, `sharedKeys`, and the
  subscription-wide list) — all three are implemented, so a real
  `azurerm_container_app_environment` apply works end to end.
