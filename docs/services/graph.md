# Microsoft Graph

A narrow slice of Microsoft Graph at `/v1.0/...`, added alongside
[Entra ID phase 2](entra.md) ([#120](https://github.com/floci-io/floci-az/issues/120)): service
principal discovery (used by the azurerm provider) and group-membership management — not a
general-purpose Graph emulator. Directory data (users, groups, membership) is shared with
[Microsoft Entra ID](entra.md): a token's `oid` claim and a Graph lookup resolve to the same
directory identity, and the [dev seed](entra.md#default-tenant--dev-credentials) (dev user + dev
group) is available here too.

## Endpoints

| Path | Purpose |
|---|---|
| `GET /v1.0/servicePrincipals?$filter=appId eq '{id}'` | Service principal discovery (azurerm provider bootstrap) |
| `POST /v1.0/users/{id}/getMemberGroups` | Group object ids a user directly belongs to |
| `POST /v1.0/groups/{id}/members/$ref` | Add a member to a group |
| `DELETE /v1.0/groups/{id}/members/{id}/$ref` | Remove a member from a group |

`{id}` in `users/{id}` accepts either the user's object id or its userPrincipalName, as real Graph
does. Only **direct** membership is modeled — there is no nested-group transitivity.

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
(`https://graph.microsoft.com/v1.0/directoryObjects/{id}`) — the emulator parses the trailing id
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

## Out of scope

Full Graph CRUD (applications, service principals beyond discovery, directory roles, conditional
access, ...) and app-registration management remain open
([#23](https://github.com/floci-io/floci-az/issues/23)).
