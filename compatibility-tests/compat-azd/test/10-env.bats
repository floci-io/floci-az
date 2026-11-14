#!/usr/bin/env bats

setup() {
    load 'test_helper/common-setup'
    new_project bicep envcheck
}

@test "azd env: holds what it is given" {
    run azd env set FLOCI_MARKER hello
    assert_success
    run azd env get-values
    assert_success
    assert_output --partial 'FLOCI_MARKER="hello"'
    assert_output --partial "AZURE_SUBSCRIPTION_ID=\"${SUB_ID}\""
    assert_output --partial "AZURE_LOCATION=\"${LOCATION}\""
}

@test "azd config: round-trips a value" {
    run azd config set floci.marker yes
    assert_success
    run azd config get floci.marker
    assert_success
    assert_output --partial "yes"
}
