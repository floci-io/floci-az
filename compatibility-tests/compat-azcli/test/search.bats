#!/usr/bin/env bats
# Azure AI Search: the management plane (service, admin keys, query keys) via az/ARM.

setup_file() {
    load 'test_helper/common-setup'

    az group create -n "$RG_NAME" -l "$LOCATION" -o none
    az search service create -n "$SEARCH_NAME" -g "$RG_NAME" -l "$LOCATION" --sku basic -o none
}

setup() {
    load 'test_helper/common-setup'
}

@test "az search service: created in the succeeded state with its endpoint" {
    run az_json search service show -n "$SEARCH_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.name')" "$SEARCH_NAME"
    assert_equal "$(echo "$output" | jq -r '.sku.name')" "basic"
    assert_equal "$(echo "$output" | jq -r '.provisioningState')" "succeeded"
    assert_equal "$(echo "$output" | jq -r '.status')" "running"
    assert_equal "$(echo "$output" | jq -r '.endpoint')" "https://${SEARCH_NAME}.search.windows.net"
}

@test "az search service: listed in its resource group" {
    run az_json search service list -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r --arg n "$SEARCH_NAME" '[.[] | select(.name == $n)] | length')" "1"
}

@test "az search service: update changes the replica count" {
    run az_json search service update -n "$SEARCH_NAME" -g "$RG_NAME" --replica-count 2
    assert_success
    assert_equal "$(echo "$output" | jq -r '.replicaCount')" "2"

    run az_json search service show -n "$SEARCH_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.replicaCount')" "2"
}

@test "az search admin-key: show returns two keys and renew replaces only the one asked for" {
    run az_json search admin-key show --service-name "$SEARCH_NAME" -g "$RG_NAME"
    assert_success
    primary="$(echo "$output" | jq -r '.primaryKey')"
    secondary="$(echo "$output" | jq -r '.secondaryKey')"
    [ -n "$primary" ] && [ "$primary" != "null" ]
    [ -n "$secondary" ] && [ "$secondary" != "null" ]
    [ "$primary" != "$secondary" ]

    run az_json search admin-key renew --service-name "$SEARCH_NAME" -g "$RG_NAME" --key-kind primary
    assert_success
    [ "$(echo "$output" | jq -r '.primaryKey')" != "$primary" ]
    assert_equal "$(echo "$output" | jq -r '.secondaryKey')" "$secondary"
}

@test "az search query-key: create, list and delete a named key" {
    run az_json search query-key create --service-name "$SEARCH_NAME" -g "$RG_NAME" -n floci-test-query-key
    assert_success
    assert_equal "$(echo "$output" | jq -r '.name')" "floci-test-query-key"
    key="$(echo "$output" | jq -r '.key')"
    [ -n "$key" ] && [ "$key" != "null" ]

    run az_json search query-key list --service-name "$SEARCH_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r --arg k "$key" '[.[] | select(.key == $k)] | length')" "1"

    run az search query-key delete --service-name "$SEARCH_NAME" -g "$RG_NAME" --key-value "$key"
    assert_success

    run az_json search query-key list --service-name "$SEARCH_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r --arg k "$key" '[.[] | select(.key == $k)] | length')" "0"
}

@test "az search service: delete removes it" {
    run az search service delete -n "$SEARCH_NAME" -g "$RG_NAME" --yes
    assert_success

    run az search service show -n "$SEARCH_NAME" -g "$RG_NAME"
    assert_failure
}
