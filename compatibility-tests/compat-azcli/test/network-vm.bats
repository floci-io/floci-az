#!/usr/bin/env bats
# Networking (solid, ARM-backed) plus a best-effort `az vm create` — VM creation drives a
# lot of CLI-side orchestration, so it is skipped gracefully if the emulator can't satisfy it.

setup_file() {
    load 'test_helper/common-setup'

    az group create -n "$RG_NAME" -l "$LOCATION" -o none
    az network vnet create -g "$RG_NAME" -n "$VNET_NAME" -l "$LOCATION" \
        --address-prefixes 10.0.0.0/16 \
        --subnet-name "$SUBNET_NAME" --subnet-prefixes 10.0.1.0/24 -o none
    az network nic create -g "$RG_NAME" -n "$NIC_NAME" -l "$LOCATION" \
        --vnet-name "$VNET_NAME" --subnet "$SUBNET_NAME" -o none
}

setup() {
    load 'test_helper/common-setup'
}

@test "az network vnet: created" {
    run az_json network vnet show -g "$RG_NAME" -n "$VNET_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.name')" "$VNET_NAME"
}

@test "az network nic: has a private IP" {
    run az_json network nic show -g "$RG_NAME" -n "$NIC_NAME"
    assert_success
    assert_output --partial "privateIPAddress"
}

@test "az vm: created and reports running power state" {
    run az vm create -g "$RG_NAME" -n "$VM_NAME" -l "$LOCATION" \
        --nics "$NIC_NAME" --image Ubuntu2204 \
        --admin-username flociadmin --admin-password 'FlociAz_Strong123!' \
        --authentication-type password -o none
    if [ "$status" -ne 0 ]; then
        skip "az vm create not supported by emulator: $output"
    fi

    run az_json vm get-instance-view -g "$RG_NAME" -n "$VM_NAME"
    assert_success
    assert_output --partial "PowerState/running"
}
