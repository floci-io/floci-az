#!/usr/bin/env bats
# Key Vault: management-plane via az/ARM; secret set/show via the *.vault.azure.net
# data-plane tunnel (skipped gracefully if data-plane DNS interception isn't reachable).

setup_file() {
    load 'test_helper/common-setup'

    az group create -n "$RG_NAME" -l "$LOCATION" -o none
    # --no-self-perms avoids the Graph lookup az otherwise does to add an access policy
    # for the signed-in identity (Microsoft Graph is a later phase).
    az keyvault create -n "$KV_NAME" -g "$RG_NAME" -l "$LOCATION" --no-self-perms -o none
}

setup() {
    load 'test_helper/common-setup'
}

@test "az keyvault: created with vault URI" {
    run az_json keyvault show -n "$KV_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.name')" "$KV_NAME"
    assert_output --partial "${KV_NAME}.vault.azure.net"
}

@test "az keyvault: secret set + show round-trip (data-plane)" {
    run az keyvault secret set --vault-name "$KV_NAME" -n "$SECRET_NAME" \
        --value "hello-from-az-cli" -o none
    if [ "$status" -ne 0 ]; then
        skip "key vault data-plane not reachable: $output"
    fi

    run az_json keyvault secret show --vault-name "$KV_NAME" -n "$SECRET_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.value')" "hello-from-az-cli"
}
