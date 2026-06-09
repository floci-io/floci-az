# API Management

floci-az includes an **in-process API Management emulator** intended for local development, SDK
compatibility tests, and CI workflows that need APIM-shaped ARM resources plus a lightweight gateway.
It is **not** a full Azure APIM gateway implementation — no sidecar is started; everything runs
in-process.

## Endpoints

| Plane | Path |
|---|---|
| Management (ARM) | `Microsoft.ApiManagement/service/...` |
| Gateway | `/{account}-apim/{serviceName}/{apiPath...}` — e.g. `http://localhost:4577/devstoreaccount1-apim/{serviceName}/...` |

## Supported

- **Management service** resources: create, get, list, delete.
- **API** resources: create, get, list, delete.
- **Operation** resources: create, get, list, delete.
- **Policy** resources at service, API, and operation scope.
- **Product** resources and product-to-API links.
- **Subscription** resources with gateway subscription-key enforcement.
- **Named values**, including secret named values whose `properties.value` is not exposed in ARM responses.
- **Backends** and `<set-backend-service backend-id="...">`.
- **OpenAPI JSON import** for APIs (operation generation from `paths`); reimport replaces previously generated operations.
- **Gateway routing** for API paths and operation URL templates, with backend proxying when an API `serviceUrl` or backend policy is configured.

### Supported policy subset

The policy engine intentionally supports a focused subset:

- `<set-header>` with `exists-action="override" | skip | append | delete`
- `<set-query-parameter>` with `exists-action="override" | skip | delete`
- `<rewrite-uri template="...">`
- `<set-backend-service base-url="...">` and `<set-backend-service backend-id="...">`
- `<return-response>` with nested `<set-status code="...">` and `<set-body>`
- Named-value interpolation with `{{name}}` in supported policy values

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
