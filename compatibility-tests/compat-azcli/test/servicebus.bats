#!/usr/bin/env bats
# Azure Service Bus: the Microsoft.ServiceBus management plane (namespaces, entities, authorization rules) via az/ARM.

setup_file() {
    load 'test_helper/common-setup'

    az group create -n "$RG_NAME" -l "$LOCATION" -o none
    az servicebus namespace create -n "$SB_NAMESPACE_NAME" -g "$RG_NAME" --sku Standard -o none
}

teardown_file() {
    load 'test_helper/common-setup'

    az servicebus namespace delete -n "$SB_NAMESPACE_NAME" -g "$RG_NAME" -o none 2>/dev/null || true
}

setup() {
    load 'test_helper/common-setup'
}

@test "az servicebus namespace: created Succeeded with its endpoint" {
    run az_json servicebus namespace show -n "$SB_NAMESPACE_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.name')" "$SB_NAMESPACE_NAME"
    assert_equal "$(echo "$output" | jq -r '.sku.name')" "Standard"
    assert_equal "$(echo "$output" | jq -r '.provisioningState')" "Succeeded"
    assert_equal "$(echo "$output" | jq -r '.serviceBusEndpoint')" "https://${SB_NAMESPACE_NAME}.servicebus.windows.net:443/"
}

@test "az servicebus namespace: listed in its resource group" {
    run az_json servicebus namespace list -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r --arg n "$SB_NAMESPACE_NAME" '[.[] | select(.name == $n)] | length')" "1"
}

@test "az servicebus namespace exists: a taken name is reported unavailable" {
    run az_json servicebus namespace exists --name "$SB_NAMESPACE_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.nameAvailable')" "false"
    assert_equal "$(echo "$output" | jq -r '.reason')" "NameInUse"
}

@test "az servicebus queue: created with its settings and read back" {
    run az servicebus queue create -n orders --namespace-name "$SB_NAMESPACE_NAME" -g "$RG_NAME" \
        --max-delivery-count 7 --lock-duration PT45S -o none
    assert_success

    run az_json servicebus queue show -n orders --namespace-name "$SB_NAMESPACE_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.maxDeliveryCount')" "7"
    assert_equal "$(echo "$output" | jq -r '.lockDuration')" "PT45S"
}

@test "az servicebus topic and subscription: created and listed" {
    run az servicebus topic create -n events --namespace-name "$SB_NAMESPACE_NAME" -g "$RG_NAME" -o none
    assert_success
    run az servicebus topic subscription create -n audit --topic-name events \
        --namespace-name "$SB_NAMESPACE_NAME" -g "$RG_NAME" -o none
    assert_success

    run az_json servicebus topic subscription list --topic-name events --namespace-name "$SB_NAMESPACE_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.[0].name')" "audit"
}

@test "az servicebus namespace authorization-rule keys list: the root rule has connection strings" {
    run az_json servicebus namespace authorization-rule keys list -n RootManageSharedAccessKey \
        --namespace-name "$SB_NAMESPACE_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.keyName')" "RootManageSharedAccessKey"
    assert_output --partial "SharedAccessKeyName=RootManageSharedAccessKey"
}

@test "az servicebus namespace delete: the namespace is gone" {
    run az servicebus namespace delete -n "$SB_NAMESPACE_NAME" -g "$RG_NAME" -o none
    assert_success
    run az_json servicebus namespace show -n "$SB_NAMESPACE_NAME" -g "$RG_NAME"
    assert_failure
}
