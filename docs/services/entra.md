# Microsoft Entra ID

A local **OpenID Connect provider** that issues real **RS256-signed** JWTs with a published
discovery document and JWKS. This replaces the previous static, unsigned-token stub so apps that
*acquire* and *validate* Entra tokens can work fully offline.

> **Phase 1.** This release delivers the OIDC foundation and the two non-interactive grants
> (**client credentials** and **resource-owner password / ROPC**). App-registration management,
> Microsoft Graph CRUD, and the interactive flows (device code, auth code + PKCE) follow in
> later phases ([#23](https://github.com/floci-io/floci-az/issues/23)).

## Features

- **Signed tokens** — RS256 JWTs with a stable signing key persisted across restarts; app-only
  tokens carry `idtyp=app`, and every token carries a unique `uti`, matching real Entra
- **Discovery** — `/.well-known/openid-configuration` derived from the request base URL
- **JWKS** — `/discovery/v2.0/keys` exposing the public signing key (`kty`, `use`, `alg`, `kid`,
  `n`, `e`) plus the self-signed cert chain (`x5c`, `x5t`)
- **Grants** — `client_credentials` and `password` (ROPC), v1.0 and v2.0 token shapes
- **Azure-shaped errors** — token errors return `error`, `error_description` (with the `AADSTS`
  code), `error_codes`, `trace_id`, `correlation_id`, `timestamp`, and `error_uri`
- **Dev seed** — a default tenant and a well-known dev app registration, so
  `ClientSecretCredential` works with zero setup

## Endpoints

All endpoints are tenant-rooted at the base URL (port `4577`). `{tenant}` may be a tenant id or
`common` / `organizations` / `consumers`.

| Path | Purpose |
|---|---|
| `POST /{tenant}/oauth2/v2.0/token` | Token endpoint (v2.0) |
| `POST /{tenant}/oauth2/token` | Token endpoint (v1.0) |
| `GET /{tenant}/v2.0/.well-known/openid-configuration` | OpenID discovery |
| `GET /{tenant}/.well-known/openid-configuration` | OpenID discovery |
| `GET /{tenant}/discovery/v2.0/keys` | JWKS |

## Default tenant & dev credentials

| Value | Default |
|---|---|
| Tenant id | `00000000-0000-0000-0000-000000000002` |
| Client id | `11111111-1111-1111-1111-111111111111` |
| Client secret | `floci-az-dev-secret` |

## Acquiring a token

=== "Python"

    ```python
    from azure.identity import ClientSecretCredential

    cred = ClientSecretCredential(
        tenant_id="00000000-0000-0000-0000-000000000002",
        client_id="11111111-1111-1111-1111-111111111111",
        client_secret="floci-az-dev-secret",
        authority="http://localhost:4577",
    )
    token = cred.get_token("api://resource/.default")
    print(token.token)  # RS256-signed JWT
    ```

=== "curl"

    ```bash
    curl -s http://localhost:4577/00000000-0000-0000-0000-000000000002/oauth2/v2.0/token \
      -d grant_type=client_credentials \
      -d client_id=11111111-1111-1111-1111-111111111111 \
      -d client_secret=floci-az-dev-secret \
      -d scope=api://resource/.default
    ```

## Validating a token

Fetch the JWKS and validate the signature, `iss`, `aud`, and `exp`:

```python
import jwt
from jwt import PyJWKClient

jwks = PyJWKClient("http://localhost:4577/00000000-0000-0000-0000-000000000002/discovery/v2.0/keys")
signing_key = jwks.get_signing_key_from_jwt(token)
claims = jwt.decode(token, signing_key.key, algorithms=["RS256"], audience="api://resource")
```

## Configuration

```yaml
floci-az:
  services:
    entra:
      enabled: true                 # local OIDC provider (default on)
      default-tenant-id: "00000000-0000-0000-0000-000000000002"
      # issuer:                     # optional override; default {baseUrl}/{tenant}/v2.0
      token-lifetime-seconds: 3599
      validate-tokens: false        # true = enforce signature/claims on incoming Bearer tokens
      # signing-key-path:           # optional; default {storage.persistent-path}/entra
```

| Setting | Env var | Default |
|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_ENTRA_ENABLED` | `true` |
| `default-tenant-id` | `FLOCI_AZ_SERVICES_ENTRA_DEFAULT_TENANT_ID` | `00000000-0000-0000-0000-000000000002` |
| `issuer` | `FLOCI_AZ_SERVICES_ENTRA_ISSUER` | _(derived from request)_ |
| `token-lifetime-seconds` | `FLOCI_AZ_SERVICES_ENTRA_TOKEN_LIFETIME_SECONDS` | `3599` |
| `validate-tokens` | `FLOCI_AZ_SERVICES_ENTRA_VALIDATE_TOKENS` | `false` |
| `signing-key-path` | `FLOCI_AZ_SERVICES_ENTRA_SIGNING_KEY_PATH` | `{storage.persistent-path}/entra` |

> `validate-tokens` stays **off** by default so existing services keep accepting any Bearer
> token in dev. Token *enforcement* against the local signing key becomes opt-in in a later phase.

> **Keep `enabled: true` unless you have a reason not to.** The OAuth2 token endpoint
> (`/{tenant}/oauth2/v2.0/token`) is served by this service. Disabling Entra makes that endpoint
> `404`, which breaks the ARM/Terraform and SDK sign-in handshakes that authenticate through it.
