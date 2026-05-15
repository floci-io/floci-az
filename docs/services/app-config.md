# App Configuration

Compatible with `azure-appconfiguration` SDKs (Java, Python, JavaScript, .NET).

## Features

- **Key-values** — set, get, delete, list with key/label filters
- **Labels** — independent values per (key, label) pair; list distinct labels
- **Feature flags** — first-class support via `.appconfig.featureflag/` prefix and `application/vnd.microsoft.appconfig.ff+json` content-type
- **Revisions** — full revision history on every write; queryable via `GET /revisions`
- **Locks** — lock/unlock individual key-values to prevent modification
- **Snapshots** — point-in-time frozen copies of filtered key-value sets; archive/recover lifecycle
- **ETags** — conditional reads (`If-None-Match`) and conditional writes/deletes (`If-Match`)
- **Composition types** — `key` (deduplicate by key) and `key_label` (keep all key+label pairs)

## Endpoint

```
http://localhost:4577/{accountName}-appconfig
```

Default account: `devstoreaccount1`
Default endpoint: `http://localhost:4577/devstoreaccount1-appconfig`

## Connection String

The App Configuration SDK expects an HTTPS endpoint. Use the forced-HTTP pattern below for local development:

=== "Java"

    ```java
    import com.azure.data.appconfiguration.ConfigurationClientBuilder;
    import com.azure.core.http.policy.HttpPipelinePolicy;
    import com.azure.core.http.HttpPipelineCallContext;
    import com.azure.core.http.HttpPipelineNextPolicy;
    import com.azure.core.http.HttpResponse;
    import com.azure.core.util.Context;
    import reactor.core.publisher.Mono;
    import java.net.URL;

    // Rewrite https → http for local emulator
    static class ForceHttpPolicy implements HttpPipelinePolicy {
        @Override
        public Mono<HttpResponse> process(HttpPipelineCallContext ctx, HttpPipelineNextPolicy next) {
            try {
                URL url = new URL(ctx.getHttpRequest().getUrl().toString());
                ctx.getHttpRequest().setUrl(new URL("http", url.getHost(), url.getPort(), url.getFile()).toString());
            } catch (Exception ignored) {}
            return next.process();
        }
    }

    String account  = "devstoreaccount1";
    String endpoint = "https://localhost:4577/" + account + "-appconfig";
    String connStr  = "Endpoint=" + endpoint + ";Id=" + account + ";Secret=placeholder";

    ConfigurationClient client = new ConfigurationClientBuilder()
            .connectionString(connStr)
            .addPolicy(new ForceHttpPolicy())
            .buildClient();
    ```

=== "Python"

    ```python
    from azure.appconfiguration import AzureAppConfigurationClient
    from azure.core.pipeline.transport import RequestsTransport

    class ForceHttpTransport(RequestsTransport):
        def send(self, request, **kwargs):
            request.url = request.url.replace("https://", "http://", 1)
            return super().send(request, **kwargs)

    account  = "devstoreaccount1"
    endpoint = f"https://localhost:4577/{account}-appconfig"
    conn_str = f"Endpoint={endpoint};Id={account};Secret=placeholder"

    client = AzureAppConfigurationClient.from_connection_string(
        conn_str, transport=ForceHttpTransport()
    )
    ```

=== "JavaScript / TypeScript"

    ```typescript
    import { AppConfigurationClient } from "@azure/app-configuration";

    const account  = "devstoreaccount1";
    const endpoint = `https://localhost:4577/${account}-appconfig`;
    const connStr  = `Endpoint=${endpoint};Id=${account};Secret=placeholder`;

    const client = new AppConfigurationClient(connStr);
    ```

---

## Key-Values

### Set a key-value

=== "Java"

    ```java
    client.setConfigurationSetting("my-key", null, "my-value");
    ```

=== "Python"

    ```python
    from azure.appconfiguration import ConfigurationSetting
    client.set_configuration_setting(ConfigurationSetting(key="my-key", value="my-value"))
    ```

### Get a key-value

=== "Java"

    ```java
    ConfigurationSetting setting = client.getConfigurationSetting("my-key", null);
    System.out.println(setting.getValue()); // "my-value"
    ```

=== "Python"

    ```python
    setting = client.get_configuration_setting("my-key")
    print(setting.value)  # "my-value"
    ```

### Delete a key-value

=== "Java"

    ```java
    client.deleteConfigurationSetting("my-key", null);
    ```

=== "Python"

    ```python
    client.delete_configuration_setting("my-key")
    ```

### List key-values

=== "Java"

    ```java
    client.listConfigurationSettings(new SettingSelector().setKeyFilter("app/*"))
          .forEach(s -> System.out.println(s.getKey() + " = " + s.getValue()));
    ```

=== "Python"

    ```python
    for s in client.list_configuration_settings(key_filter="app/*"):
        print(f"{s.key} = {s.value}")
    ```

---

## Labels

Labels let you maintain environment-specific variants of the same key:

=== "Java"

    ```java
    client.setConfigurationSetting(
        new ConfigurationSetting().setKey("timeout").setValue("30").setLabel("prod"));
    client.setConfigurationSetting(
        new ConfigurationSetting().setKey("timeout").setValue("5").setLabel("dev"));

    // Fetch by label
    String prodVal = client.getConfigurationSetting("timeout", "prod").getValue(); // "30"
    String devVal  = client.getConfigurationSetting("timeout", "dev").getValue();  // "5"
    ```

=== "Python"

    ```python
    client.set_configuration_setting(ConfigurationSetting(key="timeout", value="30", label="prod"))
    client.set_configuration_setting(ConfigurationSetting(key="timeout", value="5",  label="dev"))

    prod = client.get_configuration_setting("timeout", label_filter="prod").value  # "30"
    dev  = client.get_configuration_setting("timeout", label_filter="dev").value   # "5"
    ```

---

## Feature Flags

=== "Java"

    ```java
    // Enable a flag
    client.setConfigurationSetting(new FeatureFlagConfigurationSetting("dark-mode", true));

    // Check the flag
    ConfigurationSetting s = client.getConfigurationSetting(
        ".appconfig.featureflag/dark-mode", null);
    boolean enabled = ((FeatureFlagConfigurationSetting) s).isEnabled();
    ```

=== "Python"

    ```python
    from azure.appconfiguration import FeatureFlagConfigurationSetting

    client.set_configuration_setting(FeatureFlagConfigurationSetting("dark-mode", enabled=True))

    flag = client.get_configuration_setting(".appconfig.featureflag/dark-mode")
    print(flag.enabled)  # True
    ```

---

## Snapshots

Snapshots capture a frozen, point-in-time copy of filtered key-values. They are immutable after creation — changes to live key-values do not affect an existing snapshot.

### Create a snapshot

=== "Java"

    ```java
    ConfigurationSnapshot snapshot = new ConfigurationSnapshot(
        List.of(new ConfigurationSettingsFilter("app/*").setLabel("prod"))
    );
    ConfigurationSnapshot created = client
        .beginCreateSnapshot("release-1.0", snapshot, null)
        .getFinalResult();
    ```

=== "Python"

    ```python
    from azure.appconfiguration import ConfigurationSettingsFilter

    created = client.begin_create_snapshot(
        name="release-1.0",
        filters=[ConfigurationSettingsFilter(key="app/*", label="prod")],
    ).result()
    ```

### Read from a snapshot

=== "Java"

    ```java
    client.listConfigurationSettingsForSnapshot("release-1.0")
          .forEach(s -> System.out.println(s.getKey() + " = " + s.getValue()));
    ```

=== "Python"

    ```python
    for s in client.list_configuration_settings(snapshot_name="release-1.0"):
        print(f"{s.key} = {s.value}")
    ```

### Archive and recover

=== "Java"

    ```java
    client.archiveSnapshot("release-1.0");   // status → archived
    client.recoverSnapshot("release-1.0");   // status → ready
    ```

=== "Python"

    ```python
    client.archive_snapshot("release-1.0")
    client.recover_snapshot("release-1.0")
    ```

### Composition types

| Type | Behaviour |
|---|---|
| `key` (default) | One entry per key; last matching filter wins when keys overlap |
| `key_label` | One entry per (key, label) pair; no deduplication |

=== "Java"

    ```java
    new ConfigurationSnapshot(filters)
        .setSnapshotComposition(SnapshotComposition.KEY_LABEL);
    ```

=== "Python"

    ```python
    from azure.appconfiguration import SnapshotComposition

    client.begin_create_snapshot(
        name="all-envs",
        filters=[ConfigurationSettingsFilter(key="app/*")],
        composition_type=SnapshotComposition.KEY_LABEL,
    ).result()
    ```

---

## REST API Reference

All endpoints sit under `/{accountName}-appconfig/` with an `api-version` query parameter (e.g. `?api-version=2023-11-01`).

### Key-Values

| Method | Path | Description |
|---|---|---|
| `GET` / `HEAD` | `/kv` | List key-values (supports `key`, `label` filters) |
| `GET` / `HEAD` | `/kv/{key}` | Get a key-value |
| `PUT` | `/kv/{key}` | Set a key-value |
| `DELETE` | `/kv/{key}` | Delete a key-value |

### Keys & Labels

| Method | Path | Description |
|---|---|---|
| `GET` / `HEAD` | `/keys` | List distinct key names |
| `GET` / `HEAD` | `/labels` | List distinct labels |

### Revisions

| Method | Path | Description |
|---|---|---|
| `GET` / `HEAD` | `/revisions` | List revision history (supports `key`, `label` filters) |

### Locks

| Method | Path | Description |
|---|---|---|
| `PUT` | `/locks/{key}` | Lock a key-value (prevents modification) |
| `DELETE` | `/locks/{key}` | Unlock a key-value |

### Snapshots

| Method | Path | Description |
|---|---|---|
| `GET` / `HEAD` | `/snapshots` | List snapshots (supports `name` filter) |
| `GET` / `HEAD` | `/snapshots/{name}` | Get a snapshot |
| `PUT` | `/snapshots/{name}` | Create a snapshot |
| `PATCH` | `/snapshots/{name}` | Archive or recover a snapshot |
| `GET` | `/kv?snapshot={name}` | List frozen key-values from a snapshot |

### Operations (LRO polling)

| Method | Path | Description |
|---|---|---|
| `GET` | `/operations?snapshot={name}` | Poll snapshot creation status (always returns `Succeeded`) |

---

## Conditional Operations (ETags)

Every key-value response includes an `ETag` header. Use `If-Match` for optimistic concurrency:

```bash
# Conditional update — only succeeds if ETag matches
curl -X PUT "http://localhost:4577/devstoreaccount1-appconfig/kv/my-key" \
  -H "If-Match: \"<etag>\"" \
  -H "Content-Type: application/json" \
  -d '{"value": "new-value"}'
```

A mismatch returns **412 Precondition Failed**. A locked key returns **423 Locked**.

---

## Storage Configuration

```yaml
floci-az:
  storage:
    services:
      app-config:
        # mode: persistent   # override global storage mode
        flush-interval-ms: 5000

  services:
    app-config:
      enabled: true
```

| Environment Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_APP_CONFIG_ENABLED` | `true` | Enable/disable the service |
| `FLOCI_AZ_STORAGE_SERVICES_APP_CONFIG_MODE` | _(global)_ | Per-service storage mode |
