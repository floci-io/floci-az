# Azure CLI & SDK Setup

## azfloci CLI Wrapper

The `azfloci` tool is a companion Python CLI that acts as a transparent proxy for the official Azure CLI (`az`).

### Setup

```bash
# Optional: alias azfloci as az for a seamless experience
alias az='python3 /path/to/floci-az/azfloci/azfloci.py'

# Initialize or get connection string info
az setup
```

## Azure CLI

If you prefer using the standard `az` CLI without the wrapper, you must provide the connection string for each command:

```bash
az storage container create --name mycontainer --connection-string "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==;BlobEndpoint=http://localhost:4577/devstoreaccount1;"
```

## SDKs

Floci-AZ is compatible with official Azure SDKs. Use the standard development connection string:

```
DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==;BlobEndpoint=http://localhost:4577/devstoreaccount1;QueueEndpoint=http://localhost:4577/devstoreaccount1-queue;TableEndpoint=http://localhost:4577/devstoreaccount1-table;
```

### Path-style Routing

Floci-AZ uses path-style routing:

| Service | Endpoint |
|---|---|
| Blob | `http://localhost:4577/{accountName}` |
| Queue | `http://localhost:4577/{accountName}-queue` |
| Table | `http://localhost:4577/{accountName}-table` |
| Functions | `http://localhost:4577/{accountName}-functions` |
| App Configuration | `http://localhost:4577/{accountName}-appconfig` |

### App Configuration

The App Configuration SDK requires an HTTPS endpoint in its connection string. Use a `ForceHttp` transport wrapper to redirect traffic to the local emulator:

=== "Python"

    ```python
    from azure.appconfiguration import AzureAppConfigurationClient
    from azure.core.pipeline.transport import RequestsTransport

    class ForceHttpTransport(RequestsTransport):
        def send(self, request, **kwargs):
            request.url = request.url.replace("https://", "http://", 1)
            return super().send(request, **kwargs)

    conn_str = "Endpoint=https://localhost:4577/devstoreaccount1-appconfig;Id=devstoreaccount1;Secret=placeholder"
    client = AzureAppConfigurationClient.from_connection_string(conn_str, transport=ForceHttpTransport())
    ```

=== "Java"

    ```java
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

    String connStr = "Endpoint=https://localhost:4577/devstoreaccount1-appconfig;Id=devstoreaccount1;Secret=placeholder";
    ConfigurationClient client = new ConfigurationClientBuilder()
            .connectionString(connStr)
            .addPolicy(new ForceHttpPolicy())
            .buildClient();
    ```

See the [App Configuration service page](../services/app-config.md) for full SDK examples.
