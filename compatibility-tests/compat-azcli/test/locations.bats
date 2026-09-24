#!/usr/bin/env bats
# Subscription locations: `az account list-locations` and the CLI's display-name translation, both of
# which read GET /subscriptions/{sub}/locations.

LOCATION_RG_NAME="floci-test-location-rg"

setup() {
    load 'test_helper/common-setup'
}

teardown_file() {
    load 'test_helper/common-setup'
    az group delete -n "$LOCATION_RG_NAME" --yes -o none 2>/dev/null || true
}

@test "az account list-locations: lists westus with its display name" {
    run az_json account list-locations --query "[?name=='westus'] | [0]"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.displayName')" "West US"
    assert_equal "$(echo "$output" | jq -r '.metadata.regionType')" "Physical"
}

@test "az group create: a display-name location is translated to its short name" {
    run az_json group create -n "$LOCATION_RG_NAME" -l "East US"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.location')" "eastus"
}
