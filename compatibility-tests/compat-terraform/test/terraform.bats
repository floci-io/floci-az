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

@test "Terraform: azurerm_location resolves the configured location" {
    cd "$TF_DIR"
    run terraform output -raw location_display_name
    assert_success
    assert_output "East US"

    run terraform output -raw location_zone_count
    assert_success
    assert_output "3"
}

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

@test "Terraform: storage account tags read back, so a second plan leaves them unchanged" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Storage/storageAccounts/${SA_NAME}"
    assert_success
    assert_output --partial '"env":"floci-test"'

    # The account still plans other drift (the queue and table encryption key type), so only tags are checked here.
    cd "$TF_DIR"
    run terraform plan -input=false -no-color -target=azurerm_storage_account.sa
    assert_success
    refute_output --partial "~ tags"
    refute_output --partial "+ tags"
}

@test "Terraform: a tag-only change is applied through the account update and leaves no tag drift" {
    cd "$TF_DIR"
    local changed='storage_tags={env="floci-test",stage="updated"}'
    run terraform apply -input=false -auto-approve -no-color -target=azurerm_storage_account.sa -var "$changed"
    assert_success
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Storage/storageAccounts/${SA_NAME}"
    assert_success
    assert_output --partial '"stage":"updated"'
    run terraform plan -input=false -no-color -target=azurerm_storage_account.sa -var "$changed"
    assert_success
    refute_output --partial "~ tags"

    # Back to the configured tags, so the tests after this one see the account as applied.
    run terraform apply -input=false -auto-approve -no-color -target=azurerm_storage_account.sa
    assert_success
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Storage/storageAccounts/${SA_NAME}"
    refute_output --partial '"stage"'
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

@test "Terraform: key vault reads back what the provider wrote" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.KeyVault/vaults/${KV_NAME}"
    assert_success
    assert_output --partial '"softDeleteRetentionInDays":7'
    assert_output --partial '"enableSoftDelete":true'
    assert_output --partial '"env":"compat"'
}

@test "Terraform: key vault secret readable via data plane" {
    run kv_get "secrets/${SECRET_NAME}"
    assert_success
    assert_output --partial "hello-from-terraform"
}

@test "Terraform: role assignment on the key vault stored with the role looked up by name" {
    cd "$TF_DIR"
    run terraform output -raw role_assignment_kv_id
    assert_success
    assignment="$output"
    run arm_get "${assignment#/}?api-version=2022-04-01"
    assert_success
    assert_output --partial '/roleDefinitions/4633458b-17de-408a-b874-0445c86b69e6"'
    assert_output --partial '"principalType":"ServicePrincipal"'
    assert_output --partial '"scope":"/subscriptions/'
}

@test "Terraform: role definition data source answers Reader in its subscription form" {
    cd "$TF_DIR"
    run terraform output -raw role_definition_reader_id
    assert_success
    assert_output --partial "/subscriptions/"
    assert_output --partial "/providers/Microsoft.Authorization/roleDefinitions/acdd72a7-3385-48ef-bd42-f606fba81ae7"
}

@test "Terraform: a plan after apply leaves the role assignments unchanged" {
    cd "$TF_DIR"
    run terraform plan -input=false -no-color -detailed-exitcode \
        -target=azurerm_role_assignment.kv_secrets_user -target=azurerm_role_assignment.rg_reader
    assert_success
    assert_output --partial "No changes"
}

@test "Terraform: virtual network created" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Network/virtualNetworks/${VNET_NAME}"
    assert_success
    assert_output --partial "$VNET_NAME"
}

@test "Terraform: network interface has a private IP" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Network/networkInterfaces/${NIC_NAME}"
    assert_success
    assert_output --partial "privateIPAddress"
}

@test "Terraform: linux virtual machine created with Succeeded state" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Compute/virtualMachines/${VM_NAME}"
    assert_success
    assert_output --partial "Microsoft.Compute/virtualMachines"
    assert_output --partial "Succeeded"
}

@test "Terraform: VM instanceView reports running power state" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Compute/virtualMachines/${VM_NAME}/instanceView"
    assert_success
    assert_output --partial "PowerState/running"
}

@test "Terraform: redis cache created with Succeeded state" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Cache/redis/${REDIS_NAME}"
    assert_success
    assert_output --partial "Microsoft.Cache/Redis"
    assert_output --partial "Succeeded"
    assert_output --partial "hostName"
}

@test "Terraform: container registry created with Succeeded state" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.ContainerRegistry/registries/${ACR_NAME}"
    assert_success
    assert_output --partial "Microsoft.ContainerRegistry/registries"
    assert_output --partial "Succeeded"
    assert_output --partial "loginServer"
}

@test "Terraform: private DNS zone created" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Network/privateDnsZones/${PDZ_NAME}"
    assert_success
    assert_output --partial "Microsoft.Network/privateDnsZones"
    assert_output --partial "Succeeded"
}

@test "Terraform: private DNS zone virtual network link reports Completed" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Network/privateDnsZones/${PDZ_NAME}/virtualNetworkLinks/${PDZ_LINK_NAME}"
    assert_success
    assert_output --partial "Microsoft.Network/privateDnsZones/virtualNetworkLinks"
    assert_output --partial "Completed"
}

@test "Terraform: private endpoint created with synthesized NIC and approved connection" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Network/privateEndpoints/${PE_NAME}"
    assert_success
    assert_output --partial "Microsoft.Network/privateEndpoints"
    assert_output --partial "privateLinkServiceConnections"
    assert_output --partial "networkInterfaces"
    assert_output --partial "Succeeded"
}

@test "Terraform: NSG created with inline rule, child rule, and default rules" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Network/networkSecurityGroups/${NSG_NAME}"
    assert_success
    assert_output --partial "Microsoft.Network/networkSecurityGroups"
    assert_output --partial "allow-https"
    assert_output --partial "${NSG_RULE_NAME}"
    assert_output --partial "DenyAllInBound"
    assert_output --partial "Succeeded"
}

@test "Terraform: load balancer echoes sku and frontend with backend pool" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Network/loadBalancers/${LB_NAME}"
    assert_success
    assert_output --partial "Microsoft.Network/loadBalancers"
    assert_output --partial "Standard"
    assert_output --partial "frontendIPConfigurations"
    assert_output --partial "${LB_POOL_NAME}"
    assert_output --partial "Succeeded"
}

@test "Terraform: application gateway created with listeners and routing rules" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Network/applicationGateways/${AGW_NAME}"
    assert_success
    assert_output --partial "Microsoft.Network/applicationGateways"
    assert_output --partial "httpListeners"
    assert_output --partial "requestRoutingRules"
    assert_output --partial "Running"
    assert_output --partial "Succeeded"
}

@test "Terraform: container group created with Succeeded state" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.ContainerInstance/containerGroups/${ACI_NAME}"
    assert_success
    assert_output --partial "Microsoft.ContainerInstance/containerGroups"
    assert_output --partial "Succeeded"
}

@test "Terraform: container group echoes canonical enum casing and resource requests" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.ContainerInstance/containerGroups/${ACI_NAME}"
    assert_success
    assert_output --partial "\"osType\":\"Linux\""
    assert_output --partial "\"protocol\":\"TCP\""
    assert_output --partial "memoryInGB"
}

@test "Terraform: template deployment succeeded with an output read live from its storage account" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Resources/deployments/floci-test-deployment?api-version=2020-06-01"
    assert_success
    assert_output --partial '"provisioningState":"Succeeded"'
    run terraform output -raw template_blob_endpoint
    assert_success
    assert_output --partial "flocitesttplsa"
}

@test "Terraform: template deployment lists its module's resource, not the module" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Resources/deployments/floci-test-deployment?api-version=2020-06-01"
    assert_success
    assert_output --partial "userAssignedIdentities/floci-test-module-identity"
    refute_output --partial "deployments/floci-test-module"
}

@test "Terraform: template deployment created a real storage account and container" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Storage/storageAccounts/flocitesttplsa?api-version=2023-01-01"
    assert_success
    assert_output --partial "primaryEndpoints"
    run curl -sf "${FLOCI_AZ_ENDPOINT}/flocitesttplsa?comp=list&restype=container"
    assert_success
    assert_output --partial "tplcontainer"
}

@test "Terraform: mssql server created with its TLS version and connection policy" {
    local server="subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Sql/servers/${SQL_NAME}"
    run arm_get "${server}?api-version=2023-08-01-preview"
    assert_success
    assert_output --partial '"fullyQualifiedDomainName":"floci-test-sql.database.windows.net"'
    assert_output --partial '"minimalTlsVersion":"1.2"'

    run arm_get "${server}/connectionPolicies/default?api-version=2023-08-01-preview"
    assert_success
    assert_output --partial '"connectionType":"Default"'
}

@test "Terraform: mssql database online with an integer size and its settings readable" {
    local db="subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Sql/servers/${SQL_NAME}/databases/${SQL_DB_NAME}"
    run arm_get "${db}?api-version=2023-08-01-preview"
    assert_success
    assert_output --partial '"status":"Online"'
    assert_output --partial '"maxSizeBytes":1073741824'
    assert_output --partial '"currentServiceObjectiveName":"S0"'

    run arm_get "${db}/transparentDataEncryption/current?api-version=2023-08-01-preview"
    assert_success
    assert_output --partial '"state":"Enabled"'

    run arm_get "${db}/securityAlertPolicies/default?api-version=2023-08-01-preview"
    assert_success
    assert_output --partial '"state":"Disabled"'
}

@test "Terraform: a plan after apply leaves the mssql server and database unchanged" {
    cd "$TF_DIR"
    run terraform plan -input=false -no-color -detailed-exitcode \
        -target=azurerm_mssql_server.sql -target=azurerm_mssql_database.sqldb
    assert_success
    assert_output --partial "No changes"
}

# The destroy in teardown_file ignores failures, so the mssql destroy is checked here.
@test "Terraform: destroying the mssql server removes it and its database" {
    cd "$TF_DIR"
    run terraform destroy -input=false -auto-approve -no-color -target=azurerm_mssql_server.sql
    assert_success
    assert_output --partial "azurerm_mssql_database.sqldb: Destruction complete"
    assert_output --partial "azurerm_mssql_server.sql: Destruction complete"

    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Sql/servers/${SQL_NAME}"
    assert_failure
}

# Runs last: the destroy in teardown_file ignores failures, and leaves nothing to do when this passes.
@test "Terraform: destroying everything removes the resource group and purges the key vault" {
    cd "$TF_DIR"
    run terraform destroy -input=false -auto-approve -no-color
    assert_success
    assert_output --partial "azurerm_key_vault.kv: Destruction complete"
    assert_output --partial "azurerm_resource_group.rg: Destruction complete"

    run curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer fake" \
        "${FLOCI_AZ_ENDPOINT}/subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}?api-version=2021-04-01"
    assert_output "404"

    run curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer fake" \
        "${FLOCI_AZ_ENDPOINT}/subscriptions/${SUB_ID}/providers/Microsoft.KeyVault/locations/eastus/deletedVaults/${KV_NAME}?api-version=2023-02-01"
    assert_output "404"
}
