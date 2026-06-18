#!/usr/bin/env bats
# Validates that `az login --service-principal` (performed in the entrypoint) succeeded
# against floci-az's Entra token endpoint, and that the CLI can mint access tokens.

setup() {
    load 'test_helper/common-setup'
}

@test "az: active cloud is floci-az" {
    run az_json cloud show
    assert_success
    assert_equal "$(echo "$output" | jq -r '.name')" "floci-az"
}

@test "az: logged in as the dev service principal tenant" {
    run az_json account show
    assert_success
    assert_equal "$(echo "$output" | jq -r '.tenantId')" "$TENANT_ID"
}

@test "az: can acquire an access token (Entra token endpoint)" {
    run az_json account get-access-token
    assert_success
    local token
    token=$(echo "$output" | jq -r '.accessToken')
    [ -n "$token" ]
    # RS256 JWT: three base64url segments.
    [[ "$token" =~ ^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$ ]]
}
