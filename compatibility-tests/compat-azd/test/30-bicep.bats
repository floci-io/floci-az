#!/usr/bin/env bats

setup() {
    load 'test_helper/common-setup'
    new_project bicep dev
    GROUP="rg-dev"
}

deployment_named() {
    arm_get "subscriptions/${SUB_ID}/providers/Microsoft.Resources/deployments?api-version=2021-04-01" \
        | jq -r '[.value[] | select(.tags["azd-env-name"] == "dev")] | sort_by(.properties.timestamp) | last | .name'
}

@test "azd provision --preview: reports what would change and creates nothing" {
    run azd provision --preview --no-prompt
    assert_success
    [[ "$(arm_status "subscriptions/${SUB_ID}/resourceGroups/${GROUP}?api-version=2021-04-01")" == "404" ]]
}

@test "azd provision (bicep): deploys at subscription scope, through modules, and keeps the outputs" {
    run azd provision --no-prompt
    assert_success
    [[ "$(arm_status "subscriptions/${SUB_ID}/resourceGroups/${GROUP}?api-version=2021-04-01")" == "200" ]]
    run azd env get-values
    assert_success
    assert_output --partial "AZURE_RESOURCE_GROUP=\"${GROUP}\""
    assert_output --partial 'SUBNET_RANGE="10.20.1.0/24"'
    assert_output --partial 'IDENTITY_COUNT=2'
    assert_output --partial 'STORAGE_ACCOUNT="azdstore'
}

@test "azd provision (bicep): the deployment lists the module's resources, not the module" {
    name="$(deployment_named)"
    run bash -c "curl -sS -H 'Authorization: Bearer $(arm_token)' \
        'https://management.azure.com/subscriptions/${SUB_ID}/providers/Microsoft.Resources/deployments/${name}?api-version=2021-04-01' \
        | jq -r '.properties.outputResources[].id'"
    assert_success
    assert_output --partial "/resourceGroups/${GROUP}/providers/Microsoft.Storage/storageAccounts/azdstore"
    assert_output --partial "/resourceGroups/${GROUP}/providers/Microsoft.ManagedIdentity/userAssignedIdentities/id-one"
    assert_output --partial "/resourceGroups/${GROUP}/providers/Microsoft.ManagedIdentity/userAssignedIdentities/id-two"
    refute_output --partial 'Microsoft.Resources/deployments'
}

@test "azd provision (bicep): a second run with no change is skipped" {
    run azd provision --no-prompt
    assert_success
    assert_output --partial "no changes"
}

@test "azd down --purge: deletes the group and records the void deployment" {
    run azd down --force --purge --no-prompt
    assert_success
    [[ "$(arm_status "subscriptions/${SUB_ID}/resourceGroups/${GROUP}?api-version=2021-04-01")" == "404" ]]
    # azd records the teardown as an empty deployment tagged azd-deploy-reason=down.
    deployments="$(arm_get "subscriptions/${SUB_ID}/providers/Microsoft.Resources/deployments?api-version=2021-04-01")"
    run jq -e '[.value[] | select(.tags["azd-env-name"] == "dev" and .tags["azd-deploy-reason"] == "down")]
        | length > 0 and all(.properties.provisioningState == "Succeeded"
            and ((.properties.outputResources // []) | length == 0))' <<<"${deployments}"
    assert_success
}
