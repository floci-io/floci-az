# API Management

floci-az includes an **in-process API Management emulator** intended for local development, SDK
compatibility tests, and CI workflows that need APIM-shaped ARM resources plus a lightweight gateway.
It is **not** a full Azure APIM gateway implementation: no sidecar is started; everything runs
in-process.

## Endpoints

| Plane | Path |
|---|---|
| Management (ARM) | `Microsoft.ApiManagement/service/...` |
| Gateway | `/{account}-apim/{serviceName}/{apiPath...}`, e.g. `http://localhost:4577/devstoreaccount1-apim/{serviceName}/...` |

## Supported

- **Management service** resources: create, get, delete, and list in a resource group or across the subscription.
- **API** resources: create, get, list, delete.
- **Operation** resources: create, get, list, delete.
- **Policy** resources at service, API, and operation scope.
- **Product** resources and product-to-API links.
- **Subscription** resources with gateway subscription-key enforcement.
- **Named values**, including secret named values whose `properties.value` is not exposed in ARM responses.
- **Backends** and `<set-backend-service backend-id="...">`.
- **OpenAPI JSON import** for APIs (operation generation from `paths`); reimport replaces previously generated operations.
- **Gateway routing** for API paths and operation URL templates, with backend proxying when an API `serviceUrl` or backend policy is configured.

### Gateway requests to an API without a backend

When the matched API has no `serviceUrl`, no `<set-backend-service>` policy applies and no
`<return-response>` policy answers the request, the gateway responds with `500` and a JSON error body:

```json
{"error": {"code": "BackendNotConfigured", "message": "API 'catalog-api' in service 'my-apim' has no backend: ..."}}
```

This status and body are an emulator choice: the response real API Management returns in this case is not
covered by the references floci-az is checked against. To mock an API without a backend, configure a
`<return-response>` policy, which is the Azure way to answer from the gateway itself.

### Supported policy subset

The policy engine intentionally supports a focused subset:

- `<set-header>` with `exists-action="override" | skip | append | delete`
- `<set-query-parameter>` with `exists-action="override" | skip | delete`
- `<rewrite-uri template="...">`
- `<set-backend-service base-url="...">` and `<set-backend-service backend-id="...">`
- `<return-response>` with nested `<set-status code="...">` and `<set-body>`
- Named-value interpolation with `{{name}}` in supported policy values

## Service names

A service name is a global DNS name (`{name}.azure-api.net`, `azure-api.us` in Azure US Government, `azure-api.cn` in
Azure China): the first subscription and resource group to create it own it, and a create of the same name from any
other scope is refused with `409 ServiceAlreadyExists` until the owner deletes it. Another scope reads the name as
`404`, and its `DELETE` is a no-op `200` that leaves the owner's service in place. The name is unique across every
cloud the emulator serves, since one gateway serves every service by name. There is no `checkNameAvailability` for API
Management services. Paths that differ only in case address the same service and its child resources. With
`parity-overrides.strict-names` a name outside 1 to 50 letters, digits and hyphens, starting with a letter and ending
with a letter or digit, is refused with `400 InvalidResourceName` (see [ARM name syntax](arm.md#name-syntax)).

Services are kept through the storage layer under the store name `apim`, so they follow the global `storage.mode`.
Their child resources (APIs, operations, policies, products, subscriptions, named values and backends) are still kept
in memory only, so under a persistent mode a restart keeps the services but not their children.

## Configuration

```yaml
floci-az:
  services:
    apim:
      enabled: true
```

| Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_APIM_ENABLED` | `true` | Enable/disable the service |

## Out of scope

- The full Azure policy language (only the subset above is interpreted)
- Developer portal, products workflow approvals, and analytics
- Self-hosted gateways, multi-region deployment, and custom domains
- Rate-limiting/quota, JWT validation, and caching policies
