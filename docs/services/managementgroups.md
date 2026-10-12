# Azure Management Groups

Compatible with the `Microsoft.Management` management-group API used by `az account management-group`,
the `armmanagementgroups` / `azure-mgmt-managementgroups` SDKs and the `azurerm_management_group`
Terraform resource. Models the tenant hierarchy: the tenant root group, the groups under it and the
subscriptions placed in them.

> **HTTP-only, no Docker.** The hierarchy is in-memory and ephemeral, like the rest of the ARM control
> plane.

---

## Features

- **Tenant root group**: every tenant has one, named after the tenant id with the display name
  `Tenant Root Group`; it cannot be created, updated or deleted. A subscription that was never placed
  belongs to it, as in Azure.
- **Groups**: CreateOrUpdate (`PUT`, with `properties.details.parent.id`; the root when omitted), Get
  with `$expand=children` (plus `$recurse=true`), `$expand=path` and `$expand=ancestors`, Update
  (`PATCH` of `displayName` and `parentGroupId`), Delete (an empty group only), List, `descendants`.
- **Subscriptions**: `GET` / `PUT` / `DELETE` `managementGroups/{group}/subscriptions/{subscriptionId}`
  and the `subscriptions` listing; placing a subscription registers it with the emulator's directory.
- **Entities**: `POST /providers/Microsoft.Management/getEntities` with the parent chains, descendant and
  child counts, narrowed by `groupName`.
- **Also served**: `checkNameAvailability`, `operations`, the `settings/default` resource and the tenant
  backfill operations the CLI calls.
- **Azure error shapes**: `NotFound` for a missing group, `InvalidManagementGroupName`, `InvalidRequest`
  for a missing parent or a move under a descendant, `Conflict` when deleting a group with children.

## Endpoints

```text
GET    /providers/Microsoft.Management/managementGroups
GET    /providers/Microsoft.Management/managementGroups/{groupId}?$expand=children|path|ancestors&$recurse=true
PUT    /providers/Microsoft.Management/managementGroups/{groupId}
PATCH  /providers/Microsoft.Management/managementGroups/{groupId}
DELETE /providers/Microsoft.Management/managementGroups/{groupId}
GET    /providers/Microsoft.Management/managementGroups/{groupId}/descendants
GET    /providers/Microsoft.Management/managementGroups/{groupId}/subscriptions
GET    /providers/Microsoft.Management/managementGroups/{groupId}/subscriptions/{subscriptionId}
PUT    /providers/Microsoft.Management/managementGroups/{groupId}/subscriptions/{subscriptionId}
DELETE /providers/Microsoft.Management/managementGroups/{groupId}/subscriptions/{subscriptionId}
GET    /providers/Microsoft.Management/managementGroups/{groupId}/settings[/default]
POST   /providers/Microsoft.Management/getEntities
POST   /providers/Microsoft.Management/checkNameAvailability
GET    /providers/Microsoft.Management/operations
```

Any `api-version` is accepted (the shapes follow `2023-04-01`).

## Quickstart

```bash
BASE=http://localhost:4577/providers/Microsoft.Management
curl -s -X PUT "$BASE/managementGroups/platform?api-version=2023-04-01" -H "Content-Type: application/json" \
  -d '{"properties": {"displayName": "Platform"}}'
curl -s -X PUT "$BASE/managementGroups/corp?api-version=2023-04-01" -H "Content-Type: application/json" \
  -d '{"properties": {"displayName": "Corp", "details": {"parent": {"id": "/providers/Microsoft.Management/managementGroups/platform"}}}}'
curl -s -X PUT "$BASE/managementGroups/corp/subscriptions/00000000-0000-0000-0000-000000000001?api-version=2023-04-01"
curl -s "$BASE/managementGroups/platform?api-version=2023-04-01&\$expand=children&\$recurse=true" | jq .
```

```bash
az account management-group create --name platform --display-name Platform
az account management-group subscription add --name platform --subscription 00000000-0000-0000-0000-000000000001
az account management-group show --name platform --expand --recurse
```

## Configuration

```yaml
floci-az:
  services:
    management-groups:
      enabled: true
```

| Property | Env var | Default | Description |
|---|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_MANAGEMENT_GROUPS_ENABLED` | `true` | Serves the `Microsoft.Management` management-group resources. When disabled, their paths fall through to the generic ARM handler and answer `404` |

---

## Intentional deviations

- **Creates and deletes answer synchronously** (`200` / `204`) rather than as the long-running
  operations Azure may answer with `202`; both are allowed by the service contract and every client
  handles the synchronous answer.
- **Policy assignments at management-group scope are stored, not inherited.** The policy control plane
  accepts a management group as a scope without consulting this hierarchy; a hierarchy-aware
  evaluation of management-group assignments is a separate change.
- **No role-based scoping**: `getEntities` reports `view` permissions on everything and
  `inheritedPermissions` likewise; `$filter`, `$search`, `$view`, `$select`, `$skip`, `$top` and
  `$skiptoken` on `getEntities` and the listings are accepted and ignored (every entity is returned).
- **`updatedBy` is null** and `version` counts writes; Azure stamps the caller and its own version.
- **Settings** (`settings/default`) are a fixed representation: `requireAuthorizationForGroupCreation`
  is always `false` and writes are acknowledged without being stored.
