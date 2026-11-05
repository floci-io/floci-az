# Microsoft Graph

A narrow slice of Microsoft Graph at `/v1.0/...`, added alongside
[Entra ID phase 2](entra.md) ([#120](https://github.com/floci-io/floci-az/issues/120)): service
principal discovery (used by the azurerm provider) and group-membership management, not a
general-purpose Graph emulator. Directory data (users, groups, membership) is shared with
[Microsoft Entra ID](entra.md): a token's `oid` claim and a Graph lookup resolve to the same
directory identity, and the [dev seed](entra.md#default-tenant--dev-credentials) (dev user + dev
group) is available here too.

## Endpoints

| Path | Purpose |
|---|---|
| `GET /v1.0/servicePrincipals?$filter=appId eq '{id}'` | Service principal discovery (azurerm provider bootstrap) |
| `GET /v1.0/groups?$filter=id in ('id1','id2')&$expand=members` | List groups, optionally filtered and expanded |
| `POST /v1.0/users/{id}/getMemberGroups` | Group object ids a user directly belongs to |
| `POST /v1.0/groups/{id}/members/$ref` | Add a member to a group |
| `DELETE /v1.0/groups/{id}/members/{id}/$ref` | Remove a member from a group |
| `GET /v1.0/applications` | App registrations, with `$filter=appId eq '{id}'`, `displayName eq '{name}'` or `startswith(displayName,'{prefix}')` |
| `GET /v1.0/applications(appId='{id}')` | One registration by application (client) id |
| `GET` / `PATCH` / `DELETE /v1.0/applications/{id}` | One registration by object id (`PATCH` replaces the properties it names and keeps the rest, a `null` clearing one) |
| `POST /v1.0/applications` | Create a registration from the `application` object sent (`displayName` required; every writable property is stored and read back); a service principal is created with it |
| `POST /v1.0/applications/{id}/addPassword` | Add a client secret: answers the `passwordCredential` with its `secretText` once |
| `POST /v1.0/applications/{id}/removePassword` | Remove a client secret by `keyId` |
| `GET /v1.0/applications/{id}/owners` | The registration's owners, typed `#microsoft.graph.user` or `#microsoft.graph.servicePrincipal` |
| `POST /v1.0/applications/{id}/owners/$ref` | Add an owner from `{"@odata.id": ".../directoryObjects/{id}"}`; a user or service principal of the tenant |
| `DELETE /v1.0/applications/{id}/owners/{id}/$ref` | Remove an owner |

`{id}` in `users/{id}` accepts either the user's object id or its userPrincipalName, as real Graph
does. Only **direct** membership is modeled: there is no nested-group transitivity.

### List groups and expand members

`GET /v1.0/groups` lists groups in the default tenant's directory. `$filter` supports `eq`
and `in` with string values on `id` or `displayName`; escape an apostrophe inside a value
as two apostrophes. Add `$expand=members` to include each group's direct users, groups and
service principals. An empty group has `members: []`; members are omitted without expansion.
Only public identity fields are returned, never application credentials or internal storage fields.

```bash
curl -sG http://localhost:4577/v1.0/groups \
  --data-urlencode "\$filter=id in ('44444444-4444-4444-4444-444444444444')" \
  --data-urlencode '$expand=members'
```

The response has `@odata.context` and a `value` collection. This is a bounded subset:
compound filters, other filter operators, nested expansion, ordering and pagination are not
implemented. Unsupported filter expressions or expansions return `400 Request_BadRequest`.
The endpoint reads seeded directory records; it does not add directory provisioning APIs.

### `getMemberGroups`

```bash
curl -s -X POST http://localhost:4577/v1.0/users/dev-user@floci-az.local/getMemberGroups \
  -H "Content-Type: application/json" \
  -d '{"securityEnabledOnly": false}'
```

```json
{
  "@odata.context": "https://graph.microsoft.com/v1.0/$metadata#Collection(Edm.String)",
  "value": ["44444444-4444-4444-4444-444444444444"]
}
```

`securityEnabledOnly: true` filters the result to groups with `securityEnabled: true` (the seeded
dev group is security-enabled). A user id/UPN that does not resolve to a directory user returns
`404 Request_ResourceNotFound`.

### `members/$ref`

The request body's `@odata.id` points at *real* Graph
(`https://graph.microsoft.com/v1.0/directoryObjects/{id}`): the emulator parses the trailing id
rather than validating the host, since that URL shape is what SDKs/tools send regardless of which
Graph endpoint they're pointed at:

```bash
curl -s -X POST http://localhost:4577/v1.0/groups/44444444-4444-4444-4444-444444444444/members/\$ref \
  -H "Content-Type: application/json" \
  -d '{"@odata.id": "https://graph.microsoft.com/v1.0/directoryObjects/<member-object-id>"}'

curl -s -X DELETE \
  http://localhost:4577/v1.0/groups/44444444-4444-4444-4444-444444444444/members/<member-object-id>/\$ref
```

Both return `204 No Content` on success; a group id that doesn't exist returns
`404 Request_ResourceNotFound`.

## Configuration

```yaml
floci-az:
  services:
    graph:
      enabled: true   # Microsoft Graph slice at /v1.0/...
```

| Setting | Env var | Default |
|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_GRAPH_ENABLED` | `true` |

### Applications and client secrets

```bash
# Register an app and mint a secret for it
APP=$(curl -s -X POST http://localhost:4577/v1.0/applications -H "Content-Type: application/json" \
  -d '{"displayName": "my-connector"}')
OBJECT_ID=$(echo "$APP" | jq -r .id)
curl -s -X POST "http://localhost:4577/v1.0/applications/$OBJECT_ID/addPassword" -H "Content-Type: application/json" \
  -d '{"passwordCredential": {"displayName": "ci", "endDateTime": "2030-01-01T00:00:00Z"}}' | jq .
# Read it back: passwordCredentials carry keyId, hint, startDateTime and endDateTime, never the secret
curl -s "http://localhost:4577/v1.0/applications(appId='$(echo "$APP" | jq -r .appId)')" | jq .passwordCredentials
```

The seeded dev app (`11111111-1111-1111-1111-111111111111`) is listed with its seeded secret, whose
`startDateTime` and `endDateTime` are null because the seed has no dates. The token endpoint issues
tokens for any registered app id; with `validate-client-credentials: true` it checks the secret and its
dates, refusing one past its `endDateTime` with `AADSTS7000222` and one before its `startDateTime` with
`AADSTS7000215` (see [Entra](entra.md)). Service-principal discovery answers the stored principal's
object id, the same value the app's tokens carry as `oid`. A supplied `passwordCredential` field must be
valid (an object, string dates in order, a string `displayName`); defaults stand in for omitted fields
only, and a `PATCH` `displayName` must be a non-empty string. A path segment after an action
(`.../removePassword/extra`) answers `400` without touching credentials. On create, the principal the
caller's token names (`oid`, when it is a user or service principal of the tenant) becomes the first owner,
as Graph makes the signed-in principal one; a client that then sets its own owners, as the Terraform
`azuread_application` resource does, removes it the same way.

## Out of scope

Full Graph CRUD beyond the slice above (service principals beyond discovery, users and groups
management, directory roles, conditional access, application `keyCredentials`, API permissions and
`requiredResourceAccess`, owners, federated identity credentials) remains open
([#23](https://github.com/floci-io/floci-az/issues/23)).
