#!/usr/bin/env bats
# Terraform compatibility tests for floci-az

setup_file() {
    load 'test_helper/common-setup'

    cd "$TF_DIR"

    echo "# === Terraform Compatibility Test ===" >&3
    echo "# Endpoint: $FLOCI_AZ_ENDPOINT" >&3
    echo "# Metadata host: $TF_VAR_metadata_host" >&3

    # Clean any previous state
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true

    echo "# --- terraform init ---" >&3
    run terraform init -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform init failed: $output" >&3
        return 1
    fi

    echo "# --- terraform validate ---" >&3
    run terraform validate -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform validate failed: $output" >&3
        return 1
    fi

    echo "# --- terraform plan ---" >&3
    run terraform plan -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform plan failed: $output" >&3
        return 1
    fi

    echo "# --- terraform apply ---" >&3
    run terraform apply -input=false -auto-approve -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform apply failed: $output" >&3
        return 1
    fi
}

teardown_file() {
    load 'test_helper/common-setup'

    cd "$TF_DIR"

    echo "# --- terraform destroy ---" >&3
    terraform destroy -input=false -auto-approve -no-color || true
}

setup() {
    load 'test_helper/common-setup'
}

# --- Spot Checks ---

@test "Terraform: resource group created" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}"
    assert_success
    assert_output --partial "\"name\""
    assert_output --partial "$RG_NAME"
}

@test "Terraform: storage account created with blob endpoint" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Storage/storageAccounts/${SA_NAME}"
    assert_success
    assert_output --partial "primaryEndpoints"
    assert_output --partial "blob"
}

@test "Terraform: storage container listed in blob API" {
    run curl -sf "${FLOCI_AZ_ENDPOINT}/${SA_NAME}?comp=list&restype=container"
    assert_success
    assert_output --partial "$CONTAINER_NAME"
}

@test "Terraform: storage queue listed in queue API" {
    run curl -sf "${FLOCI_AZ_ENDPOINT}/${SA_NAME}-queue?comp=list"
    assert_success
    assert_output --partial "$QUEUE_NAME"
}

@test "Terraform: key vault created with vault URI" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.KeyVault/vaults/${KV_NAME}"
    assert_success
    assert_output --partial "vaultUri"
    assert_output --partial "${KV_NAME}.vault.azure.net"
}

@test "Terraform: key vault secret readable via data plane" {
    run kv_get "secrets/${SECRET_NAME}"
    assert_success
    assert_output --partial "hello-from-terraform"
}
