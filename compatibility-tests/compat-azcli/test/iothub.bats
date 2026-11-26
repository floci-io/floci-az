#!/usr/bin/env bats
# Azure IoT Hub: the management plane (hub lifecycle and shared access policies) via az/ARM, and the device
# identity registry via the azure-iot extension.

setup_file() {
    load 'test_helper/common-setup'

    az group create -n "$RG_NAME" -l "$LOCATION" -o none
    az iot hub create -n "$IOTHUB_NAME" -g "$RG_NAME" --sku S1 --partition-count 2 -o none
}

teardown_file() {
    load 'test_helper/common-setup'

    az iot hub delete -n "$IOTHUB_NAME" -g "$RG_NAME" -o none 2>/dev/null || true
}

setup() {
    load 'test_helper/common-setup'
}

owner_key() {
    az_json iot hub policy show --hub-name "$IOTHUB_NAME" -g "$RG_NAME" -n iothubowner | jq -r '.primaryKey'
}

@test "az iot hub: created Active and Succeeded with its host name" {
    run az_json iot hub show -n "$IOTHUB_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.name')" "$IOTHUB_NAME"
    assert_equal "$(echo "$output" | jq -r '.sku.name')" "S1"
    assert_equal "$(echo "$output" | jq -r '.sku.tier')" "Standard"
    assert_equal "$(echo "$output" | jq -r '.properties.state')" "Active"
    assert_equal "$(echo "$output" | jq -r '.properties.provisioningState')" "Succeeded"
    assert_equal "$(echo "$output" | jq -r '.properties.hostName')" "${IOTHUB_NAME}.azure-devices.net"
    assert_equal "$(echo "$output" | jq -r '.properties.eventHubEndpoints.events.partitionCount')" "2"
}

@test "az iot hub: shown without a resource group, as the CLI finds it across the subscription" {
    run az_json iot hub show -n "$IOTHUB_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.resourcegroup')" "$RG_NAME"
}

@test "az iot hub: listed in its resource group and its subscription" {
    run az_json iot hub list -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r --arg n "$IOTHUB_NAME" '[.[] | select(.name == $n)] | length')" "1"

    run az_json iot hub list
    assert_success
    assert_equal "$(echo "$output" | jq -r --arg n "$IOTHUB_NAME" '[.[] | select(.name == $n)] | length')" "1"
}

@test "az iot hub: a second hub with a taken name is refused" {
    az group create -n "${RG_NAME}-iothub-other" -l "$LOCATION" -o none
    run az iot hub create -n "$IOTHUB_NAME" -g "${RG_NAME}-iothub-other" --sku S1 -o none
    assert_failure
    assert_output --partial "not available"
    az group delete -n "${RG_NAME}-iothub-other" --yes -o none
}

@test "az iot hub policy: the five default policies are listed with keys" {
    run az_json iot hub policy list --hub-name "$IOTHUB_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '[.[].keyName] | sort | join(",")')" \
        "device,iothubowner,registryRead,registryReadWrite,service"
    assert_equal "$(echo "$output" | jq -r '[.[] | select(.primaryKey == null or .primaryKey == "")] | length')" "0"
}

@test "az iot hub: update changes the hub and keeps the policy keys" {
    before="$(owner_key)"

    run az_json iot hub update -n "$IOTHUB_NAME" -g "$RG_NAME" --retention-day 3 --c2d-max-delivery-count 20 \
        --tags env=floci-test
    assert_success
    assert_equal "$(echo "$output" | jq -r '.properties.eventHubEndpoints.events.retentionTimeInDays')" "3"
    assert_equal "$(echo "$output" | jq -r '.properties.cloudToDevice.maxDeliveryCount')" "20"
    assert_equal "$(echo "$output" | jq -r '.tags.env')" "floci-test"

    run az_json iot hub show -n "$IOTHUB_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.properties.eventHubEndpoints.events.retentionTimeInDays')" "3"

    assert_equal "$(owner_key)" "$before"
}

@test "az iot hub policy: create adds a policy with keys and leaves the others' keys alone" {
    before="$(owner_key)"

    run az iot hub policy create --hub-name "$IOTHUB_NAME" -g "$RG_NAME" -n floci-policy \
        --permissions RegistryRead ServiceConnect -o none
    assert_success

    run az_json iot hub policy show --hub-name "$IOTHUB_NAME" -g "$RG_NAME" -n floci-policy
    assert_success
    assert_equal "$(echo "$output" | jq -r '.rights')" "RegistryRead, ServiceConnect"
    key="$(echo "$output" | jq -r '.primaryKey // empty')"
    assert [ -n "$key" ]

    assert_equal "$(owner_key)" "$before"
}

@test "az iot hub policy: renew-key replaces only the key asked for" {
    run az_json iot hub policy show --hub-name "$IOTHUB_NAME" -g "$RG_NAME" -n floci-policy
    assert_success
    primary="$(echo "$output" | jq -r '.primaryKey')"
    secondary="$(echo "$output" | jq -r '.secondaryKey')"

    run az iot hub policy renew-key --hub-name "$IOTHUB_NAME" -g "$RG_NAME" -n floci-policy --renew-key Primary \
        -o none
    assert_success

    run az_json iot hub policy show --hub-name "$IOTHUB_NAME" -g "$RG_NAME" -n floci-policy
    assert_success
    [ "$(echo "$output" | jq -r '.primaryKey')" != "$primary" ]
    assert_equal "$(echo "$output" | jq -r '.secondaryKey')" "$secondary"
}

@test "az iot hub policy: delete removes the policy" {
    run az iot hub policy delete --hub-name "$IOTHUB_NAME" -g "$RG_NAME" -n floci-policy -o none
    assert_success

    run az_json iot hub policy list --hub-name "$IOTHUB_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '[.[] | select(.keyName == "floci-policy")] | length')" "0"
}

group_names() {
    az_json iot hub consumer-group list --hub-name "$IOTHUB_NAME" -g "$RG_NAME" | jq -r '[.[].name] | join(",")'
}

has_group() {
    az_json iot hub consumer-group list --hub-name "$IOTHUB_NAME" -g "$RG_NAME" \
        | jq -r --arg n "$1" '[.[] | select(.name == $n)] | length'
}

@test "az iot hub consumer-group: a hub lists \$Default first" {
    run group_names
    assert_success
    assert_equal "${output%%,*}" '$Default'
}

@test "az iot hub consumer-group: create, show and list a group" {
    run az_json iot hub consumer-group create --hub-name "$IOTHUB_NAME" -g "$RG_NAME" -n floci-cg
    assert_success
    assert_equal "$(echo "$output" | jq -r '.name')" "floci-cg"

    run az_json iot hub consumer-group show --hub-name "$IOTHUB_NAME" -g "$RG_NAME" -n floci-cg
    assert_success
    assert_equal "$(echo "$output" | jq -r '.type')" "Microsoft.Devices/IotHubs/EventHubEndpoints/ConsumerGroups"
    assert [ -n "$(echo "$output" | jq -r '.properties.created // empty')" ]

    assert_equal "$(has_group floci-cg)" "1"
    az iot hub consumer-group delete --hub-name "$IOTHUB_NAME" -g "$RG_NAME" -n floci-cg -o none
}

@test "az iot hub consumer-group: a hub update keeps its groups" {
    az iot hub consumer-group create --hub-name "$IOTHUB_NAME" -g "$RG_NAME" -n floci-cg-kept -o none

    run az iot hub update -n "$IOTHUB_NAME" -g "$RG_NAME" --retention-day 2 -o none
    assert_success

    assert_equal "$(has_group floci-cg-kept)" "1"
    az iot hub consumer-group delete --hub-name "$IOTHUB_NAME" -g "$RG_NAME" -n floci-cg-kept -o none
}

@test "az iot hub consumer-group: delete removes the group" {
    az iot hub consumer-group create --hub-name "$IOTHUB_NAME" -g "$RG_NAME" -n floci-cg-gone -o none

    run az iot hub consumer-group delete --hub-name "$IOTHUB_NAME" -g "$RG_NAME" -n floci-cg-gone -o none
    assert_success

    run az iot hub consumer-group show --hub-name "$IOTHUB_NAME" -g "$RG_NAME" -n floci-cg-gone -o none
    assert_failure
    assert_equal "$(has_group floci-cg-gone)" "0"
    assert_equal "$(has_group '$Default')" "1"
}

@test "az iot hub list-skus: an S1 hub lists the Standard SKUs" {
    run az_json iot hub list-skus -n "$IOTHUB_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '[.[].sku.name] | join(",")')" "S1,S2,S3"
    assert_equal "$(echo "$output" | jq -r '.[2].capacity.maximum')" "10"
}

@test "az iot hub show-quota-metrics: the S1 quotas with nothing counted" {
    run az_json iot hub show-quota-metrics -n "$IOTHUB_NAME" -g "$RG_NAME"
    assert_success
    # The CLI itself prints TotalDeviceCount's maxValue as Unlimited, whatever the service answers.
    assert_equal "$(echo "$output" | jq -r '[.[] | "\(.name)=\(.currentValue)/\(.maxValue)"] | join(",")')" \
        "TotalMessages=0/400000,TotalDeviceCount=0/Unlimited"
}

@test "az iot hub show-stats: no devices registered" {
    run az_json iot hub show-stats -n "$IOTHUB_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.totalDeviceCount')" "0"
    assert_equal "$(echo "$output" | jq -r '.enabledDeviceCount')" "0"
    assert_equal "$(echo "$output" | jq -r '.disabledDeviceCount')" "0"
}

# az iot hub device-identity (the azure-iot extension): the service API on the hub's host name, signed with the
# iothubowner key the extension reads through listkeys. Each test removes the devices it creates.

device() {
    az_json iot hub device-identity show -n "$IOTHUB_NAME" -g "$RG_NAME" -d "$1"
}

# The devices a test creates. teardown deletes them, so a test that fails part way leaves no device behind to
# break the counts the next tests check. A test records a device before creating it: `run` creates in a subshell.
CREATED_DEVICES=()

new_device() {
    CREATED_DEVICES+=("$1")
    az iot hub device-identity create -n "$IOTHUB_NAME" -g "$RG_NAME" -d "$@" -o none
}

teardown() {
    local id
    for id in "${CREATED_DEVICES[@]}"; do
        az iot hub device-identity delete -n "$IOTHUB_NAME" -g "$RG_NAME" -d "$id" -o none 2>/dev/null || true
    done
}

@test "az iot hub device-identity: create generates keys, and show answers the device" {
    CREATED_DEVICES+=(floci-dev-1)
    run az_json iot hub device-identity create -n "$IOTHUB_NAME" -g "$RG_NAME" -d floci-dev-1
    assert_success
    assert_equal "$(echo "$output" | jq -r '.deviceId')" "floci-dev-1"
    assert_equal "$(echo "$output" | jq -r '.status')" "enabled"
    assert_equal "$(echo "$output" | jq -r '.authentication.type')" "sas"
    local primary
    primary="$(echo "$output" | jq -r '.authentication.symmetricKey.primaryKey')"
    assert [ -n "$primary" ] && assert [ "$primary" != "null" ]

    run device floci-dev-1
    assert_success
    assert_equal "$(echo "$output" | jq -r '.authentication.symmetricKey.primaryKey')" "$primary"
    assert_equal "$(echo "$output" | jq -r '.connectionState')" "Disconnected"

    run az_json iot hub device-identity connection-string show -n "$IOTHUB_NAME" -g "$RG_NAME" -d floci-dev-1
    assert_success
    assert_equal "$(echo "$output" | jq -r '.connectionString')" \
        "HostName=${IOTHUB_NAME}.azure-devices.net;DeviceId=floci-dev-1;SharedAccessKey=${primary}"

    run az iot hub device-identity create -n "$IOTHUB_NAME" -g "$RG_NAME" -d floci-dev-1 -o none
    assert_failure
}

@test "az iot hub device-identity: update disables a device, and show-stats counts it" {
    new_device floci-dev-off

    run az_json iot hub device-identity update -n "$IOTHUB_NAME" -g "$RG_NAME" -d floci-dev-off \
        --set status=disabled statusReason=lost
    assert_success
    assert_equal "$(echo "$output" | jq -r '.status')" "disabled"
    assert_equal "$(device floci-dev-off | jq -r '.statusReason')" "lost"

    run az_json iot hub show-stats -n "$IOTHUB_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.totalDeviceCount')" "1"
    assert_equal "$(echo "$output" | jq -r '.disabledDeviceCount')" "1"
}

@test "az iot hub device-identity: renew-key renews the key asked for, and swap swaps them" {
    new_device floci-dev-keys
    local before primary secondary
    before="$(device floci-dev-keys)"
    primary="$(echo "$before" | jq -r '.authentication.symmetricKey.primaryKey')"
    secondary="$(echo "$before" | jq -r '.authentication.symmetricKey.secondaryKey')"

    run az_json iot hub device-identity renew-key -n "$IOTHUB_NAME" -g "$RG_NAME" -d floci-dev-keys --kt primary
    assert_success
    assert [ "$(echo "$output" | jq -r '.authentication.symmetricKey.primaryKey')" != "$primary" ]
    assert_equal "$(echo "$output" | jq -r '.authentication.symmetricKey.secondaryKey')" "$secondary"

    primary="$(device floci-dev-keys | jq -r '.authentication.symmetricKey.primaryKey')"
    run az_json iot hub device-identity renew-key -n "$IOTHUB_NAME" -g "$RG_NAME" -d floci-dev-keys --kt swap
    assert_success
    assert_equal "$(echo "$output" | jq -r '.authentication.symmetricKey.primaryKey')" "$secondary"
    assert_equal "$(echo "$output" | jq -r '.authentication.symmetricKey.secondaryKey')" "$primary"
}

@test "az iot hub device-identity: list answers every device, and --edge-enabled the edge ones" {
    new_device floci-dev-leaf
    new_device floci-dev-edge --edge-enabled

    run az_json iot hub device-identity list -n "$IOTHUB_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '[.[].deviceId] | sort | join(",")')" "floci-dev-edge,floci-dev-leaf"

    run az_json iot hub device-identity list -n "$IOTHUB_NAME" -g "$RG_NAME" --edge-enabled
    assert_success
    assert_equal "$(echo "$output" | jq -r '[.[].deviceId] | join(",")')" "floci-dev-edge"
    assert_equal "$(echo "$output" | jq -r '.[0].capabilities.iotEdge')" "true"
}

@test "az iot hub device-identity: delete removes the device" {
    az iot hub device-identity create -n "$IOTHUB_NAME" -g "$RG_NAME" -d floci-dev-gone -o none

    run az iot hub device-identity delete -n "$IOTHUB_NAME" -g "$RG_NAME" -d floci-dev-gone -o none
    assert_success

    run az iot hub device-identity show -n "$IOTHUB_NAME" -g "$RG_NAME" -d floci-dev-gone -o none
    assert_failure
    assert_output --partial "DeviceNotFound"
}

@test "az iot hub: delete removes the hub" {
    run az iot hub delete -n "$IOTHUB_NAME" -g "$RG_NAME" -o none
    assert_success

    run az iot hub show -n "$IOTHUB_NAME" -g "$RG_NAME" -o none
    assert_failure
}
