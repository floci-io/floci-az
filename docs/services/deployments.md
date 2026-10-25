# ARM Template Deployments

floci-az runs ARM template deployments (`Microsoft.Resources/deployments`) at resource-group and subscription
scope: the calls behind `az deployment group create` and `az deployment sub create`, Terraform's
`azurerm_resource_group_template_deployment`, and any Bicep file once the client has compiled it to ARM JSON.

Each resource in the template is created through the same ARM routing, request gate and handler a client
request would reach, so a deployed storage account, virtual network or key vault is exactly the resource a
direct `PUT` creates, and every other floci-az service sees it.

**Protocol:** ARM REST, JSON

**Path:** `/subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Resources/deployments/{name}`, or
`/subscriptions/{sub}/providers/Microsoft.Resources/deployments/{name}` at subscription scope

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
POST   .../deployments/{name}/whatIf             # the changes it would make, 200
POST   /providers/Microsoft.Resources/calculateTemplateHash   # {"minifiedTemplate", "templateHash"}
```

The resource group must exist, or the answer is `404 ResourceGroupNotFound`. Every endpoint is served at
subscription scope too, under `/subscriptions/{sub}/providers/Microsoft.Resources/deployments`, and at tenant
scope, under `/providers/Microsoft.Resources/deployments`.

## Subscription-scope deployments

A subscription-scope deployment deploys subscription-level resources, typically resource groups
(`Microsoft.Resources/resourceGroups`, whose id is `/subscriptions/{sub}/resourceGroups/{name}`), and policy.
As Azure requires, the body carries a `location` where the deployment's data is stored: a missing one is
`400 LocationRequired`, and reusing a deployment name with another location is
`400 InvalidDeploymentLocation`. A resource type that lives in a resource group (a storage account, say) is
`InvalidTemplate` at this scope, and `resourceGroup()` is an error here. Deploying into a resource group from a
subscription-scope template takes a nested deployment (see below), which is what `azd provision` and a Bicep file
with `targetScope = 'subscription'` and modules send.

## Tenant-scope deployments

A tenant-scope deployment (`az deployment tenant create`) carries a `location` on the same terms as a
subscription-scope one, and its tenant is the caller's (the tenant of the request's token when the emulator holds
it, otherwise the default tenant). Its own resources are tenant-level ones; a resource that lives in a
subscription or a resource group is `InvalidTemplate` here, and `subscription()` and `resourceGroup()` are errors.
It reaches a subscription through a nested deployment that names `subscriptionId` (and `location`), and a
resource group through one that names both `subscriptionId` and `resourceGroup`, as Azure requires at this scope.
A nested deployment that targets a management group through `scope` is refused with `InvalidTemplate`.

`resourceId()` without a subscription or a resource group takes the deployment's level, as Azure documents: a
resource-group id in a resource-group deployment, `/subscriptions/{sub}/providers/...` at subscription scope, and
`/providers/...` at tenant scope.

## Nested deployments

A `Microsoft.Resources/deployments` resource inside a template, which a Bicep module compiles to, is deployed
as a deployment of its own at its target: the resource group its `resourceGroup` property names (in the
subscription `subscriptionId` names, else the parent's), the subscription `subscriptionId` alone names, or else
the parent's own scope. It runs like any other deployment, so it is listed, polled and read at its target, and
the parent waits for it: `reference('module').outputs.name.value` reads its outputs once it has finished.

- With `expressionEvaluationOptions.scope` set to `inner`, as Bicep modules are, the nested template is its own
  template: its expressions use its own parameters, which the nested deployment's `parameters` pass in.
- With `outer`, the default, its expressions use the parent's parameters and variables: floci-az sends it with
  the parent's parameters declared as its own (defaulting to the parent's values) and the parent's variable values
  as its variables. `resourceGroup()` and `subscription()` there answer for the nested deployment's own target,
  where Azure answers the parent's; they differ only when an outer-scope nested deployment targets another scope.

A nested deployment's template is inline or linked (see Linked templates).

## Linked templates

A deployment's `templateLink.uri` and `parametersLink.uri` are downloaded over HTTP or HTTPS when the request
arrives, and the deployment runs the downloaded template as if it had been sent inline; the links are echoed in
the deployment's properties and in `deployment().properties.templateLink`. A template staged in floci-az's own blob
storage works, the local counterpart of a storage account with a SAS token.

- A nested deployment's `templateLink.relativePath` resolves against the link its parent's template came from,
  keeping the parent link's query string, as Azure does; a parent sent inline has no base, so it is refused.
- A link that cannot be downloaded (no `uri`, not HTTP or HTTPS, a non-`200` answer, a timeout after 30 seconds,
  more than 4 MB), whose content is not a JSON object, or whose content carries a different `contentVersion` than
  the link names is `400 InvalidContentLink`.
- `parameters` and `parametersLink` together are `400 InvalidTemplate`, as Azure documents; a parameters link may
  point at a parameter file (`{"parameters": {...}}`) or at the parameters object itself.
- A template spec (`templateLink.id`) is `400 InvalidTemplate`: floci-az does not serve template specs.

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

## What-if

`whatIf` prepares the deployment as a `PUT` would, then reads each resource's current state instead of writing
it: a resource that does not exist is a `Create`; one that does is a `Modify`, with a `delta` listing each
property the template sets to a different value, or a `NoChange`. Only the properties the template sets are
compared, so a provider's own read-only properties never count as changes; `after` is the current resource with
the template's values applied. A nested deployment, and a resource whose expressions cannot be evaluated before
deployment (a `reference()` to a resource the deployment would create), are `Unsupported` with the reason.
`whatIfSettings.resultFormat` `ResourceIdOnly` leaves the documents out. The prediction is only as good as the
providers' reads: a provider that answers `200` for anything it is asked about makes a new resource look like a
`NoChange`.

## Child resources

A child resource can be declared outside its parent, with the full type and name
(`Microsoft.Network/virtualNetworks/subnets`, `vnet1/default`), or inside the parent's `resources` array
with one segment each (`subnets`, `default`), at any depth. A child declared inside is read as a top-level
resource whose type and name carry its parent's, right after the parent, so template order creates the parent
first. As in Azure, it does not depend on its parent unless its `dependsOn` says so, and it is never sent as part
of the parent's body.

## Copy loops

All four kinds of copy loop are served, with `copyIndex([loopName], [offset])`:

- **Resources**: `"copy": {"name", "count", "mode", "batchSize"}` deploys one resource per iteration. A
  `dependsOn` entry naming the loop depends on every instance, and a loop whose `count` is 0 deploys nothing
  and satisfies any dependency on it. Instances are created one at a time in iteration order, which meets both
  `parallel` and `serial` mode.
- **Properties**: a `copy` array of `{"name", "count", "input"}` in a top-level resource's `properties` builds
  the array property of that name; `copyIndex` there names its loop.
- **Variables**: a `copy` array in `variables` defines one array variable per entry, and a `copy` array
  inside a variable's value builds an array property of it.
- **Outputs**: an output with `"copy": {"count", "input"}` is the array of its iterations.

A `count` is an integer from 0 to 800, as in Azure; anything else is `InvalidTemplate`.

## Template functions

| Group | Functions |
|---|---|
| Scope | `parameters`, `variables`, `resourceGroup`, `subscription`, `tenant`, `deployment`, `environment` |
| Resource ids | `resourceId` (every arity), `subscriptionResourceId`, `tenantResourceId`, `extensionResourceId` |
| Live | `reference` (by name or id, optional api-version and `'Full'`), every `list*` function (`listKeys`, ...) |
| Loops | `copyIndex` |
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

### User-defined functions

A template's `functions` section declares functions called as `namespace.member(...)`. Each one is its `output`
expression over its own parameters, checked against their declared types, and its result is checked against the
output's type. The scope functions (`resourceGroup()`, `subscription()`, `tenant()`, `deployment()`,
`environment()`) answer for the deployment. The restrictions Azure documents are each `400 InvalidTemplate`: a
function reads only its own parameters, never the template's or `variables()`, calls no other user-defined
function, uses neither `reference()` nor a `list*` function, and declares no parameter defaults. An outer-scope
nested deployment calls its parent's functions.

## Not supported yet

Each of these is refused with `400 InvalidTemplate`, never skipped:

- symbolic resource names (`languageVersion` 2.0)
- a copy loop on a child declared inside its parent, and children inside a parent that has a copy loop: as the
  ARM docs advise, declare such children outside the parent, with the parent's name in theirs
- the lambda functions (`filter`, `map`, `reduce`, `sort`, `toObject`, `groupBy`, `mapValues`), the CIDR
  functions, `pickZones`, `providers`, `references`, `managementGroup`, `deployer`, `roleDefinitions`
- Key Vault parameter references
- `Complete` mode, refused with `400 DeploymentModeNotSupported` rather than run as `Incremental`

Deployments at management-group scope answer `404`, and a nested deployment that targets a management group is
`400 InvalidTemplate`.

## Intentional deviations

- **`Retry-After: 1`** on the `PUT` and on every status that is not final yet: Azure sends none, and clients
  then fall back to their own, much longer, poll interval (30 seconds for the az CLI, which polls once straight
  after the `PUT`); a local deployment usually finishes within the first second.
- **A refused `cancel`** (of a deployment that is no longer running) is `409 DeploymentNotActive`; Azure
  documents that only an `Accepted` or `Running` deployment can be canceled, but not the error.
- **Delete** answers `204` at once; Azure answers `202` and lets the client poll.
- **`whatIf`** answers `200` with the result at once, one of the two answers the spec declares; Azure answers `202`
  and lets the client poll a `Location`. An array that changed is one `Modify` of the whole array, where Azure
  lists its element changes as `Array` children, and a nested deployment's own changes are not predicted.
- **The first failing resource stops the deployment.** Azure goes on with the resources that do not depend
  on it.
- **`templateHash`** is a 64-bit hash of the minified, uppercased template, so it is the same for a deployment
  and for `calculateTemplateHash` of the same template whatever its whitespace or casing, which is what azd
  compares to skip an unchanged deployment. Azure does not publish its algorithm, so the value differs from
  Azure's.
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
