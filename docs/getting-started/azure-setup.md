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
