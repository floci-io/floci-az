#!/usr/bin/env bats
# Resource group + storage: management-plane via az/ARM, data-plane via connection string.

setup_file() {
    load 'test_helper/common-setup'

    az group create -n "$RG_NAME" -l "$LOCATION" -o none
    az storage account create -n "$SA_NAME" -g "$RG_NAME" -l "$LOCATION" \
        --sku Standard_LRS -o none
}

setup() {
    load 'test_helper/common-setup'
}

@test "az group: created with succeeded state" {
    run az_json group show -n "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.name')" "$RG_NAME"
    assert_equal "$(echo "$output" | jq -r '.properties.provisioningState')" "Succeeded"
}

@test "az storage account: created with blob endpoint" {
    run az_json storage account show -n "$SA_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.name')" "$SA_NAME"
    assert_output --partial "primaryEndpoints"
    assert_output --partial "blob"
}

@test "az storage: container create + blob upload/download round-trip" {
    local conn body got
    conn="$(storage_conn)"
    body="$(mktemp)"; got="$(mktemp)"
    echo -n "hello-from-az-cli" > "$body"

    run az storage container create -n "$CONTAINER_NAME" --connection-string "$conn" -o none
    assert_success

    run az storage blob upload -c "$CONTAINER_NAME" -f "$body" -n "$BLOB_NAME" \
        --connection-string "$conn" --overwrite -o none
    assert_success

    az storage blob download -c "$CONTAINER_NAME" -n "$BLOB_NAME" -f "$got" \
        --connection-string "$conn" -o none
    assert_equal "$(cat "$got")" "hello-from-az-cli"

    rm -f "$body" "$got"
}
