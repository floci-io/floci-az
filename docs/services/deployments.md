# ARM Template Deployments

floci-az runs ARM template deployments (`Microsoft.Resources/deployments`) at resource-group scope: the
calls behind `az deployment group create`, Terraform's `azurerm_resource_group_template_deployment`, and
any Bicep file once the client has compiled it to ARM JSON.

Each resource in the template is created through the same ARM routing, request gate and handler a client
request would reach, so a deployed storage account, virtual network or key vault is exactly the resource a
direct `PUT` creates, and every other floci-az service sees it.

**Protocol:** ARM REST, JSON

**Path:** `/subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Resources/deployments/{name}`

**Api-versions:** the Resources spec 2025-04-01, which the az CLI sends; azurerm sends 2020-06-01, whose
operations and shapes are the same.

## Configuration

```yaml
floci-az:
  services:
    deployments:
      enabled: true    # FLOCI_AZ_SERVICES_DEPLOYMENTS_ENABLED
```

With `enabled: false` a deployment path answers the generic ARM `404 ResourceNotFound` it answered before
deployments existed.

## Endpoints

```
PUT    .../deployments/{name}                    # CreateOrUpdate: 201 (200 on a re-PUT), Accepted, then runs
GET    .../deployments/{name}/operationStatuses/{id}   # the poll the PUT's Azure-AsyncOperation names
GET    .../deployments/{name}                    # Get
HEAD   .../deployments/{name}                    # CheckExistence: 204 or 404
DELETE .../deployments/{name}                    # Delete: removes the record, never the resources; 204
GET    .../deployments[?$filter=provisioningState eq 'X'&$top=N]   # nextLink when $top leaves more
GET    .../deployments/{name}/operations[/{operationId}]           # also without providers/Microsoft.Resources
POST   .../deployments/{name}/validate           # 200, or 400 with the template error
POST   .../deployments/{name}/exportTemplate     # {"template": ...}
POST   .../deployments/{name}/cancel             # 204 while it runs
```

The resource group must exist, or the answer is `404 ResourceGroupNotFound`.

## How a deployment runs

A `PUT` checks the template at once and answers `201` with `provisioningState: Accepted` and an
`Azure-AsyncOperation` header, as Azure does. The deployment then runs in the background: the
`operationStatuses` URL answers `{"status": "Accepted"}` until it ends, then `Succeeded`, `Failed` with the
error, or `Canceled`, and `GET` of the deployment shows `Running` and then the final state. A `PUT` or
`DELETE` of a deployment that is still running is `409 DeploymentActive`. `cancel` stops it before its next
resource and leaves what it already created in place.

1. The template is read and checked: parameters (type and `allowedValues`), variables, resources and
   outputs. A template floci-az cannot run is refused with `400 InvalidTemplate` before anything is created.
2. Each resource's `type`, `name` and `dependsOn` are evaluated, and the resources are ordered by their
   dependencies: an explicit `dependsOn` entry (a name, `type/name`, or an id), and an implicit dependency
   for every `reference()` or `list*()` call that names a resource by name. As in Azure, `resourceId()`
   alone creates no dependency, and a child resource does not depend on its parent unless `dependsOn` says
   so. A cycle, or a dependency on a resource the template does not declare, is an `InvalidTemplate`.
3. Each resource is evaluated and sent as `PUT {id}?api-version={its apiVersion}`, without the template-only
   keys (`type`, `apiVersion`, `name`, `dependsOn`, `condition`, `comments`). A resource whose `condition`
   is false is skipped. A `202`, or a `provisioningState` that is not terminal, is polled with `GET`.
4. The outputs are evaluated once every resource exists, so `reference()` reads the live resource.

Each resource the run tries is a deployment operation (`provisioningOperation: Create`, with the provider's
answer as `statusCode`, `OK` or `Created`, its `targetResource`, and a `statusMessage` holding the error when it
failed), followed by one `EvaluateDeploymentOutput` operation, as Azure records them. The operations list is
served both under `.../providers/Microsoft.Resources/deployments/{name}/operations` and under the spec's own
`.../resourcegroups/{rg}/deployments/{name}/operations`, which has no provider segment and is the form the az
CLI sends.

The deployment answers with Azure's `DeploymentExtended` body: `provisioningState`, the echoed
`parameters` (a `securestring` or `secureObject` value is never echoed), `outputs`, `outputResources`,
`providers`, `dependencies`, `templateHash`, `timestamp`, `duration` and `correlationId`. When a resource
fails, the deployment ends `Failed` with Azure's `DeploymentFailed` error, whose `details` hold the failing
resource's own error; a resource that ends in a `Failed` provisioning state is a
`ResourceDeploymentFailure`.

## Template functions

| Group | Functions |
|---|---|
| Scope | `parameters`, `variables`, `resourceGroup`, `subscription`, `tenant`, `deployment`, `environment` |
| Resource ids | `resourceId` (every arity), `subscriptionResourceId`, `tenantResourceId`, `extensionResourceId` |
| Live | `reference` (by name or id, optional api-version and `'Full'`), every `list*` function (`listKeys`, ...) |
| Logical | `true`, `false`, `null`, `not`, `and`, `or`, `if`, `bool`, `coalesce` |
| Comparison | `equals`, `less`, `lessOrEquals`, `greater`, `greaterOrEquals` |
| String | `concat`, `format`, `uniqueString`, `guid`, `newGuid`, `toLower`, `toUpper`, `string`, `int`, `float`, `json`, `length`, `empty`, `first`, `last`, `take`, `skip`, `split`, `replace`, `substring`, `trim`, `padLeft`, `startsWith`, `endsWith`, `contains`, `indexOf`, `lastIndexOf`, `like` |
| Encoding | `base64`, `base64ToString`, `base64ToJson`, `dataUri`, `dataUriToString`, `uri`, `uriComponent`, `uriComponentToString` |
| Array and object | `union`, `intersection`, `createArray`, `createObject`, `items`, `objectKeys`, `shallowMerge`, `tryGet`, `indexFromEnd`, `tryIndexFromEnd`, `distinct`, `join`, `array`, `range` |
| Numeric | `add`, `sub`, `mul`, `div`, `mod`, `min`, `max` |
| Date | `utcNow`, `dateTimeAdd`, `dateTimeFromEpoch`, `dateTimeToEpoch` |

`environment()` returns the subscription's cloud's own metadata (Azure's endpoints and suffixes), so a
template that builds a hostname from `environment().suffixes.storage` gets the real Azure suffix, which the
emulator routes.

## Not supported yet

Each of these is refused with `400 InvalidTemplate`, never skipped:

- `copy` loops (resources and outputs) and `copyIndex()`
- nested `resources`, nested deployments (`Microsoft.Resources/deployments` inside a template, which Bicep
  modules compile to), user-defined `functions`, and symbolic resource names (`languageVersion` 2.0)
- the lambda functions (`filter`, `map`, `reduce`, `sort`, `toObject`, `groupBy`, `mapValues`), the CIDR
  functions, `pickZones`, `providers`, `references`, `managementGroup`, `deployer`, `roleDefinitions`
- linked templates and parameter files (`templateLink`, `parametersLink`) and Key Vault parameter
  references
- `Complete` mode, refused with `400 DeploymentModeNotSupported` rather than run as `Incremental`

Deployments at subscription, management-group and tenant scope and `whatIf` answer `404`.

## Intentional deviations

- **`Retry-After: 1`** on the `PUT` and on every status that is not final yet: Azure sends none, and clients
  then fall back to their own, much longer, poll interval (30 seconds for the az CLI, which polls once straight
  after the `PUT`); a local deployment usually finishes within the first second.
- **A refused `cancel`** (of a deployment that is no longer running) is `409 DeploymentNotActive`; Azure
  documents that only an `Accepted` or `Running` deployment can be canceled, but not the error.
- **Delete** answers `204` at once; Azure answers `202` and lets the client poll.
- **The first failing resource stops the deployment.** Azure goes on with the resources that do not depend
  on it.
- **`uniqueString` and `guid`** have Azure's length and form but not its values: `uniqueString` is 13
  lowercase base32 characters from a SHA-256 hash, `guid` a name-based UUID. A template that only needs
  stable unique names works; one that compares against a value computed in Azure does not.
- **`format` and the date functions** support `{n}`, `{n,width}` and the `N`, `D` and `X` number formats,
  and the `u`, `d`, `s` and `o` date formats with the common custom tokens; any other format string is an
  `InvalidTemplate` rather than a guess.
- **`if`, `and` and `or` short-circuit**, so a `reference()` in a branch the condition excludes is never
  sent.
- **Validation messages** start with Azure's `Deployment template validation failed:` and use its codes, but
  the wording after it is floci-az's.
- **Deployments live in memory**, as the rest of ARM state does.
