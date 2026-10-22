# Azure Policy

Compatible with the `Microsoft.Authorization` policy management plane used by the Azure CLI
(`az policy ...`), the `azurerm` Terraform provider (`azurerm_policy_definition`,
`azurerm_policy_set_definition`, `azurerm_*_policy_assignment`, `azurerm_*_policy_exemption`) and the
Azure SDKs (`azure-resourcemanager-resources`, `armpolicy`, `azure-mgmt-resource`). Covers **policy
definitions**, **policy set definitions** (initiatives), **policy assignments** and **policy
exemptions** at every scope the real service accepts, and **evaluates the assigned rules** on every
resource write: `deny` refuses the request with `RequestDisallowedByPolicy`, `append` amends it and
`audit` is recorded.

> **HTTP-only, no Docker.** All policy state is in-memory and ephemeral, like the rest of the ARM
> control plane.

> **Request-time effects only.** `deny`, `append` and `audit` fire when a resource is written; there is
> no compliance scan of existing resources, no remediation and no compliance state
> (`Microsoft.PolicyInsights` is not implemented). A rule the engine cannot evaluate denies the request
> rather than standing aside, so a broken rule fails loudly.

---

## Features

- **Policy definitions** at subscription and management-group scope: CreateOrUpdate, Get, Delete, List
  (`$filter=policyType eq 'Custom'` and `atExactScope()` are honoured). `policyType` is always
  `Custom`, `mode` defaults to `Indexed`, `version` defaults to `1.0.0`, and `metadata` gains the
  `createdBy` / `createdOn` / `updatedBy` / `updatedOn` stamps Azure adds
- **Policy set definitions** at subscription and management-group scope: CreateOrUpdate, Get, Delete,
  List. Each reference gets a server-generated `policyDefinitionReferenceId` when none is supplied and
  a `definitionVersion` of `1.*.*`
- **Policy assignments** at management-group, subscription, resource-group and resource scope:
  Create, Get, Update (PATCH of `identity`, `location`, `resourceSelectors`, `overrides`), Delete
  (returns the deleted assignment), List for subscription / resource group / resource / management
  group with `atScope()`, `atExactScope()`, `atScopeAndBelow()` and `policyDefinitionId eq '...'`.
  `scope`, `enforcementMode`, `definitionVersion` and `instanceId` are populated server-side;
  a `SystemAssigned` identity receives a stable `principalId`, and `UserAssigned` identities resolve
  their `principalId` / `clientId` from [Managed Identity](managed-identity.md) when the identity exists
- **Policy exemptions** at the same scopes: CreateOrUpdate, Get, Update (PATCH of
  `resourceSelectors`, `assignmentScopeValidation`), Delete (returns the deleted exemption), List with
  `atScope()`, `atExactScope()`, `excludeExpired()` and `policyAssignmentId eq '...'`. Exemptions are
  deleted together with their assignment
- **Referential integrity**: assignments and set definitions must reference definitions that exist
  (`PolicyDefinitionNotFound` / `PolicySetDefinitionNotFound`), exemptions must reference an existing
  assignment (`PolicyAssignmentNotFound`), and a definition or set definition that is still referenced
  cannot be deleted (`InvalidDeletePolicyDefinitionRequest` / `InvalidDeletePolicySetDefinitionRequest`)
- **Azure error shapes**: `PolicyDefinitionNotFound`, `PolicySetDefinitionNotFound`,
  `PolicyAssignmentNotFound`, `PolicyExemptionNotFound`, `InvalidPolicyRule`,
  `InvalidCreatePolicySetDefinitionRequest`, `InvalidPolicyDefinitionReference`, `MissingSubscription`
  for tenant-level writes, and ARM's `InvalidRequestContent` for malformed JSON
- **Rule evaluation on writes** (see [Rule evaluation](#rule-evaluation)): every PUT and PATCH of a
  resource is checked against the assignments covering its scope, with `deny`, `append` and `audit`
  effects, overrides, exemptions, `notScopes`, `enforcementMode`, parameters, `Indexed` / `All` modes,
  array aliases, `count` expressions and the ARM template functions

---

## Endpoints

`{scope}` is empty (tenant root), `providers/Microsoft.Management/managementGroups/{mg}`,
`subscriptions/{sub}`, `subscriptions/{sub}/resourceGroups/{rg}` or a full resource id.

```
# Definitions and set definitions: subscription or management-group scope
PUT    /{scope}/providers/Microsoft.Authorization/policyDefinitions/{name}
GET    /{scope}/providers/Microsoft.Authorization/policyDefinitions/{name}
DELETE /{scope}/providers/Microsoft.Authorization/policyDefinitions/{name}
GET    /{scope}/providers/Microsoft.Authorization/policyDefinitions[?$filter=...]
PUT    /{scope}/providers/Microsoft.Authorization/policySetDefinitions/{name}
GET    /{scope}/providers/Microsoft.Authorization/policySetDefinitions/{name}
DELETE /{scope}/providers/Microsoft.Authorization/policySetDefinitions/{name}
GET    /{scope}/providers/Microsoft.Authorization/policySetDefinitions[?$filter=...]

# Built-in listings at the tenant root (always empty, see deviations)
GET    /providers/Microsoft.Authorization/policyDefinitions
GET    /providers/Microsoft.Authorization/policySetDefinitions

# Assignments and exemptions: management group, subscription, resource group or resource scope
PUT    /{scope}/providers/Microsoft.Authorization/policyAssignments/{name}
PATCH  /{scope}/providers/Microsoft.Authorization/policyAssignments/{name}
GET    /{scope}/providers/Microsoft.Authorization/policyAssignments/{name}
DELETE /{scope}/providers/Microsoft.Authorization/policyAssignments/{name}
GET    /{scope}/providers/Microsoft.Authorization/policyAssignments[?$filter=...]
PUT    /{scope}/providers/Microsoft.Authorization/policyExemptions/{name}
PATCH  /{scope}/providers/Microsoft.Authorization/policyExemptions/{name}
GET    /{scope}/providers/Microsoft.Authorization/policyExemptions/{name}
DELETE /{scope}/providers/Microsoft.Authorization/policyExemptions/{name}
GET    /{scope}/providers/Microsoft.Authorization/policyExemptions[?$filter=...]
```

Any `api-version` is accepted. Shapes follow the policy spec `2025-03-01` and the exemptions spec
`2022-07-01-preview`.

---

## Quickstart

### 1. Create a definition and assign it to a resource group

```bash
BASE=http://localhost:4577
SUB=00000000-0000-0000-0000-000000000001

curl -s -X PUT "$BASE/subscriptions/$SUB/providers/Microsoft.Authorization/policyDefinitions/allowed-locations?api-version=2025-03-01" \
  -H "Content-Type: application/json" \
  -d '{"properties":{"displayName":"Allowed locations","mode":"All",
       "policyRule":{"if":{"field":"location","notIn":["eastus"]},"then":{"effect":"deny"}}}}'

curl -s -X PUT "$BASE/subscriptions/$SUB/resourceGroups/my-rg/providers/Microsoft.Authorization/policyAssignments/enforce-locations?api-version=2025-03-01" \
  -H "Content-Type: application/json" \
  -d "{\"location\":\"eastus\",\"identity\":{\"type\":\"SystemAssigned\"},
       \"properties\":{\"policyDefinitionId\":\"/subscriptions/$SUB/providers/Microsoft.Authorization/policyDefinitions/allowed-locations\"}}"
```

```json
{
  "identity": {"principalId": "4f0c…", "tenantId": "00000000-0000-0000-0000-000000000002", "type": "SystemAssigned"},
  "properties": {
    "policyDefinitionId": "/subscriptions/…/providers/Microsoft.Authorization/policyDefinitions/allowed-locations",
    "definitionVersion": "1.*.*",
    "scope": "/subscriptions/…/resourceGroups/my-rg",
    "metadata": {"createdBy": "…", "createdOn": "2026-09-04T10:00:00Z"},
    "enforcementMode": "Default",
    "instanceId": "…"
  },
  "id": "/subscriptions/…/resourceGroups/my-rg/providers/Microsoft.Authorization/policyAssignments/enforce-locations",
  "type": "Microsoft.Authorization/policyAssignments",
  "name": "enforce-locations",
  "location": "eastus"
}
```

### 2. Azure CLI

With the CLI pointed at the emulator (see the `compat-azcli` suite for the custom-cloud setup):

```bash
az policy definition create --name allowed-locations --mode All \
  --rules '{"if":{"field":"location","notIn":["eastus"]},"then":{"effect":"deny"}}'
az policy assignment create --name enforce-locations --policy allowed-locations -g my-rg
az policy exemption create --name legacy -g my-rg --exemption-category Waiver \
  --policy-assignment "$(az policy assignment show -n enforce-locations -g my-rg --query id -o tsv)"
az policy assignment list -g my-rg
```

### 3. Terraform

```hcl
resource "azurerm_policy_definition" "allowed_locations" {
  name         = "allowed-locations"
  policy_type  = "Custom"
  mode         = "All"
  display_name = "Allowed locations"
  policy_rule  = jsonencode({
    if   = { field = "location", notIn = ["eastus"] }
    then = { effect = "deny" }
  })
}

resource "azurerm_resource_group_policy_assignment" "enforce" {
  name                 = "enforce-locations"
  resource_group_id    = azurerm_resource_group.example.id
  policy_definition_id = azurerm_policy_definition.allowed_locations.id
}
```

---

## Rule evaluation

Every PUT and PATCH of a provider resource or a resource group passes through the ARM request gate,
where the assigned policies are evaluated before the handler runs, the way Azure evaluates them before
a request reaches its resource provider. A PATCH is judged on the resource it produces: the stored
resource (read through its own GET) with the patch applied the way Resource Manager applies one,
`properties` merged and `tags` replaced as a whole. A tag update on a compliant resource therefore
passes, and a PATCH that drops a required tag or sets a forbidden property is refused. As in Azure, a
PATCH that changes only `tags` is evaluated only against the definitions whose condition inspects a
tags field (`tags`, `tags.name`, `tags['name']`, a parameterised field resolving to one, or
`field('tags...')` in an expression), so a tag update never trips a rule about other properties. When
an `append` fires on a PATCH, its amendments are added to the caller's patch, so the handler still
receives a PATCH.

1. The assignments whose scope covers the resource are collected: the resource itself, its parents,
   its resource group, its subscription and any management group. `notScopes` and `resourceSelectors`
   narrow them, and an unexpired exemption for the assignment (matching the reference id when it names
   one) removes it.
2. Each assigned definition, or each member of an assigned set definition, has its parameters resolved
   (assignment values, then set-definition values, then declared defaults) and its effect resolved:
   an `overrides` entry of kind `policyEffect` wins over the definition's `then.effect`, which may
   itself be a parameter expression.
3. The `if` condition is evaluated against the request content, with `type`, `name` and `id` filled
   from the URL. `field` conditions cover the fixed fields (`name`, `fullName`, `type`, `kind`,
   `location`, `id`, `identity.type`, `tags`, `tags['x']`) and property aliases; `value` conditions
   and `count` expressions take the ARM template functions (`parameters`, `field`, `current`,
   `requestContext`, `policy`, `resourceGroup`, `subscription`, `if`, `equals`, `contains`,
   `empty`, `length`, `split`, `concat`, `toLower`, `int`, `union`, `tryGet`, `ipRangeContains` and
   the rest of the documented set). All documented operators are supported, with Azure's case rules.
4. Effects run in Azure's order. `append` writes its `details` into the body the handler then reads,
   and denies on a conflicting existing value. `deny` answers `403 RequestDisallowedByPolicy` with
   the message Azure writes (the assignment's `nonComplianceMessages` as the reason, else the policy
   identifiers) and an `additionalInfo[].info` of type `PolicyViolation` carrying the evaluated
   expressions. `audit` is recorded once the handler has answered with a 2xx. Under
   `enforcementMode: DoNotEnforce` the rules are evaluated but nothing is applied or recorded.

`requestContext().identity` is read from the bearer token of the request (`idtyp`, `appid`, `oid`),
so rules that gate on the caller type behave as they do in Azure when the request carries a token
from the emulator's Entra endpoint.

### Inspecting what fired

Every effect that fired is kept in memory, newest last, with the fields the Activity Log carries for a
`Microsoft.Authorization/policies/{deny|audit|append}/action` event:

```bash
curl -s "http://localhost:4577/_admin/policy/evaluations" | jq .
curl -s "http://localhost:4577/_admin/policy/evaluations?effect=deny&resourceId=/subscriptions/.../storageAccounts/sa1"
curl -s -X DELETE "http://localhost:4577/_admin/policy/evaluations"
```

Each entry names the resource, the assignment, the definition, the set definition and reference id when
there is one, the resolved effect, the enforcement mode, the caller and claims, the correlation id and
the `evaluatedExpressions`. `POST /_admin/reset` empties the log too.

### Alias resolution

Azure resolves an alias through a per-api-version path table. The emulator has no table, so it resolves
an alias by convention: the alias must start with the resource's type, and the remainder is a property
path read from the request content, looking under `properties` when a segment is not a top-level key.
That is how the published aliases map for the resources the emulator serves, nested children included:
`Microsoft.Network/networkSecurityGroups/securityRules[*].access` reads each rule's
`properties.access`. An alias whose type is not the resource's type resolves to nothing, as in Azure.

---

## Configuration

```yaml
floci-az:
  services:
    policy:
      enabled: true
      enforcement: true
```

| Property | Env var | Default | Description |
|---|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_POLICY_ENABLED` | `true` | Enables the `Microsoft.Authorization` policy resources. When disabled, their paths fall through to the generic ARM handler and answer `404` |
| `enforcement` | `FLOCI_AZ_SERVICES_POLICY_ENFORCEMENT` | `true` | Evaluates the assigned rules on every resource write. When off, policies are stored but no request is denied, amended or audited |

---

## Intentional deviations

- **Request-time evaluation only.** `deny`, `append` and `audit` fire on writes; `modify`,
  `denyAction`, `auditIfNotExists`, `deployIfNotExists`, `manual`, `mutate` and the resource-provider
  modes (`Microsoft.Kubernetes.Data` and the like) evaluate to nothing. There is no compliance scan of
  existing resources and `Microsoft.PolicyInsights` (policy states, remediations, compliance summaries)
  is not implemented. A PATCH of a resource that cannot be read back (none stored, or a handler that
  answers something other than 200) is evaluated on the body it carries, and the merge is generic:
  a provider that replaces a nested collection on PATCH (a Redis cache's `redisConfiguration`, say)
  stores less than the policy saw when the patch supplies only part of it.
- **Management-group assignments apply to every subscription.** The emulator has no management-group
  hierarchy, so an assignment at `Microsoft.Management/managementGroups/{mg}` covers all subscriptions.
- **`Indexed` mode is approximated** as top-level provider resources: nested child types, extension
  resources and `Microsoft.Authorization` / `Microsoft.Resources` resources are skipped, resource
  groups are evaluated in `All` mode only.
- **Aliases resolve by convention**, not from Azure's alias table (see [Alias resolution](#alias-resolution)).
  A definition referenced through a built-in id evaluates nothing, since built-ins are not seeded.
- **Audit and deny events are kept in memory**, readable at `/_admin/policy/evaluations`; the Activity
  Log endpoint does not serve them yet.
- **No built-in definitions.** Azure ships thousands of built-in definitions and initiatives; none are
  seeded. Tenant-rooted listings return an empty collection, tenant-rooted reads return
  `PolicyDefinitionNotFound` / `PolicySetDefinitionNotFound`, and built-in ids
  (`/providers/Microsoft.Authorization/policyDefinitions/{guid}` and
  `/providers/Microsoft.Authorization/policySetDefinitions/{guid}`) are accepted in assignments and set
  definitions without validation. Every other reference, including relative ids and ids under another
  provider namespace, must name an existing definition or set definition.
- **Management groups are scopes, not resources.** `Microsoft.Management/managementGroups/{mg}` is
  accepted as a scope for definitions, set definitions, assignments and exemptions, but management
  groups themselves are not modelled: any id is accepted, there is no hierarchy, and a
  management-group assignment does not appear in subscription-scoped listings.
- **Policy rules are validated structurally only.** A rule must be an object with an `if` condition
  and a `then` block that names an `effect`; aliases, functions, parameter references and effect
  details are not checked. Assignment parameters are not validated against the definition's
  parameter schema.
- **Exemption scope is not validated** against the assignment's scope (`assignmentScopeValidation` is
  stored and echoed only).
- **Definition versions are not implemented.** `versions` sub-resources answer `404`; `version` and
  `definitionVersion` are stored and echoed as plain strings.
- **`systemData` and `metadata` audit fields** name the caller of the request the way Resource
  Manager does: a user by principal name (`upn`, `unique_name` or `preferred_username`), an
  application or managed identity by client id (`appid` / `azp`), with `createdByType` /
  `lastModifiedByType` of `User`, `Application` or `ManagedIdentity` (a token carrying `xms_mirid`).
  The object id is used only when a token lacks those claims, and `Key` is never reported since
  policy requests carry no shared key. The policy `metadata` fields carry the principal's object id in
  both `createdBy` and `updatedBy`, as Azure writes them. The token is read, not verified. A request without
  a readable token is stamped with a fixed emulator identity. `metadata.updatedBy` / `updatedOn` appear
  only after the first update instead of being present as `null` from creation.
- **Listings are unpaginated** (`nextLink` is never returned) and `$top` is ignored.
- **Policy state is in-memory only**, regardless of `storage.mode`, like the rest of the ARM control
  plane.
