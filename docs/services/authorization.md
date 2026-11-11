# Azure RBAC (role definitions and role assignments)

Compatible with the `Microsoft.Authorization` role-based access control plane used by the `azurerm`
Terraform provider (`azurerm_role_assignment`, `data "azurerm_role_definition"`), the Azure CLI
(`az role assignment ...`, `az role definition list`, `az aks create --attach-acr`), the Azure SDKs
(`azure-mgmt-authorization`) and ARM template or Bicep `Microsoft.Authorization/roleAssignments` resources.
Covers Azure's **built-in role definitions** and **role assignments** at every scope: the tenant root, a
management group, a subscription, a resource group and any resource.

> **Stored, not enforced.** Role assignments are kept and answered the way Azure answers them, but nothing
> evaluates them: an assignment grants nothing and its absence denies nothing. floci-az authorizes no request
> by role.

> **HTTP-only, no Docker.** Assignments are kept in the `authorization` store, which follows the global
> `storage.mode`, so they survive a restart in a persistent mode. They are keyed by their lowercased id.

---

## Features

- **Built-in role definitions**: all of Azure's built-in roles (AcrPull, Contributor, Key Vault Secrets User,
  Owner, Reader, Storage Blob Data Contributor and the rest), generated from Azure's own role list, with their
  GUIDs, descriptions, permissions and assignable scopes. List and Get at any scope:
    - `$filter=roleName eq '<name>'` matches the name ignoring case, as Azure does; `type eq 'BuiltInRole'`
      and `type eq 'CustomRole'` filter by type.
    - Inside a subscription a role's `id` is
      `/subscriptions/{sub}/providers/Microsoft.Authorization/roleDefinitions/{guid}`, whatever scope it was
      listed at. At the tenant root and a management group it is `/providers/Microsoft.Authorization/roleDefinitions/{guid}`.
    - `permissions[].condition` and `conditionVersion` appear from api-version `2022-05-01-preview`.
- **Role assignments**: Create (`201`, `200` on a re-PUT), Get, Delete (`200` with the deleted assignment,
  `204` when there is none) and List, at any scope.
    - Every property is written, the absent ones as `null`, as Azure writes them.
    - `roleDefinitionId` is echoed in the form it was sent; `scope` is the scope of the request.
    - An omitted `principalType` is resolved from the directory: `User`, `Group` or `ServicePrincipal` for a
      principal floci-az knows (an Entra user, group or service principal, or a user-assigned identity).
    - `createdBy` and `updatedBy` are the caller's object id when the request carries a bearer token.
- **List filters**: `atScope()` lists at or above the scope; no filter lists at, above and below;
  `principalId eq '{id}'` and `assignedTo('{id}')` keep one principal's assignments at, above and below
  (`assignedTo` does not expand group membership). Other terms are ignored.
- **Azure's refusals**:
    - the same principal, role and scope under a second name: `409 RoleAssignmentExists`;
    - a name reused with another principal, or at another scope (names are unique everywhere):
      `400 RoleAssignmentUpdateNotPermitted`;
    - an unknown role: `400 RoleDefinitionDoesNotExist`.
- **Resource group delete**: the group's assignments, and those on its resources, go with it.

## Parity

| Behaviour | floci-az | Parity switch |
|---|---|---|
| Unknown principal | **accepted at every level.** Azure answers `400 PrincipalNotFound`. floci-az cannot resolve every principal Azure would (a system-assigned identity's object id is derived, not stored), and `azurerm` retries that error for 30 minutes | none |
| Assignment on a resource that does not exist | `404 ResourceNotFound`, read through the resource's own handler. A type floci-az does not serve is accepted, since its existence cannot be checked | `strict-resource-groups` |
| Assignment name that is not a GUID | `400 InvalidResourceName` | `strict-names` |
| Custom role definitions (PUT, DELETE) | `501 NotImplemented` | none |
| Role definition dates (`createdOn`, `updatedOn`) | `null`: Azure's role list carries none | none |

Not every error code is documented by Azure. `RoleAssignmentNotFound` (a missing assignment) and
`RoleDefinitionDoesNotExist` (an unknown role) are not in the spec, the RBAC docs or the SDKs; clients act on the
status, which is Azure's.

## Configuration

```yaml
floci-az:
  services:
    authorization:
      enabled: true   # FLOCI_AZ_SERVICES_AUTHORIZATION_ENABLED
```

## Examples

```bash
# Look a role up by name, then assign it to a managed identity on a Key Vault
az role definition list --name "Key Vault Secrets User" --query "[0].id" -o tsv
az role assignment create --assignee-object-id <principal-id> --assignee-principal-type ServicePrincipal \
  --role "Key Vault Secrets User" --scope <vault-id>
az role assignment list --scope <vault-id>
```

```hcl
resource "azurerm_role_assignment" "kv_reader" {
  scope                = azurerm_key_vault.example.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.example.principal_id
}
```
