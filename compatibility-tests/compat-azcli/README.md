# compat-azcli

Compatibility tests for [Floci AZ](https://github.com/floci-io/floci-az) using the **Azure CLI (`az`)**.

Tests are [bats](https://github.com/bats-core/bats-core) scripts that call `az` commands. Unlike the AWS CLI, `az` has no universal `--endpoint-url`, so the container registers a custom `az cloud` pointing at the emulator and runs `az login --service-principal` (exercising Floci AZ's Entra token endpoint) before the tests.

## Services Covered

| Group               | Description                                          |
| ------------------- | ---------------------------------------------------- |
| `login`             | Custom cloud, service-principal login, access token  |
| `group-storage`     | Resource group, storage account, blob data-plane     |
| `keyvault`          | Vault create, secret set/show data-plane             |
| `network-vm`        | Virtual network, NIC (VM create best-effort)         |
| `acr-redis`         | Container Registry, Redis cache                      |

## Requirements

- Azure CLI (`az`) — provided by the `mcr.microsoft.com/azure-cli` base image
- bash
- jq
- bats-core (cloned into the image at build time)

## Running

```bash
# Build the emulator + run this suite (from repo root)
make test-azcli

# As part of the full compatibility matrix
make compat-docker
```

## Configuration

| Variable                  | Default                                  | Description                       |
| ------------------------- | ---------------------------------------- | --------------------------------- |
| `FLOCI_AZ_ENDPOINT`       | `http://localhost:4577`                  | Floci AZ emulator endpoint        |
| `AZURE_SUBSCRIPTION_ID`   | `00000000-0000-0000-0000-000000000001`   | Dev subscription                  |
| `AZURE_TENANT_ID`         | `00000000-0000-0000-0000-000000000002`   | Dev tenant                        |
| `AZURE_CLIENT_ID`         | `00000000-0000-0000-0000-000000000003`   | Dev service principal             |
| `AZURE_CLIENT_SECRET`     | `fake-secret`                            | Dev service principal secret      |

`az vm create` is skipped gracefully where the emulator does not yet resolve marketplace image versions.

## Docker

```bash
docker build -t compat-azcli .
docker run --rm --network host -v "$PWD/results:/results" compat-azcli

# Custom endpoint (macOS/Windows, or a named emulator container)
docker run --rm -e FLOCI_AZ_ENDPOINT=http://floci-az:4577 compat-azcli
```
