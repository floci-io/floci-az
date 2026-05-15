# Quick Start

## Docker Compose

```yaml title="docker-compose.yml"
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - ./data:/app/data
      - /var/run/docker.sock:/var/run/docker.sock  # required for Azure Functions
```

```bash
docker compose up -d
```

All services are immediately available at `http://localhost:4577`.

!!! tip "Don't use Azure Functions?"
    Skip the Docker socket mount and set `FLOCI_AZ_SERVICES_FUNCTIONS_ENABLED=false` for a simpler setup.

## Docker Run

```bash
docker run -d --name floci-az \
  -p 4577:4577 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  floci/floci-az:latest
```

## Verify it's running

```bash
curl http://localhost:4577/health
```

## Connection strings

**Blob / Queue / Table:**

```
DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==;BlobEndpoint=http://localhost:4577/devstoreaccount1;QueueEndpoint=http://localhost:4577/devstoreaccount1-queue;TableEndpoint=http://localhost:4577/devstoreaccount1-table;
```

**App Configuration** — see [Azure CLI & SDK Setup](azure-setup.md) for the ForceHttp transport required by the App Config SDK.

## Next steps

- [Configure via environment variables →](../configuration/docker-compose.md)
- [Storage modes →](../configuration/storage.md)
- [Service reference →](../services/index.md)
