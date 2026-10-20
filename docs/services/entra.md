# Microsoft Entra ID

A local **OpenID Connect provider** that issues real **RS256-signed** JWTs with a published
discovery document and JWKS. This replaces the previous static, unsigned-token stub so apps that
*acquire* and *validate* Entra tokens can work fully offline.

> **Phase 1** delivered the OIDC foundation and the two non-interactive grants (**client
> credentials** and **resource-owner password / ROPC**). **Phase 2**
> ([#120](https://github.com/floci-io/floci-az/issues/120)) adds the **authorization code + PKCE**
> grant for interactive (browser SPA) sign-in, plus a narrow Microsoft Graph slice for group
> membership: see [`services/graph.md`](graph.md). App-registration management and full Graph
> CRUD are not implemented yet.

## Features

- **Signed tokens**: RS256 JWTs with a stable signing key persisted across restarts; app-only
  tokens carry `idtyp=app`, and every token carries a unique `uti`, matching real Entra
- **Discovery**: `/.well-known/openid-configuration` derived from the request base URL. It
  advertises only what the emulator serves, see [Discovery document](#discovery-document)
- **JWKS**: `/discovery/v2.0/keys` exposing the public signing key (`kty`, `use`, `alg`, `kid`,
  `n`, `e`) plus the self-signed cert chain (`x5c`, `x5t`)
- **Grants**: `client_credentials`, `password` (ROPC), and `authorization_code` (auth code + PKCE),
  v1.0 and v2.0 token shapes
- **Azure-shaped errors**: token errors return `error`, `error_description` (with the `AADSTS`
  code), `error_codes`, `trace_id`, `correlation_id`, `timestamp`, and `error_uri`
- **Dev seed**: a default tenant, a well-known dev app registration, a dev user and a dev group
- **Permissive client authentication by default**: client secrets are not checked, so any
  `client_id` and `client_secret` get a token and `ClientSecretCredential` works with zero setup.
  Set `validate-client-credentials: true` to check them against the directory (see
  [Client credential validation](#client-credential-validation))

## Endpoints

All endpoints are tenant-rooted at the base URL (port `4577`). `{tenant}` may be a tenant id or
`common` / `organizations` / `consumers`.

| Path | Purpose |
|---|---|
| `GET /{tenant}/oauth2/v2.0/authorize` | Authorize endpoint (auth code + PKCE) |
| `POST /{tenant}/oauth2/v2.0/token` | Token endpoint (v2.0) |
| `POST /{tenant}/oauth2/token` | Token endpoint (v1.0) |
| `GET /{tenant}/v2.0/.well-known/openid-configuration` | OpenID discovery |
| `GET /{tenant}/.well-known/openid-configuration` | OpenID discovery |
| `GET /{tenant}/discovery/v2.0/keys` | JWKS |
| `GET /common/userrealm/{username}?api-version=1.0` | Home realm discovery: always `account_type: Managed`, so MSAL username-password sign-in goes straight to the password grant |

## Discovery document

`GET /{tenant}/v2.0/.well-known/openid-configuration` lists only the grants, endpoints and
response types the emulator implements, so a client that configures itself from discovery fails
early with a clear message instead of calling a route that does not exist:

| Field | Value |
|---|---|
| `issuer` | `{base}/{tenant}/v2.0`, echoing the tenant segment as requested (`common` stays `common`) |
| `authorization_endpoint`, `token_endpoint`, `jwks_uri` | the routes in the table above |
| `grant_types_supported` | `authorization_code`, `client_credentials`, `password` |
| `response_types_supported` | `code` |
| `response_modes_supported` | `query`, `fragment` |
| `scopes_supported` | `openid`, `profile`, `email` |
| `end_session_endpoint` | `{base}/{tenant}/oauth2/v2.0/logout` |

There is no `device_authorization_endpoint` or `userinfo_endpoint`, and `refresh_token`,
the device code grant and the `offline_access` scope are not listed, because none of them is
implemented yet. msal-python's `initiate_device_flow` therefore stops with "You need to provide
device authorization endpoint" before sending any request. `end_session_endpoint` is listed even though logout is not served yet, because
MSAL.js refuses to log out without it.

## Default tenant & dev credentials

| Value | Default |
|---|---|
| Tenant id | `00000000-0000-0000-0000-000000000002` |
| Client id | `11111111-1111-1111-1111-111111111111` |
| Client secret | `floci-az-dev-secret` (checked only when `validate-client-credentials` is on) |
| Dev user (UPN) | `dev-user@floci-az.local` |
| Dev group | `floci-az dev group` (the dev user is a direct member) |

## Interactive sign-in (authorization code + PKCE)

There is no real interactive consent screen: `GET /{tenant}/oauth2/v2.0/authorize` auto-approves
against the seeded dev user (or a different seeded user, selected via `login_hint`) and redirects
straight back to `redirect_uri` with a `code`: no login form, nothing to click through. This keeps
the flow fully scriptable while still exercising the real auth-code+PKCE wire protocol that MSAL
(`@azure/msal-browser`, `@azure/msal-react`, `@azure/msal-node`) speaks.

```
GET /{tenant}/oauth2/v2.0/authorize
    ?client_id=11111111-1111-1111-1111-111111111111
    &redirect_uri=https://app.local/callback
    &response_type=code
    &response_mode=query          # or "fragment"
    &scope=openid profile
    &state=...
    &nonce=...
    &code_challenge=...           # PKCE S256 challenge
    &code_challenge_method=S256
    &login_hint=dev-user@floci-az.local   # optional; defaults to the seeded dev user
```

`302`s to `{redirect_uri}?code=...&state=...` (or `#code=...&state=...` for `response_mode=fragment`).
The issued code is bound to the tenant, `client_id`, and `redirect_uri` from this request: redemption
must repeat the same `client_id`/`redirect_uri` and happen against the same tenant, or it fails with
`invalid_grant`. Redeem the code with `grant_type=authorization_code`:

```bash
curl -s http://localhost:4577/00000000-0000-0000-0000-000000000002/oauth2/v2.0/token \
  -d grant_type=authorization_code \
  -d client_id=11111111-1111-1111-1111-111111111111 \
  -d redirect_uri=https://app.local/callback \
  -d code=<code from the redirect> \
  -d code_verifier=<the PKCE verifier for the challenge you sent>
```

The response carries both `access_token` and `id_token`. The ID token's `aud` is always the client
id (per OIDC, regardless of v1.0/v2.0), and it echoes the `nonce` from the `/authorize` request:
MSAL rejects an ID token whose nonce doesn't match. PKCE verification (`S256` or `plain`) is
skipped when `/authorize` was called without a `code_challenge`: `client_id` is still required and
bound to the code, so an omitted challenge no longer hands a usable code to a caller who doesn't
know the client. There is no app-registration redirect-URI allow-list yet,
so any `client_id` is accepted at `/authorize`, matching this phase's permissive client validation
elsewhere (`client_credentials` is likewise accepted without strict validation).

**Not yet supported:** `grant_type=refresh_token`: MSAL's silent token renewal via
`offline_access` will not work against this emulator yet.

**Group membership** for the signed-in user is *not* embedded in the token (no `groups` claim, matching
real Entra's default behavior): call the Graph `getMemberGroups` endpoint instead, see
[`services/graph.md`](graph.md).

## Acquiring a token

=== "Python"

    ```python
    from azure.identity import ClientSecretCredential

    cred = ClientSecretCredential(
        tenant_id="00000000-0000-0000-0000-000000000002",
        client_id="11111111-1111-1111-1111-111111111111",
        client_secret="floci-az-dev-secret",  # any value works unless validate-client-credentials is on
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

## Client credential validation

By default the token endpoint accepts any client: an unregistered `client_id` and a wrong secret
both get a token, and the first unregistered `client_id` is logged once at `WARN`. With
`validate-client-credentials: true` the client is checked against the directory instead:

| Request | Response |
|---|---|
| a `client_id` not registered in the tenant | `400` `unauthorized_client`, `AADSTS700016` |
| a secret that matches none of the app's secrets | `401` `invalid_client`, `AADSTS7000215` |
| `client_credentials` with neither a secret nor a `client_assertion` | `401` `invalid_client`, `AADSTS7000218` |
| no `client_id` at all | `400` `invalid_request`, `AADSTS900144` |

The secret may be sent in the form (`client_secret_post`) or in an HTTP Basic header
(`client_secret_basic`). The `password` and `authorization_code` grants need no secret, only a
registered `client_id`. A `client_assertion` is accepted without verification, and the `password`
grant does not check the user's password. The seeded dev app (`11111111-...` with
`floci-az-dev-secret`) is registered, so it keeps working. Secrets are stored as salted
PBKDF2-HMAC-SHA256 hashes, never in plaintext.

Tenants: `common`, `organizations` and `consumers` act in the default tenant. Any other tenant id
is accepted as it is and served from the default tenant's directory, so the dev app authenticates
under it too.

## Configuration

```yaml
floci-az:
  services:
    entra:
      enabled: true                 # local OIDC provider (default on)
      default-tenant-id: "00000000-0000-0000-0000-000000000002"
      # issuer:                     # optional override; default {baseUrl}/{tenant}/v2.0
      token-lifetime-seconds: 3599
      validate-tokens: false        # reserved: not enforced yet, any Bearer token is accepted
      validate-client-credentials: false   # true: reject unregistered clients and wrong secrets
      # signing-key-path:           # optional; default {storage.persistent-path}/entra
```

| Setting | Env var | Default |
|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_ENTRA_ENABLED` | `true` |
| `default-tenant-id` | `FLOCI_AZ_SERVICES_ENTRA_DEFAULT_TENANT_ID` | `00000000-0000-0000-0000-000000000002` |
| `issuer` | `FLOCI_AZ_SERVICES_ENTRA_ISSUER` | _(derived from request)_ |
| `token-lifetime-seconds` | `FLOCI_AZ_SERVICES_ENTRA_TOKEN_LIFETIME_SECONDS` | `3599` |
| `validate-tokens` | `FLOCI_AZ_SERVICES_ENTRA_VALIDATE_TOKENS` | `false` |
| `validate-client-credentials` | `FLOCI_AZ_SERVICES_ENTRA_VALIDATE_CLIENT_CREDENTIALS` | `false` |
| `signing-key-path` | `FLOCI_AZ_SERVICES_ENTRA_SIGNING_KEY_PATH` | `{storage.persistent-path}/entra` |

> `validate-tokens` is reserved and currently has no effect: every service accepts any Bearer
> token whatever its value. Token *enforcement* against the local signing key becomes opt-in in a
> later phase.

> **Keep `enabled: true` unless you have a reason not to.** The OAuth2 token endpoint
> (`/{tenant}/oauth2/v2.0/token`) is served by this service. Disabling Entra makes that endpoint
> `404`, which breaks the ARM/Terraform and SDK sign-in handshakes that authenticate through it.
