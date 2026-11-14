#!/usr/bin/env bash
# Common setup for floci-az azd bats tests.

if [[ -n "${BATS_LIB_PATH:-}" ]]; then
    load "${BATS_LIB_PATH}/bats-support/load"
    load "${BATS_LIB_PATH}/bats-assert/load"
else
    echo "Error: BATS_LIB_PATH not set" >&2
    exit 1
fi

export FLOCI_AZ_ENDPOINT="${FLOCI_AZ_ENDPOINT:-http://floci-az:4577}"
export SUB_ID="${AZURE_SUBSCRIPTION_ID}"
export TENANT_ID="${AZURE_TENANT_ID}"
export LOCATION="eastus"
export AZURE_LOCATION="${LOCATION}"
export ARM_CLIENT_ID="${AZURE_CLIENT_ID}"
export ARM_CLIENT_SECRET="${AZURE_CLIENT_SECRET}"
export ARM_TENANT_ID="${AZURE_TENANT_ID}"
export ARM_SUBSCRIPTION_ID="${AZURE_SUBSCRIPTION_ID}"

# A dev token for ARM calls the tests make themselves, read straight from floci-az.
arm_token() {
    azd auth token --output json | jq -r .token
}

arm_get() {
    curl -sS -H "Authorization: Bearer $(arm_token)" "https://management.azure.com/$1"
}

arm_status() {
    curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $(arm_token)" "https://management.azure.com/$1"
}

# A fresh project directory, copied from a fixture, with one environment selected.
new_project() {
    local fixture="$1"
    local env_name="$2"
    local dir="/tmp/projects/${fixture}"
    if [[ ! -d "${dir}" ]]; then
        mkdir -p /tmp/projects
        cp -r "/app/fixtures/${fixture}" "${dir}"
    fi
    cd "${dir}"
    azd env list --output json 2>/dev/null | jq -e --arg n "${env_name}" '.[] | select(.Name == $n)' >/dev/null \
        || azd env new "${env_name}" --subscription "${SUB_ID}" --location "${LOCATION}" --no-prompt
    azd env select "${env_name}"
}
