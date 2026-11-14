#!/usr/bin/env bats

setup() {
    load 'test_helper/common-setup'
    new_project terraform tfenv
}

@test "azd provision (terraform): creates the group and hands its outputs to the environment" {
    run azd provision --no-prompt
    assert_success
    run azd env get-values
    assert_success
    assert_output --partial 'TF_RESOURCE_GROUP="rg-tf-tfenv"'
    [[ "$(arm_status "subscriptions/${SUB_ID}/resourceGroups/rg-tf-tfenv?api-version=2021-04-01")" == "200" ]]
}

@test "azd down (terraform): removes the group" {
    run azd down --force --no-prompt
    assert_success
    [[ "$(arm_status "subscriptions/${SUB_ID}/resourceGroups/rg-tf-tfenv?api-version=2021-04-01")" == "404" ]]
}
