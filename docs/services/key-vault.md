# Key Vault

Compatible with the `azure-keyvault-secrets` and `azure-keyvault-keys` SDKs (Python, Java, JavaScript, .NET).

## Features

### Secrets

- **Secrets CRUD** — set, get, delete, list secrets
- **Versioning** — each `set_secret` creates a new immutable version; latest pointer tracks the most recent
- **Soft-delete lifecycle** — delete moves a secret to the deleted namespace; recover or purge it
- **Properties update** — update `content_type`, `tags`, `enabled`, `nbf`, `exp` without changing the value
- **Attributes** — `enabled`, `not_before`, `expires_on`; disabled secrets return 403 on get
- **Optional timestamps** — unset `nbf` and `exp` attributes are omitted from responses; supplied values remain numeric Unix timestamps
- **List operations** — list active secrets, deleted secrets, or versions of a specific secret
- **Optional trailing slash** — fixed routes (`/secrets`, `/deletedsecrets`, `/certificates/contacts`) accept a trailing slash, including .NET `AddAzureKeyVault` configuration loading
- **Backup** — backup a secret (base64-encoded blob)
- **32-char hex version IDs** — matches Azure's version ID format

### Keys & Cryptography

- **Keys CRUD** — create/import RSA (`RSA`, `RSA-HSM`), EC (`EC`, `EC-HSM`, P-256/P-384/P-521), and
  oct (`oct`, `oct-HSM`) keys; get/list/list-versions; PATCH attributes
- **Soft-delete lifecycle** — delete → `deletedkeys` namespace → recover or purge
- **Backup/restore** — `POST /keys/{name}/backup` and `POST /keys/restore` (see deviations below)
- **Rotation** — `POST /keys/{name}/rotate` and rotation-policy management (`/keys/{name}/rotationpolicy`)
- **Crypto ops** — `encrypt`/`decrypt` (RSA-OAEP, RSA-OAEP-256, RSA1_5; AES-GCM A128/A192/A256),
  `sign`/`verify` (RS256/384/512, PS256/384/512, ES256/384/512), `wrapkey`/`unwrapkey`
- **`/rng`** — random bytes for client-side key material (see deviations)
- **Managed HSM** — the same data plane served under the `/{account}-managedhsm/` suffix

## Endpoint

```
http://localhost:4577/{accountName}-keyvault
```

Default account: `devstoreaccount1`
Default endpoint: `http://localhost:4577/devstoreaccount1-keyvault`

## Connection String

The Key Vault SDK enforces HTTPS and uses a challenge-based auth flow. Use the patterns below to connect to the local emulator:

=== "Python"

    ```python
    import re, time
    from azure.core.credentials import AccessToken, TokenCredential
    from azure.core.pipeline.transport import RequestsTransport
    from azure.keyvault.secrets import SecretClient

    class FakeCredential(TokenCredential):
        def get_token(self, *scopes, **kwargs):
            return AccessToken("fake-token", int(time.time()) + 3600)

    class ForceHttpTransport(RequestsTransport):
        def send(self, request, **kwargs):
            request.url = request.url.replace("https://", "http://", 1)
            return super().send(request, **kwargs)

    account   = "devstoreaccount1"
    endpoint  = "http://localhost:4577"
    vault_url = re.sub(r"^http://", "https://", endpoint) + f"/{account}-keyvault"

    client = SecretClient(
        vault_url=vault_url,
        credential=FakeCredential(),
        transport=ForceHttpTransport(),
        verify_challenge_resource=False,
    )
    ```

=== "Java"

    ```java
    import com.azure.core.credential.AccessToken;
    import com.azure.core.credential.TokenCredential;
    import com.azure.core.http.HttpPipelineCallContext;
    import com.azure.core.http.HttpPipelineNextPolicy;
    import com.azure.core.http.HttpResponse;
    import com.azure.core.http.policy.HttpPipelinePolicy;
    import com.azure.security.keyvault.secrets.SecretClient;
    import com.azure.security.keyvault.secrets.SecretClientBuilder;
    import reactor.core.publisher.Mono;
    import java.net.URL;
    import java.time.OffsetDateTime;

    static class FakeCredential implements TokenCredential {
        @Override
        public Mono<AccessToken> getToken(TokenRequestContext req) {
            return Mono.just(new AccessToken("fake-token", OffsetDateTime.now().plusHours(1)));
        }
    }

    static class ForceHttpPolicy implements HttpPipelinePolicy {
        @Override
        public Mono<HttpResponse> process(HttpPipelineCallContext ctx, HttpPipelineNextPolicy next) {
            try {
                URL url = new URL(ctx.getHttpRequest().getUrl().toString());
                ctx.getHttpRequest().setUrl(
                    new URL("http", url.getHost(), url.getPort(), url.getFile()).toString());
            } catch (Exception ignored) {}
            return next.process();
        }
    }

    String account  = "devstoreaccount1";
    String vaultUrl = "https://localhost:4577/" + account + "-keyvault";

    SecretClient client = new SecretClientBuilder()
            .vaultUrl(vaultUrl)
            .credential(new FakeCredential())
            .addPolicy(new ForceHttpPolicy())
            .disableChallengeResourceVerification()
            .buildClient();
    ```

---

## Secrets

### Set a secret

=== "Python"

    ```python
    secret = client.set_secret("my-secret", "my-value")
    print(secret.value)           # "my-value"
    print(secret.properties.version)  # 32-char hex version ID
    ```

=== "Java"

    ```java
    KeyVaultSecret secret = client.setSecret("my-secret", "my-value");
    System.out.println(secret.getValue());                   // "my-value"
    System.out.println(secret.getProperties().getVersion()); // 32-char hex
    ```

### Get a secret

=== "Python"

    ```python
    secret = client.get_secret("my-secret")          # latest version
    secret = client.get_secret("my-secret", version) # specific version
    ```

=== "Java"

    ```java
    KeyVaultSecret secret = client.getSecret("my-secret");           // latest
    KeyVaultSecret secret = client.getSecret("my-secret", version);  // specific version
    ```

### Delete a secret (soft-delete)

=== "Python"

    ```python
    poller = client.begin_delete_secret("my-secret")
    deleted = poller.result()
    print(deleted.deleted_date)          # when it was deleted
    print(deleted.scheduled_purge_date)  # after 7 days
    ```

### Recover a deleted secret

=== "Python"

    ```python
    poller = client.begin_recover_deleted_secret("my-secret")
    recovered = poller.result()
    secret = client.get_secret("my-secret")  # back to normal
    ```

### Purge a deleted secret

=== "Python"

    ```python
    client.purge_deleted_secret("my-secret")  # permanently gone
    ```

---

## Versioning

Every `set_secret` call creates a new immutable version. The latest version is always returned by `get_secret(name)`.

=== "Python"

    ```python
    v1 = client.set_secret("db-password", "hunter2")
    v2 = client.set_secret("db-password", "correct-horse-battery-staple")

    # Latest is always v2
    latest = client.get_secret("db-password")
    assert latest.value == "correct-horse-battery-staple"

    # Access v1 by version ID
    old = client.get_secret("db-password", v1.properties.version)
    assert old.value == "hunter2"

    # List all versions
    for props in client.list_properties_of_secret_versions("db-password"):
        print(props.version, props.created_on)
    ```

---

## Properties Update

Update metadata without creating a new version:

=== "Python"

    ```python
    s = client.set_secret("api-key", "abc123", content_type="text/plain")

    client.update_secret_properties(
        "api-key",
        s.properties.version,
        content_type="application/json",
        tags={"env": "prod"},
        enabled=False,
    )
    ```

---

## REST API Reference

All endpoints sit under `/{accountName}-keyvault/` with an `api-version` query parameter.

### Secrets

| Method | Path | Description |
|---|---|---|
| `GET` | `/secrets` | List all secrets (properties only, no values) |
| `GET` | `/secrets/{name}` | Get latest version |
| `PUT` | `/secrets/{name}` | Set a secret (creates new version) |
| `DELETE` | `/secrets/{name}` | Soft-delete a secret |
| `GET` | `/secrets/{name}/{version}` | Get a specific version |
| `PATCH` | `/secrets/{name}/{version}` | Update secret properties |
| `GET` | `/secrets/{name}/versions` | List all versions |
| `POST` | `/secrets/{name}/backup` | Backup a secret |

### Deleted Secrets

| Method | Path | Description |
|---|---|---|
| `GET` | `/deletedsecrets` | List deleted secrets |
| `GET` | `/deletedsecrets/{name}` | Get a deleted secret |
| `DELETE` | `/deletedsecrets/{name}` | Purge (permanently delete) |
| `POST` | `/deletedsecrets/{name}/recover` | Recover a deleted secret |

### Keys

| Method | Path | Description |
|---|---|---|
| `POST` | `/keys/{name}/create` | Create a key (`kty`, `key_size`, `curve`, `key_ops`) |
| `PUT` | `/keys/{name}` | Import a key (`key` JWK) |
| `GET` | `/keys` | List keys |
| `GET` | `/keys/{name}` | Get latest version |
| `GET` | `/keys/{name}/{version}` | Get a specific version |
| `PATCH` | `/keys/{name}/{version}` | Update key attributes (`enabled`, `nbf`, `exp`, `tags`) |
| `DELETE` | `/keys/{name}` | Soft-delete a key |
| `GET` | `/keys/{name}/versions` | List all versions |
| `POST` | `/keys/{name}/backup` | Backup a key |
| `POST` | `/keys/restore` | Restore a key from a backup blob |
| `POST` | `/keys/{name}/rotate` | Rotate (regenerate) a key |
| `GET`/`PUT` | `/keys/{name}/rotationpolicy` | Get/put the key rotation policy |

### Deleted Keys

| Method | Path | Description |
|---|---|---|
| `GET` | `/deletedkeys` | List deleted keys |
| `GET` | `/deletedkeys/{name}` | Get a deleted key |
| `DELETE` | `/deletedkeys/{name}` | Purge (permanently delete) |
| `POST` | `/deletedkeys/{name}/recover` | Recover a deleted key |

### Crypto Operations

| Method | Path | Description |
|---|---|---|
| `POST` | `/keys/{name}[/{version}]/encrypt` | Encrypt (`RSA-OAEP`, `RSA-OAEP-256`, `RSA1_5`, `A128GCM`, `A192GCM`, `A256GCM`) |
| `POST` | `/keys/{name}[/{version}]/decrypt` | Decrypt |
| `POST` | `/keys/{name}[/{version}]/sign` | Sign (`RS256/384/512`, `PS256/384/512`, `ES256/384/512`) |
| `POST` | `/keys/{name}[/{version}]/verify` | Verify a signature |
| `POST` | `/keys/{name}[/{version}]/wrapkey` | Wrap a key (`RSA-OAEP-256` for RSA keys) |
| `POST` | `/keys/{name}[/{version}]/unwrapkey` | Unwrap a key |
| `POST` | `/rng` | Random bytes (`{"count": 1..128}`) |

---

## Intentional deviations

These are deliberate differences from real Azure Key Vault. They are stable, documented behavior — not bugs:

- **Backup blobs are unencrypted plaintext.** Real Azure returns HSM-encrypted opaque blobs that can only be
  restored into the same vault. floci-az emits a readable JSON snapshot (JWK + metadata) so backups are
  portable and inspectable. Do not treat backup blobs as secrets.
- **`/rng` caps at 128 bytes per request.** This matches real Azure (which caps at 128). An earlier draft of the
  plan stated 1024; that was incorrect.
- **Key material is stored in the clear in the storage backend** (memory/persistent), not HSM-encrypted.
- **`key_size` may appear in the returned public JWK** even though it is not part of Azure's `JsonWebKey`
  schema. Azure SDKs tolerate unknown fields; kept for convenience.
- **`nbf`/`exp` must be numeric Unix epoch timestamps.** Whitespace is tolerated and normalized; any other
  form is rejected with `400 BadParameter` at create/import/PATCH/restore time.

---

## Storage Configuration

```yaml
floci-az:
  storage:
    services:
      key-vault:
        # mode: persistent   # override global storage mode
        flush-interval-ms: 5000

  services:
    key-vault:
      enabled: true
```

| Environment Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_KEY_VAULT_ENABLED` | `true` | Enable/disable the service |
| `FLOCI_AZ_STORAGE_SERVICES_KEY_VAULT_MODE` | _(global)_ | Per-service storage mode |
