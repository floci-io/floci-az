#!/usr/bin/env bats
# ACR + Redis: management-plane create/show via az/ARM.

setup_file() {
    load 'test_helper/common-setup'

    az group create -n "$RG_NAME" -l "$LOCATION" -o none
    az acr create -n "$ACR_NAME" -g "$RG_NAME" -l "$LOCATION" --sku Basic -o none
    az redis create -n "$REDIS_NAME" -g "$RG_NAME" -l "$LOCATION" \
        --sku Basic --vm-size c0 -o none
}

setup() {
    load 'test_helper/common-setup'
}

@test "az acr: created with login server" {
    run az_json acr show -n "$ACR_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.name')" "$ACR_NAME"
    assert_output --partial "loginServer"
}

@test "az redis: created with host name" {
    run az_json redis show -n "$REDIS_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.name')" "$REDIS_NAME"
    assert_output --partial "hostName"
}
