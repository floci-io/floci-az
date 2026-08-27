# Azure Container Apps

Compatible with Azure Resource Manager clients using `Microsoft.App/managedEnvironments` and `Microsoft.App/containerApps`.

Real mode runs each active revision replica as Docker containers. Mocked mode keeps full ARM and revision state without starting Docker.

## Features

- Managed Environment create, get, update, delete, and list
- Container App create, get, update, delete, and list
- Versioned templates with Single and Multiple active revision modes
- Revision list, get, activate, deactivate, and restart
- Container commands, arguments, environment variables, and secret references
- External and internal HTTP ingress forwarded to running revision containers
- Weighted revision traffic and round-robin replica selection with unhealthy replica failover
- `minReplicas` and `maxReplicas` validation; local replicas start at `minReplicas`
- Scale-to-zero apps start one replica on first ingress request when `maxReplicas` permits
- Mocked mode for Docker-free tests

Scale rules beyond minimum/maximum replicas are retained in ARM responses but are not evaluated locally.
Internal ingress accepts only transport peers whose source IP belongs to a Docker IPAM subnet used by a running Container App replica. Forwarded headers cannot make a public caller internal.

## ARM endpoints

```text
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/managedEnvironments/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/managedEnvironments/{name}
PATCH  /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/managedEnvironments/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/managedEnvironments/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/managedEnvironments
GET    /subscriptions/{sub}/providers/Microsoft.App/managedEnvironments

PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/containerApps/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/containerApps/{name}
PATCH  /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/containerApps/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.App/containerApps/{name}
POST   .../containerApps/{name}/listSecrets
GET    .../containerApps/{name}/revisions
GET    .../containerApps/{name}/revisions/{revision}
POST   .../containerApps/{name}/revisions/{revision}/activate
POST   .../containerApps/{name}/revisions/{revision}/deactivate
POST   .../containerApps/{name}/revisions/{revision}/restart
```

## Example

Create an environment:

```bash
curl -X PUT 'http://localhost:4577/subscriptions/dev/resourceGroups/apps/providers/Microsoft.App/managedEnvironments/local?api-version=2025-07-01' \
  -H 'Content-Type: application/json' \
  -d '{"location":"eastus","properties":{}}'
```

Create an externally accessible app:

```bash
curl -X PUT 'http://localhost:4577/subscriptions/dev/resourceGroups/apps/providers/Microsoft.App/containerApps/hello?api-version=2025-07-01' \
  -H 'Content-Type: application/json' \
  -d '{
    "location":"eastus",
    "properties":{
      "environmentId":"/subscriptions/dev/resourceGroups/apps/providers/Microsoft.App/managedEnvironments/local",
      "configuration":{
        "activeRevisionsMode":"Single",
        "secrets":[{"name":"token","value":"local-secret"}],
        "ingress":{"external":true,"targetPort":80}
      },
      "template":{
        "revisionSuffix":"v1",
        "containers":[{
          "name":"web",
          "image":"nginx:alpine",
          "env":[{"name":"TOKEN","secretRef":"token"}]
        }],
        "scale":{"minReplicas":1,"maxReplicas":3}
      }
    }
  }'
```

The response returns a globally unique value in `properties.configuration.ingress.fqdn`. Route that hostname to floci-az, then call it through port 4577:

```bash
FQDN=$(curl -s 'http://localhost:4577/subscriptions/dev/resourceGroups/apps/providers/Microsoft.App/containerApps/hello?api-version=2025-07-01' | jq -r '.properties.configuration.ingress.fqdn')
curl -H "Host: $FQDN" http://localhost:4577/
```

## Configuration

```yaml
floci-az:
  services:
    container-apps:
      enabled: true
      mocked: false
      dns-suffix: azurecontainerapps.io
      ingress-timeout-seconds: 60
```

| Environment variable | Default | Description |
|---|---:|---|
| `FLOCI_AZ_SERVICES_CONTAINER_APPS_ENABLED` | `true` | Enables `Microsoft.App` routing |
| `FLOCI_AZ_SERVICES_CONTAINER_APPS_MOCKED` | `false` | Keeps ARM state without Docker containers |
| `FLOCI_AZ_SERVICES_CONTAINER_APPS_DNS_SUFFIX` | `azurecontainerapps.io` | Suffix returned in environment and app FQDNs |
| `FLOCI_AZ_SERVICES_CONTAINER_APPS_INGRESS_TIMEOUT_SECONDS` | `60` | Backend connect/request timeout |

Real mode requires access to Docker daemon. Template containers in one replica share the leader container's network namespace, so sidecars can communicate over `localhost`. The leader receives the dynamic host-port binding for the shared ingress target port. A replica becomes healthy only after that port accepts TCP connections. Requests still enter floci-az on port 4577.
