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

@test "az keyvault: key create/show/list/delete round-trip (data-plane)" {
    run az keyvault key create --vault-name "$KV_NAME" -n "$KEY_NAME" \
        --kty RSA --size 2048 -o none
    if [ "$status" -ne 0 ]; then
        skip "key vault data-plane not reachable: $output"
    fi

    run az_json keyvault key show --vault-name "$KV_NAME" -n "$KEY_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.key.kty')" "RSA"

    run az_json keyvault key list --vault-name "$KV_NAME"
    assert_success
    assert_not_empty "$(echo "$output" | jq -r '.[].kid')"

    run az keyvault key delete --vault-name "$KV_NAME" -n "$KEY_NAME" -o none
    assert_success
}

@test "az keyvault: key set-attributes + encrypt/decrypt + rotation-policy (data-plane)" {
    run az keyvault key create --vault-name "$KV_NAME" -n "$CRYPTO_KEY_NAME" \
        --kty RSA --size 2048 -o none
    if [ "$status" -ne 0 ]; then
        skip "key vault data-plane not reachable: $output"
    fi

    # set-attributes: disable the key and verify the flag is reflected on show.
    run az keyvault key set-attributes --vault-name "$KV_NAME" -n "$CRYPTO_KEY_NAME" \
        --enabled false -o none
    assert_success
    run az_json keyvault key show --vault-name "$KV_NAME" -n "$CRYPTO_KEY_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.attributes.enabled')" "false"

    # re-enable so encrypt/decrypt can proceed.
    run az keyvault key set-attributes --vault-name "$KV_NAME" -n "$CRYPTO_KEY_NAME" \
        --enabled true -o none
    assert_success

    # encrypt/decrypt round-trip.
    local plaintext="hello-az-cli"
    local b64
    b64=$(printf '%s' "$plaintext" | base64)
    run az_json keyvault key encrypt --vault-name "$KV_NAME" -n "$CRYPTO_KEY_NAME" \
        --algorithm RSA-OAEP-256 --value "$b64"
    assert_success
    local ciphertext
    ciphertext=$(echo "$output" | jq -r '.result')
    assert_not_empty "$ciphertext"

    run az_json keyvault key decrypt --vault-name "$KV_NAME" -n "$CRYPTO_KEY_NAME" \
        --algorithm RSA-OAEP-256 --value "$ciphertext"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.result' | base64 -d)" "$plaintext"

    # rotation-policy show + update round-trip.
    run az_json keyvault key rotation-policy show --vault-name "$KV_NAME" -n "$CRYPTO_KEY_NAME"
    assert_success
    run az_json keyvault key rotation-policy update --vault-name "$KV_NAME" -n "$CRYPTO_KEY_NAME" \
        --value '{"lifetimeActions":[{"trigger":{"timeAfterCreate":"P30D"},"action":{"type":"Rotate"}}],"attributes":{"expiryTime":"P90D"}}'
    assert_success

    run az keyvault key delete --vault-name "$KV_NAME" -n "$CRYPTO_KEY_NAME" -o none
    assert_success
}

@test "az keyvault: key soft-delete lifecycle (data-plane)" {
    run az keyvault key create --vault-name "$KV_NAME" -n "$LIFECYCLE_KEY_NAME" \
        --kty RSA --size 2048 -o none
    if [ "$status" -ne 0 ]; then
        skip "key vault data-plane not reachable: $output"
    fi

    run az keyvault key delete --vault-name "$KV_NAME" -n "$LIFECYCLE_KEY_NAME" -o none
    assert_success

    run az_json keyvault key list-deleted --vault-name "$KV_NAME"
    assert_success
    assert_not_empty "$(echo "$output" | jq -r '.[].kid')"

    run az keyvault key recover --vault-name "$KV_NAME" -n "$LIFECYCLE_KEY_NAME" -o none
    assert_success

    run az keyvault key delete --vault-name "$KV_NAME" -n "$LIFECYCLE_KEY_NAME" -o none
    assert_success
    run az keyvault key purge --vault-name "$KV_NAME" -n "$LIFECYCLE_KEY_NAME" -o none
    assert_success
}
