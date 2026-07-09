# Managed Identity

Compatible with the `azure-identity` `ManagedIdentityCredential` (Java, Python, Node.js), the
`Microsoft.ManagedIdentity` ARM management plane, and any HTTP client. Covers **user-assigned
identities** (with federated identity credentials) and the **IMDS token endpoint** that
applications use to acquire tokens without secrets.

> **HTTP-only — no Docker.** ARM CRUD state is in-memory; IMDS tokens are minted with the same
> RSA signing key as the [Entra ID](entra.md) emulation, so they verify against the emulator JWKS.

---

## Features

- **User-assigned identities** — CreateOrUpdate, Get, Update (tags), Delete, List (by resource
  group and by subscription); `principalId` / `clientId` / `tenantId` are server-generated GUIDs
  that stay stable across updates (msi spec `2024-11-30`)
- **Federated identity credentials** — CreateOrUpdate, Get, Delete, List with
  `issuer` / `subject` / `audiences` (as used by `azurerm_federated_identity_credential`)
- **System-assigned identity read** — `GET {scope}/providers/Microsoft.ManagedIdentity/identities/default`
  returns deterministic per-scope GUIDs
- **IMDS token endpoint** — `GET /metadata/identity/oauth2/token` (imds spec `2023-07-01`):
  requires the `Metadata: true` header, accepts `resource` plus an optional `client_id` /
  `object_id` / `msi_res_id` selector, and returns the all-string IMDS response shape. Any
  `api-version` is accepted (SDKs send `2018-02-01`)
- **Verifiable tokens** — v1.0 JWTs (`appid`, `oid`, `idtyp=app`) signed by the Entra key;
  validate against `GET /common/discovery/v2.0/keys`

---

## Endpoints

```
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ManagedIdentity/userAssignedIdentities/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ManagedIdentity/userAssignedIdentities/{name}
PATCH  /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ManagedIdentity/userAssignedIdentities/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ManagedIdentity/userAssignedIdentities/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ManagedIdentity/userAssignedIdentities
GET    /subscriptions/{sub}/providers/Microsoft.ManagedIdentity/userAssignedIdentities

PUT    .../userAssignedIdentities/{name}/federatedIdentityCredentials/{ficName}
GET    .../userAssignedIdentities/{name}/federatedIdentityCredentials/{ficName}
DELETE .../userAssignedIdentities/{name}/federatedIdentityCredentials/{ficName}
GET    .../userAssignedIdentities/{name}/federatedIdentityCredentials

GET    /{scope}/providers/Microsoft.ManagedIdentity/identities/default

GET    /metadata/identity/oauth2/token?resource={resource}[&client_id=...]   # header: Metadata: true
```

---

## Quickstart

### 1 — Create a user-assigned identity

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.ManagedIdentity/userAssignedIdentities/my-identity?api-version=2024-11-30" \
  -H "Content-Type: application/json" \
  -d '{"location":"eastus"}'
```

```json
{
  "name": "my-identity",
  "type": "Microsoft.ManagedIdentity/userAssignedIdentities",
  "properties": {
    "tenantId": "00000000-0000-0000-0000-000000000002",
    "principalId": "e3b0c442-…",
    "clientId": "9f86d081-…"
  }
}
```

### 2 — Acquire a token via IMDS (raw HTTP)

```bash
curl -s -H "Metadata: true" \
  "http://localhost:4577/metadata/identity/oauth2/token?resource=https://management.azure.com/&api-version=2018-02-01&client_id={clientId}"
```

Omit `client_id` for a system-assigned token. All response values are strings, per the real
IMDS contract:

```json
{
  "access_token": "eyJ0…",
  "client_id": "9f86d081-…",
  "expires_in": "3599",
  "expires_on": "1767225599",
  "ext_expires_in": "3599",
  "not_before": "1767221999",
  "resource": "https://management.azure.com/",
  "token_type": "Bearer"
}
```

### 3 — Use `ManagedIdentityCredential` from the SDKs

The azure-identity SDKs target `http://169.254.169.254` by default. Point them at the emulator
with `AZURE_POD_IDENTITY_AUTHORITY_HOST` (honored by the Java, Python, and Node.js SDKs):

```bash
export AZURE_POD_IDENTITY_AUTHORITY_HOST=http://localhost:4577
```

=== "Python"

    ```python
    from azure.identity import ManagedIdentityCredential

    credential = ManagedIdentityCredential(client_id="{clientId}")  # or no args for system-assigned
    token = credential.get_token("https://management.azure.com/.default")
    ```

=== "Java"

    ```java
    ManagedIdentityCredential credential = new ManagedIdentityCredentialBuilder()
            .clientId("{clientId}")
            .build();
    AccessToken token = credential
            .getToken(new TokenRequestContext().addScopes("https://management.azure.com/.default"))
            .block();
    ```

=== "Node.js"

    ```javascript
    const { ManagedIdentityCredential } = require("@azure/identity");

    const credential = new ManagedIdentityCredential("{clientId}");
    const token = await credential.getToken("https://management.azure.com/.default");
    ```

---

## Configuration

```yaml
floci-az:
  services:
    managed-identity:
      enabled: true
      system-assigned-scope: subscriptions/00000000-0000-0000-0000-000000000001
```

| Property | Env var | Default | Description |
|---|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_MANAGED_IDENTITY_ENABLED` | `true` | Enables the ARM provider and the IMDS endpoint |
| `system-assigned-scope` | `FLOCI_AZ_SERVICES_MANAGED_IDENTITY_SYSTEM_ASSIGNED_SCOPE` | `subscriptions/00000000-0000-0000-0000-000000000001` | ARM scope that seeds the system-assigned IMDS identity's `principalId`/`clientId`. Set it to your resource's scope (e.g. `subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Compute/virtualMachines/{vm}`) so IMDS tokens match `GET {scope}/.../identities/default` reads |

Token tenant, issuer, and lifetime follow the [Entra ID](entra.md) settings
(`floci-az.services.entra.*`).

---

## Intentional deviations

- **`clientSecretUrl` is synthetic** — real Azure returns a Key Vault-backed credential renewal
  URL for `identities/default`; the emulator returns a placeholder URL under the emulator base.
- **Any `api-version` is accepted** on both planes; real IMDS rejects unknown versions.
- **`isolationScope` is not modeled** on user-assigned identities.
- **Federated identity credentials are CRUD-only** — no token-exchange semantics.
- **Unknown identities return** `400 {"error":"invalid_request","error_description":"Identity not found"}`,
  matching real IMDS behavior for unassigned user identities.
- **The system-assigned IMDS identity is not tied to a caller resource** — on real Azure, IMDS
  runs on the resource and returns that resource's own identity. The emulator is not attached to
  a resource, so system-assigned tokens are seeded from the configured `system-assigned-scope`
  (default: the default subscription). `identities/default` reads for *other* scopes return
  different (deterministic) GUIDs; set `system-assigned-scope` to your resource's scope when
  your code compares an ARM-read `principalId` against the token's `oid`.
- **Identity state is in-memory only** — like the rest of the ARM control plane, identities and
  federated credentials do not persist across restarts, regardless of `storage.mode`.
- **Resource-group deletion does not cascade** — deleting a resource group leaves its identities
  behind (consistent with the other ARM resources in the emulator). Identities do appear in
  `GET .../resourceGroups/{rg}/resources`, so `prevent_deletion_if_contains_resources` works.
