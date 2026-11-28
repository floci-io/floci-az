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

@test "OpenTofu: azurerm_location resolves the configured location" {
    cd "$TOFU_DIR"
    run tofu output -raw location_display_name
    assert_success
    assert_output "East US"

    run tofu output -raw location_zone_count
    assert_success
    assert_output "3"
}

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

@test "OpenTofu: storage account tags read back, so a second plan leaves them unchanged" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Storage/storageAccounts/${SA_NAME}"
    assert_success
    assert_output --partial '"env":"floci-test"'

    # The account still plans other drift (the queue and table encryption key type), so only tags are checked here.
    cd "$TOFU_DIR"
    run tofu plan -input=false -no-color -target=azurerm_storage_account.sa
    assert_success
    refute_output --partial "~ tags"
    refute_output --partial "+ tags"
}

@test "OpenTofu: a tag-only change is applied through the account update and leaves no tag drift" {
    cd "$TOFU_DIR"
    local changed='storage_tags={env="floci-test",stage="updated"}'
    run tofu apply -input=false -auto-approve -no-color -target=azurerm_storage_account.sa -var "$changed"
    assert_success
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Storage/storageAccounts/${SA_NAME}"
    assert_success
    assert_output --partial '"stage":"updated"'
    run tofu plan -input=false -no-color -target=azurerm_storage_account.sa -var "$changed"
    assert_success
    refute_output --partial "~ tags"

    # Back to the configured tags, so the tests after this one see the account as applied.
    run tofu apply -input=false -auto-approve -no-color -target=azurerm_storage_account.sa
    assert_success
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Storage/storageAccounts/${SA_NAME}"
    refute_output --partial '"stage"'
}

@test "OpenTofu: storage account blob properties read back, so a second plan leaves them unchanged" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Storage/storageAccounts/${SA_NAME}/blobServices/default"
    assert_success
    assert_output --partial '"isVersioningEnabled":true'

    # The account still plans other drift (the queue and table encryption key type), so only blob_properties is checked.
    cd "$TOFU_DIR"
    run tofu plan -input=false -no-color -target=azurerm_storage_account.sa
    assert_success
    refute_output --partial "blob_properties"
}

@test "OpenTofu: storage management policy stored, so a second plan leaves it unchanged" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Storage/storageAccounts/${SA_NAME}/managementPolicies/default"
    assert_success
    assert_output --partial '"expire-logs"'

    cd "$TOFU_DIR"
    run tofu plan -input=false -no-color -target=azurerm_storage_management_policy.mp
    assert_success
    refute_output --partial "# azurerm_storage_management_policy.mp"
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

@test "OpenTofu: event grid topic and its event subscription created" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.EventGrid/topics/floci-test-egtopic/providers/Microsoft.EventGrid/eventSubscriptions/floci-test-egsub"
    assert_success
    assert_output --partial '"provisioningState":"Succeeded"'
    assert_output --partial "https://127.0.0.1:1/eventgrid"
}

@test "OpenTofu: a plan after apply leaves the event grid topic and subscription unchanged" {
    cd "$TOFU_DIR"
    run tofu plan -input=false -no-color -detailed-exitcode \
        -target=azurerm_eventgrid_topic.egt -target=azurerm_eventgrid_event_subscription.egs
    assert_success
    assert_output --partial "No changes"
}

@test "OpenTofu: key vault created with vault URI" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.KeyVault/vaults/${KV_NAME}"
    assert_success
    assert_output --partial "vaultUri"
    assert_output --partial "${KV_NAME}.vault.azure.net"
}

@test "OpenTofu: key vault reads back what the provider wrote" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.KeyVault/vaults/${KV_NAME}"
    assert_success
    assert_output --partial '"softDeleteRetentionInDays":7'
    assert_output --partial '"enableSoftDelete":true'
    assert_output --partial '"env":"compat"'
}

@test "OpenTofu: key vault secret readable via data plane" {
    run kv_get "secrets/${SECRET_NAME}"
    assert_success
    assert_output --partial "hello-from-opentofu"
}

@test "OpenTofu: role assignment on the key vault stored with the role looked up by name" {
    cd "$TOFU_DIR"
    run tofu output -raw role_assignment_kv_id
    assert_success
    assignment="$output"
    run arm_get "${assignment#/}?api-version=2022-04-01"
    assert_success
    assert_output --partial '/roleDefinitions/4633458b-17de-408a-b874-0445c86b69e6"'
    assert_output --partial '"principalType":"ServicePrincipal"'
    assert_output --partial '"scope":"/subscriptions/'
}

@test "OpenTofu: role definition data source answers Reader in its subscription form" {
    cd "$TOFU_DIR"
    run tofu output -raw role_definition_reader_id
    assert_success
    assert_output --partial "/subscriptions/"
    assert_output --partial "/providers/Microsoft.Authorization/roleDefinitions/acdd72a7-3385-48ef-bd42-f606fba81ae7"
}

@test "OpenTofu: a plan after apply leaves the role assignments unchanged" {
    cd "$TOFU_DIR"
    run tofu plan -input=false -no-color -detailed-exitcode \
        -target=azurerm_role_assignment.kv_secrets_user -target=azurerm_role_assignment.rg_reader
    assert_success
    assert_output --partial "No changes"
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

@test "OpenTofu: search service created in the succeeded state with its endpoint" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Search/searchServices/${SEARCH_NAME}?api-version=2025-05-01"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.type')" "Microsoft.Search/searchServices"
    assert_equal "$(echo "$output" | jq -r '.sku.name')" "basic"
    assert_equal "$(echo "$output" | jq -r '.properties.provisioningState')" "succeeded"
    assert_equal "$(echo "$output" | jq -r '.properties.endpoint')" "https://${SEARCH_NAME}.search.windows.net"
}

@test "OpenTofu: search service primary key is the admin key the service lists" {
    run curl -sf -X POST -H "Authorization: Bearer fake" \
        "${FLOCI_AZ_ENDPOINT}/subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Search/searchServices/${SEARCH_NAME}/listAdminKeys?api-version=2025-05-01"
    assert_success
    listed="$(echo "$output" | jq -r '.primaryKey')"
    run tofu -chdir="$TOFU_DIR" output -raw search_primary_key
    assert_success
    assert_equal "$output" "$listed"
}

@test "OpenTofu: IoT hub created with its host name, Succeeded and Active" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Devices/IotHubs/${IOTHUB_NAME}?api-version=2023-06-30"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.type')" "Microsoft.Devices/IotHubs"
    assert_equal "$(echo "$output" | jq -r '.sku.name')" "S1"
    assert_equal "$(echo "$output" | jq -r '.properties.provisioningState')" "Succeeded"
    assert_equal "$(echo "$output" | jq -r '.properties.state')" "Active"
    assert_equal "$(echo "$output" | jq -r '.properties.hostName')" "${IOTHUB_NAME}.azure-devices.net"

    run tofu -chdir="$TOFU_DIR" output -raw iothub_hostname
    assert_success
    assert_output "${IOTHUB_NAME}.azure-devices.net"
}

@test "OpenTofu: IoT hub owner key is the one the hub lists" {
    run curl -sf -X POST -H "Authorization: Bearer fake" \
        "${FLOCI_AZ_ENDPOINT}/subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Devices/IotHubs/${IOTHUB_NAME}/IotHubKeys/iothubowner/listkeys?api-version=2023-06-30"
    assert_success
    listed="$(echo "$output" | jq -r '.primaryKey')"
    run tofu -chdir="$TOFU_DIR" output -raw iothub_owner_primary_key
    assert_success
    assert_equal "$output" "$listed"
}

@test "OpenTofu: a plan after apply leaves the IoT hub unchanged" {
    cd "$TOFU_DIR"
    run tofu plan -input=false -no-color -detailed-exitcode -target=azurerm_iothub.iothub
    assert_success
    assert_output --partial "No changes"
}

@test "OpenTofu: an imported IoT hub plans no changes" {
    cd "$TOFU_DIR"
    hub_id="$(tofu output -raw iothub_id)"
    run tofu state rm azurerm_iothub.iothub
    assert_success
    run tofu import -input=false -no-color azurerm_iothub.iothub "$hub_id"
    assert_success
    run tofu plan -input=false -no-color -detailed-exitcode -target=azurerm_iothub.iothub
    assert_success
    assert_output --partial "No changes"
}

@test "OpenTofu: IoT hub consumer group created on the events endpoint" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Devices/IotHubs/${IOTHUB_NAME}/eventHubEndpoints/events/ConsumerGroups/floci-cg?api-version=2023-06-30"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.name')" "floci-cg"
    assert_equal "$(echo "$output" | jq -r '.type')" "Microsoft.Devices/IotHubs/EventHubEndpoints/ConsumerGroups"

    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Devices/IotHubs/${IOTHUB_NAME}/eventHubEndpoints/events/ConsumerGroups?api-version=2023-06-30"
    assert_success
    assert_equal "$(echo "$output" | jq -r '[.value[].name] | join(",")')" '$Default,floci-cg'
}

@test "OpenTofu: a plan after apply leaves the IoT hub consumer group unchanged" {
    cd "$TOFU_DIR"
    run tofu plan -input=false -no-color -detailed-exitcode -target=azurerm_iothub_consumer_group.iothub_cg
    assert_success
    assert_output --partial "No changes"
}

@test "OpenTofu: an imported IoT hub consumer group plans no changes" {
    cd "$TOFU_DIR"
    cg_id="$(tofu output -raw iothub_consumer_group_id)"
    run tofu state rm azurerm_iothub_consumer_group.iothub_cg
    assert_success
    run tofu import -input=false -no-color azurerm_iothub_consumer_group.iothub_cg "$cg_id"
    assert_success
    run tofu plan -input=false -no-color -detailed-exitcode -target=azurerm_iothub_consumer_group.iothub_cg
    assert_success
    assert_output --partial "No changes"
}

@test "OpenTofu: destroying the IoT hub consumer group leaves the hub and \$Default" {
    cd "$TOFU_DIR"
    run tofu destroy -input=false -auto-approve -no-color -target=azurerm_iothub_consumer_group.iothub_cg
    assert_success
    assert_output --partial "azurerm_iothub_consumer_group.iothub_cg: Destruction complete"

    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Devices/IotHubs/${IOTHUB_NAME}/eventHubEndpoints/events/ConsumerGroups/floci-cg?api-version=2023-06-30"
    assert_failure
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Devices/IotHubs/${IOTHUB_NAME}/eventHubEndpoints/events/ConsumerGroups?api-version=2023-06-30"
    assert_success
    assert_equal "$(echo "$output" | jq -r '[.value[].name] | join(",")')" '$Default'
}

@test "OpenTofu: destroying the IoT hub removes it" {
    cd "$TOFU_DIR"
    run tofu destroy -input=false -auto-approve -no-color -target=azurerm_iothub.iothub
    assert_success
    assert_output --partial "azurerm_iothub.iothub: Destruction complete"

    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Devices/IotHubs/${IOTHUB_NAME}?api-version=2023-06-30"
    assert_failure
}

@test "OpenTofu: Service Bus namespace created Succeeded, with its endpoint and the root rule" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.ServiceBus/namespaces/${SB_NAMESPACE_NAME}?api-version=2024-01-01"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.sku.name')" "Standard"
    assert_equal "$(echo "$output" | jq -r '.properties.provisioningState')" "Succeeded"
    assert_equal "$(echo "$output" | jq -r '.properties.serviceBusEndpoint')" "https://${SB_NAMESPACE_NAME}.servicebus.windows.net:443/"

    run curl -sf -X POST -H "Authorization: Bearer fake" \
        "${FLOCI_AZ_ENDPOINT}/subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.ServiceBus/namespaces/${SB_NAMESPACE_NAME}/AuthorizationRules/RootManageSharedAccessKey/listKeys?api-version=2024-01-01"
    assert_success
    listed="$(echo "$output" | jq -r '.primaryConnectionString')"
    run tofu -chdir="$TOFU_DIR" output -raw servicebus_root_connection_string
    assert_success
    assert_equal "$output" "$listed"
}

@test "OpenTofu: a Service Bus queue created through ARM is the queue the Atom API serves" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.ServiceBus/namespaces/${SB_NAMESPACE_NAME}/queues/orders?api-version=2024-01-01"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.properties.maxDeliveryCount')" "5"
    assert_equal "$(echo "$output" | jq -r '.properties.lockDuration')" "PT30S"

    run curl -sf "${FLOCI_AZ_ENDPOINT}/devstoreaccount1-servicebus/${SB_NAMESPACE_NAME}/queues/orders"
    assert_success
    assert_output --partial "<MaxDeliveryCount>5</MaxDeliveryCount>"
}

@test "OpenTofu: the Service Bus subscription rule and the sender rule's key are the stored ones" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.ServiceBus/namespaces/${SB_NAMESPACE_NAME}/topics/events/subscriptions/audit/rules/high-priority?api-version=2024-01-01"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.properties.sqlFilter.sqlExpression')" "priority > 5"

    run curl -sf -X POST -H "Authorization: Bearer fake" \
        "${FLOCI_AZ_ENDPOINT}/subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.ServiceBus/namespaces/${SB_NAMESPACE_NAME}/AuthorizationRules/sender/listKeys?api-version=2024-01-01"
    assert_success
    listed="$(echo "$output" | jq -r '.primaryKey')"
    run tofu -chdir="$TOFU_DIR" output -raw servicebus_sender_primary_key
    assert_success
    assert_equal "$output" "$listed"
}

@test "OpenTofu: a plan after apply leaves the Service Bus resources unchanged" {
    cd "$TOFU_DIR"
    run tofu plan -input=false -no-color -detailed-exitcode \
        -target=azurerm_servicebus_namespace.sb -target=azurerm_servicebus_queue.sb \
        -target=azurerm_servicebus_topic.sb -target=azurerm_servicebus_subscription.sb \
        -target=azurerm_servicebus_subscription_rule.sb -target=azurerm_servicebus_namespace_authorization_rule.sb
    assert_success
    assert_output --partial "No changes"
}

@test "OpenTofu: destroying the Service Bus namespace removes it and its queue" {
    cd "$TOFU_DIR"
    run tofu destroy -input=false -auto-approve -no-color -target=azurerm_servicebus_namespace.sb
    assert_success
    assert_output --partial "azurerm_servicebus_namespace.sb: Destruction complete"

    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.ServiceBus/namespaces/${SB_NAMESPACE_NAME}?api-version=2024-01-01"
    assert_failure
    run curl -sf "${FLOCI_AZ_ENDPOINT}/devstoreaccount1-servicebus/${SB_NAMESPACE_NAME}/queues/orders"
    assert_failure
}

@test "OpenTofu: container registry created with Succeeded state" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.ContainerRegistry/registries/${ACR_NAME}"
    assert_success
    assert_output --partial "Microsoft.ContainerRegistry/registries"
    assert_output --partial "Succeeded"
    assert_output --partial "loginServer"
}

@test "OpenTofu: postgresql flexible server network reads back, so a second plan leaves the server unchanged" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.DBforPostgreSQL/flexibleServers/${PG_NAME}"
    assert_success
    assert_output --partial '"publicNetworkAccess":"Disabled"'

    cd "$TOFU_DIR"
    run tofu plan -input=false -no-color -detailed-exitcode -target=azurerm_postgresql_flexible_server.pg
    assert_success
    assert_output --partial "No changes"
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

@test "OpenTofu: an Entra-only postgresql server and its Entra administrator read back, so a second plan leaves them unchanged" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.DBforPostgreSQL/flexibleServers/floci-test-pg-entra"
    assert_success
    assert_output --partial '"passwordAuth":"Disabled"'
    refute_output --partial '"administratorLogin"'

    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.DBforPostgreSQL/flexibleServers/floci-test-pg-entra/administrators/22222222-2222-2222-2222-222222222222"
    assert_success
    assert_output --partial '"principalType":"ServicePrincipal"'

    cd "$TOFU_DIR"
    run tofu plan -input=false -no-color -detailed-exitcode \
        -target=azurerm_postgresql_flexible_server.pg_entra \
        -target=azurerm_postgresql_flexible_server_active_directory_administrator.pg_entra_admin
    assert_success
    assert_output --partial "No changes"
}

@test "OpenTofu: private DNS zone created" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Network/privateDnsZones/${PDZ_NAME}"
    assert_success
    assert_output --partial "Microsoft.Network/privateDnsZones"
    assert_output --partial "Succeeded"
}

@test "OpenTofu: private DNS zone virtual network link reports Completed" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Network/privateDnsZones/${PDZ_NAME}/virtualNetworkLinks/${PDZ_LINK_NAME}"
    assert_success
    assert_output --partial "Microsoft.Network/privateDnsZones/virtualNetworkLinks"
    assert_output --partial "Completed"
}

@test "OpenTofu: private endpoint created with synthesized NIC and approved connection" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Network/privateEndpoints/${PE_NAME}"
    assert_success
    assert_output --partial "Microsoft.Network/privateEndpoints"
    assert_output --partial "privateLinkServiceConnections"
    assert_output --partial "networkInterfaces"
    assert_output --partial "Succeeded"
}

@test "OpenTofu: NSG created with inline rule, child rule, and default rules" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Network/networkSecurityGroups/${NSG_NAME}"
    assert_success
    assert_output --partial "Microsoft.Network/networkSecurityGroups"
    assert_output --partial "allow-https"
    assert_output --partial "${NSG_RULE_NAME}"
    assert_output --partial "DenyAllInBound"
    assert_output --partial "Succeeded"
}

@test "OpenTofu: load balancer echoes sku and frontend with backend pool" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Network/loadBalancers/${LB_NAME}"
    assert_success
    assert_output --partial "Microsoft.Network/loadBalancers"
    assert_output --partial "Standard"
    assert_output --partial "frontendIPConfigurations"
    assert_output --partial "${LB_POOL_NAME}"
    assert_output --partial "Succeeded"
}

@test "OpenTofu: application gateway created with listeners and routing rules" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Network/applicationGateways/${AGW_NAME}"
    assert_success
    assert_output --partial "Microsoft.Network/applicationGateways"
    assert_output --partial "httpListeners"
    assert_output --partial "requestRoutingRules"
    assert_output --partial "Running"
    assert_output --partial "Succeeded"
}

@test "OpenTofu: container group created with Succeeded state" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.ContainerInstance/containerGroups/${ACI_NAME}"
    assert_success
    assert_output --partial "Microsoft.ContainerInstance/containerGroups"
    assert_output --partial "Succeeded"
}

@test "OpenTofu: container group echoes canonical enum casing and resource requests" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.ContainerInstance/containerGroups/${ACI_NAME}"
    assert_success
    assert_output --partial "\"osType\":\"Linux\""
    assert_output --partial "\"protocol\":\"TCP\""
    assert_output --partial "memoryInGB"
}

@test "OpenTofu: template deployment succeeded with an output read live from its storage account" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Resources/deployments/floci-test-deployment?api-version=2020-06-01"
    assert_success
    assert_output --partial '"provisioningState":"Succeeded"'
    run tofu output -raw template_blob_endpoint
    assert_success
    assert_output --partial "flocitesttplsa"
}

@test "OpenTofu: template deployment lists its module's resource, not the module" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Resources/deployments/floci-test-deployment?api-version=2020-06-01"
    assert_success
    assert_output --partial "userAssignedIdentities/floci-test-module-identity"
    refute_output --partial "deployments/floci-test-module"
}

@test "OpenTofu: template deployment created a real storage account and container" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Storage/storageAccounts/flocitesttplsa?api-version=2023-01-01"
    assert_success
    assert_output --partial "primaryEndpoints"
    run curl -sf "${FLOCI_AZ_ENDPOINT}/flocitesttplsa?comp=list&restype=container"
    assert_success
    assert_output --partial "tplcontainer"
}

@test "OpenTofu: mssql server created with its TLS version and connection policy" {
    local server="subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Sql/servers/${SQL_NAME}"
    run arm_get "${server}?api-version=2023-08-01-preview"
    assert_success
    assert_output --partial '"fullyQualifiedDomainName":"floci-test-sql.database.windows.net"'
    assert_output --partial '"minimalTlsVersion":"1.2"'

    run arm_get "${server}/connectionPolicies/default?api-version=2023-08-01-preview"
    assert_success
    assert_output --partial '"connectionType":"Default"'
}

@test "OpenTofu: mssql database online with an integer size and its settings readable" {
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

@test "OpenTofu: a plan after apply leaves the mssql server and database unchanged" {
    cd "$TOFU_DIR"
    run tofu plan -input=false -no-color -detailed-exitcode \
        -target=azurerm_mssql_server.sql -target=azurerm_mssql_database.sqldb
    assert_success
    assert_output --partial "No changes"
}

# The destroy in teardown_file ignores failures, so the mssql destroy is checked here.
@test "OpenTofu: destroying the mssql server removes it and its database" {
    cd "$TOFU_DIR"
    run tofu destroy -input=false -auto-approve -no-color -target=azurerm_mssql_server.sql
    assert_success
    assert_output --partial "azurerm_mssql_database.sqldb: Destruction complete"
    assert_output --partial "azurerm_mssql_server.sql: Destruction complete"

    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Sql/servers/${SQL_NAME}"
    assert_failure
}

@test "OpenTofu: linux function app created with its plan, identity and settings" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Web/sites/floci-test-func?api-version=2023-12-01"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.properties.serverFarmId | ascii_downcase | endswith("/serverfarms/floci-test-func-plan")')" "true"
    assert_equal "$(echo "$output" | jq -r '.identity.type')" "SystemAssigned"
    assert_equal "$(echo "$output" | jq -r '.identity.principalId | length > 0')" "true"

    run curl -sf -X POST -H "Authorization: Bearer fake" \
        "${FLOCI_AZ_ENDPOINT}/subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Web/sites/floci-test-func/config/appsettings/list?api-version=2023-12-01"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.properties.FLOCI_TEST_MODE')" "compat"
}

@test "OpenTofu: application insights component created with its connection string and daily cap" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Insights/components/floci-test-appi?api-version=2020-02-02"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.properties.ConnectionString | startswith("InstrumentationKey=")')" "true"

    run arm_get "subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}/providers/Microsoft.Insights/components/floci-test-appi/currentbillingfeatures?api-version=2015-05-01"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.DataVolumeCap.Cap')" "5"
}

@test "OpenTofu: a plan after apply leaves the function app, its plan and the application insights component unchanged" {
    cd "$TOFU_DIR"
    run tofu plan -input=false -no-color -detailed-exitcode \
        -target=azurerm_service_plan.func -target=azurerm_application_insights.appi \
        -target=azurerm_linux_function_app.func
    assert_success
    assert_output --partial "No changes"
}

# Runs last: the destroy in teardown_file ignores failures, and leaves nothing to do when this passes.
@test "OpenTofu: destroying everything removes the resource group and purges the key vault and the API Management service" {
    cd "$TOFU_DIR"
    run tofu destroy -input=false -auto-approve -no-color
    assert_success
    assert_output --partial "azurerm_key_vault.kv: Destruction complete"
    assert_output --partial "azurerm_api_management.apim: Destruction complete"
    assert_output --partial "azurerm_resource_group.rg: Destruction complete"

    run curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer fake" \
        "${FLOCI_AZ_ENDPOINT}/subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}?api-version=2021-04-01"
    assert_output "404"

    run curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer fake" \
        "${FLOCI_AZ_ENDPOINT}/subscriptions/${SUB_ID}/providers/Microsoft.KeyVault/locations/eastus/deletedVaults/${KV_NAME}?api-version=2023-02-01"
    assert_output "404"

    # The destroy purges the soft-deleted API Management service too (purge_soft_delete_on_destroy).
    run curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer fake" \
        "${FLOCI_AZ_ENDPOINT}/subscriptions/${SUB_ID}/providers/Microsoft.ApiManagement/locations/eastus/deletedservices/floci-test-apim?api-version=2022-08-01"
    assert_output "404"
}
