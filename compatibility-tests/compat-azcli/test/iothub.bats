#!/usr/bin/env bats
# Azure IoT Hub: the management plane (hub lifecycle and shared access policies) via az/ARM.

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

@test "az iot hub: delete removes the hub" {
    run az iot hub delete -n "$IOTHUB_NAME" -g "$RG_NAME" -o none
    assert_success

    run az iot hub show -n "$IOTHUB_NAME" -g "$RG_NAME" -o none
    assert_failure
}
