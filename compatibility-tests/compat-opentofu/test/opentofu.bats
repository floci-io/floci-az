#!/usr/bin/env bats
# OpenTofu compatibility tests for floci-az

setup_file() {
    load 'test_helper/common-setup'

    cd "$TOFU_DIR"

    echo "# === OpenTofu Compatibility Test ===" >&3
    echo "# Endpoint: $FLOCI_AZ_ENDPOINT" >&3
    echo "# Metadata host: $TF_VAR_metadata_host" >&3

    # Clean any previous state
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true

    echo "# --- tofu init ---" >&3
    run tofu init -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# tofu init failed: $output" >&3
        return 1
    fi

    echo "# --- tofu validate ---" >&3
    run tofu validate -no-color
    if [ "$status" -ne 0 ]; then
        echo "# tofu validate failed: $output" >&3
        return 1
    fi

    echo "# --- tofu plan ---" >&3
    run tofu plan -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# tofu plan failed: $output" >&3
        return 1
    fi

    echo "# --- tofu apply ---" >&3
    run tofu apply -input=false -auto-approve -no-color
    if [ "$status" -ne 0 ]; then
        echo "# tofu apply failed: $output" >&3
        return 1
    fi
}

teardown_file() {
    load 'test_helper/common-setup'

    cd "$TOFU_DIR"

    echo "# --- tofu destroy ---" >&3
    tofu destroy -input=false -auto-approve -no-color || true
}

setup() {
    load 'test_helper/common-setup'
}

# --- Spot Checks ---

@test "OpenTofu: resource group created" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}"
    assert_success
    assert_output --partial "\"name\""
    assert_output --partial "$RG_NAME"
}

@test "OpenTofu: storage account created with blob endpoint" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Storage/storageAccounts/${SA_NAME}"
    assert_success
    assert_output --partial "primaryEndpoints"
    assert_output --partial "blob"
}

@test "OpenTofu: storage container listed in blob API" {
    run curl -sf "${FLOCI_AZ_ENDPOINT}/${SA_NAME}?comp=list&restype=container"
    assert_success
    assert_output --partial "$CONTAINER_NAME"
}

@test "OpenTofu: storage queue listed in queue API" {
    run curl -sf "${FLOCI_AZ_ENDPOINT}/${SA_NAME}-queue?comp=list"
    assert_success
    assert_output --partial "$QUEUE_NAME"
}

@test "OpenTofu: key vault created with vault URI" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.KeyVault/vaults/${KV_NAME}"
    assert_success
    assert_output --partial "vaultUri"
    assert_output --partial "${KV_NAME}.vault.azure.net"
}

@test "OpenTofu: key vault secret readable via data plane" {
    run kv_get "secrets/${SECRET_NAME}"
    assert_success
    assert_output --partial "hello-from-opentofu"
}

@test "OpenTofu: virtual network created" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Network/virtualNetworks/${VNET_NAME}"
    assert_success
    assert_output --partial "$VNET_NAME"
}

@test "OpenTofu: network interface has a private IP" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Network/networkInterfaces/${NIC_NAME}"
    assert_success
    assert_output --partial "privateIPAddress"
}

@test "OpenTofu: linux virtual machine created with Succeeded state" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Compute/virtualMachines/${VM_NAME}"
    assert_success
    assert_output --partial "Microsoft.Compute/virtualMachines"
    assert_output --partial "Succeeded"
}

@test "OpenTofu: VM instanceView reports running power state" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Compute/virtualMachines/${VM_NAME}/instanceView"
    assert_success
    assert_output --partial "PowerState/running"
}

@test "OpenTofu: redis cache created with Succeeded state" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Cache/redis/${REDIS_NAME}"
    assert_success
    assert_output --partial "Microsoft.Cache/Redis"
    assert_output --partial "Succeeded"
    assert_output --partial "hostName"
}

@test "OpenTofu: container registry created with Succeeded state" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.ContainerRegistry/registries/${ACR_NAME}"
    assert_success
    assert_output --partial "Microsoft.ContainerRegistry/registries"
    assert_output --partial "Succeeded"
    assert_output --partial "loginServer"
}

@test "OpenTofu: postgresql flexible server created with Succeeded state" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.DBforPostgreSQL/flexibleServers/${PG_NAME}"
    assert_success
    assert_output --partial "Microsoft.DBforPostgreSQL/flexibleServers"
    assert_output --partial "Succeeded"
    assert_output --partial "fullyQualifiedDomainName"
}

@test "OpenTofu: postgresql flexible server database created" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.DBforPostgreSQL/flexibleServers/${PG_NAME}/databases/${PG_DB_NAME}"
    assert_success
    assert_output --partial "$PG_DB_NAME"
}
