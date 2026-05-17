# Key Vault

Compatible with `azure-keyvault-secrets` SDKs (Python, Java, JavaScript, .NET).

## Features

- **Secrets CRUD** — set, get, delete, list secrets
- **Versioning** — each `set_secret` creates a new immutable version; latest pointer tracks the most recent
- **Soft-delete lifecycle** — delete moves a secret to the deleted namespace; recover or purge it
- **Properties update** — update `content_type`, `tags`, `enabled`, `nbf`, `exp` without changing the value
- **Attributes** — `enabled`, `not_before`, `expires_on`; disabled secrets return 403 on get
- **List operations** — list active secrets, deleted secrets, or versions of a specific secret
- **Backup** — backup a secret (base64-encoded blob)
- **32-char hex version IDs** — matches Azure's version ID format

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
