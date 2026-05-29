#!/usr/bin/env bash
# Common setup for floci-az OpenTofu bats tests

TOFU_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Load bats helpers
if [[ -n "${BATS_LIB_PATH:-}" ]]; then
    load "${BATS_LIB_PATH}/bats-support/load"
    load "${BATS_LIB_PATH}/bats-assert/load"
else
    echo "Error: BATS_LIB_PATH not set" >&2
    exit 1
fi

export FLOCI_AZ_ENDPOINT="${FLOCI_AZ_ENDPOINT:-http://localhost:4577}"
export TF_VAR_subscription_id="${TF_VAR_subscription_id:-00000000-0000-0000-0000-000000000001}"
export TF_VAR_tenant_id="${TF_VAR_tenant_id:-00000000-0000-0000-0000-000000000002}"
# TF_VAR_metadata_host is set by run-bats-in-container.sh; default for local runs
export TF_VAR_metadata_host="${TF_VAR_metadata_host:-localhost:4577}"

# Resource names that match main.tf
export SA_NAME="flocitestsa"
export RG_NAME="floci-test-rg"
export KV_NAME="floci-test-kv"
export CONTAINER_NAME="floci-test-container"
export QUEUE_NAME="floci-test-queue"
export SECRET_NAME="floci-test-secret"
export SUB_ID="${TF_VAR_subscription_id}"

arm_get() {
    curl -sf -H "Authorization: Bearer fake" \
        "${FLOCI_AZ_ENDPOINT}/$1"
}

kv_get() {
    # azurerm v3 provider sends key vault data-plane to the ARM base URL when
    # metadata/endpoints returns 404. Read from the same path so the account
    # namespace matches what the provider wrote.
    curl -sf -H "Authorization: Bearer fake" \
        "${FLOCI_AZ_ENDPOINT}/$1?api-version=7.4"
}
