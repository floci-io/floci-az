#!/usr/bin/env bash
# Common setup for floci-az Azure CLI bats tests.

if [[ -n "${BATS_LIB_PATH:-}" ]]; then
    load "${BATS_LIB_PATH}/bats-support/load"
    load "${BATS_LIB_PATH}/bats-assert/load"
else
    echo "Error: BATS_LIB_PATH not set" >&2
    exit 1
fi

export FLOCI_AZ_ENDPOINT="${FLOCI_AZ_ENDPOINT:-http://localhost:4577}"
export SUB_ID="${AZURE_SUBSCRIPTION_ID:-00000000-0000-0000-0000-000000000001}"
export TENANT_ID="${AZURE_TENANT_ID:-00000000-0000-0000-0000-000000000002}"
export CLIENT_ID="${AZURE_CLIENT_ID:-00000000-0000-0000-0000-000000000003}"

export LOCATION="eastus"

# Resource names — match the terraform suite where it helps cross-checking.
export RG_NAME="floci-test-rg"
export SA_NAME="flocitestsa"
export CONTAINER_NAME="floci-test-container"
export BLOB_NAME="hello.txt"
export KV_NAME="floci-test-kv"
export SECRET_NAME="floci-test-secret"
export VNET_NAME="floci-test-vnet"
export SUBNET_NAME="floci-test-subnet"
export NIC_NAME="floci-test-nic"
export VM_NAME="floci-test-vm"
export ACR_NAME="flocitestacr"
export REDIS_NAME="floci-test-redis"

# Dev storage key (well-known Azurite key floci-az accepts) — used to build a
# data-plane connection string, mirroring azfloci/azfloci.py.
export DEV_STORAGE_KEY="Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0=="

# Run an az command and emit JSON on stdout only — stderr (warnings/InsecureRequestWarning)
# is dropped so bats `run` doesn't merge it into $output and break jq parsing.
az_json() {
    az "$@" -o json 2>/dev/null
}

# Build a storage connection string pointing the data-plane endpoints at floci-az.
storage_conn() {
    local account="${1:-$SA_NAME}"
    echo "DefaultEndpointsProtocol=http;AccountName=${account};AccountKey=${DEV_STORAGE_KEY};BlobEndpoint=${FLOCI_AZ_ENDPOINT}/${account};QueueEndpoint=${FLOCI_AZ_ENDPOINT}/${account}-queue;TableEndpoint=${FLOCI_AZ_ENDPOINT}/${account}-table;"
}
