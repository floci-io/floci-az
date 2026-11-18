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

# One value of the selected environment, as azd env get-values prints it.
env_value() {
    azd env get-values | sed -n "s/^$1=\"\\(.*\\)\"$/\\1/p"
}

@test "azd provision (bicep): also deploys a Log Analytics workspace and an API Management service" {
    workspace="$(env_value LOG_WORKSPACE)"
    apim="$(env_value APIM_SERVICE)"
    [[ -n "${workspace}" && -n "${apim}" ]]
    [[ "$(arm_status "subscriptions/${SUB_ID}/resourceGroups/${GROUP}/providers/Microsoft.OperationalInsights/workspaces/${workspace}?api-version=2022-10-01")" == "200" ]]
    [[ "$(arm_status "subscriptions/${SUB_ID}/resourceGroups/${GROUP}/providers/Microsoft.ApiManagement/service/${apim}?api-version=2022-08-01")" == "200" ]]
}

@test "azd down --purge: deletes the group and records the void deployment" {
    workspace="$(env_value LOG_WORKSPACE)"
    apim="$(env_value APIM_SERVICE)"
    run azd down --force --purge --no-prompt
    assert_success
    # The workspace was force-deleted and the API Management service purged, so neither is left soft-deleted.
    run bash -c "curl -sS -H 'Authorization: Bearer $(arm_token)' \
        'https://management.azure.com/subscriptions/${SUB_ID}/providers/Microsoft.OperationalInsights/deletedWorkspaces?api-version=2022-10-01' \
        | jq -r '.value[].name'"
    assert_success
    refute_output --partial "${workspace}"
    [[ "$(arm_status "subscriptions/${SUB_ID}/providers/Microsoft.ApiManagement/locations/${LOCATION}/deletedservices/${apim}?api-version=2022-08-01")" == "404" ]]
    [[ "$(arm_status "subscriptions/${SUB_ID}/resourceGroups/${GROUP}?api-version=2021-04-01")" == "404" ]]
    # azd records the teardown as an empty deployment tagged azd-deploy-reason=down.
    deployments="$(arm_get "subscriptions/${SUB_ID}/providers/Microsoft.Resources/deployments?api-version=2021-04-01")"
    run jq -e '[.value[] | select(.tags["azd-env-name"] == "dev" and .tags["azd-deploy-reason"] == "down")]
        | length > 0 and all(.properties.provisioningState == "Succeeded"
            and ((.properties.outputResources // []) | length == 0))' <<<"${deployments}"
    assert_success
}

@test "azd down without --purge: leaves the workspace and the API Management service soft-deleted" {
    new_project bicep keep
    run azd provision --no-prompt
    assert_success
    workspace="$(env_value LOG_WORKSPACE)"
    apim="$(env_value APIM_SERVICE)"

    run azd down --force --no-prompt
    assert_success

    deleted="subscriptions/${SUB_ID}/resourceGroups/rg-keep/providers/Microsoft.OperationalInsights/workspaces/${workspace}"
    run bash -c "curl -sS -H 'Authorization: Bearer $(arm_token)' \
        'https://management.azure.com/subscriptions/${SUB_ID}/providers/Microsoft.OperationalInsights/deletedWorkspaces?api-version=2022-10-01' \
        | jq -r '.value[].name'"
    assert_success
    assert_output --partial "${workspace}"
    apim_deleted="subscriptions/${SUB_ID}/providers/Microsoft.ApiManagement/locations/${LOCATION}/deletedservices/${apim}?api-version=2022-08-01"
    [[ "$(arm_status "${apim_deleted}")" == "200" ]]

    # Clean up as a user would: purge the service, and recover the workspace to force-delete it.
    [[ "$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE -H "Authorization: Bearer $(arm_token)" \
        "https://management.azure.com/${apim_deleted}")" == "200" ]]
    [[ "$(arm_status "${apim_deleted}")" == "404" ]]
    [[ "$(curl -sS -o /dev/null -w '%{http_code}' -X PUT -H "Authorization: Bearer $(arm_token)" \
        -H 'Content-Type: application/json' -d "{\"location\":\"${LOCATION}\"}" \
        "https://management.azure.com/subscriptions/${SUB_ID}/resourceGroups/rg-keep?api-version=2021-04-01")" =~ ^20[01]$ ]]
    [[ "$(curl -sS -o /dev/null -w '%{http_code}' -X PUT -H "Authorization: Bearer $(arm_token)" \
        -H 'Content-Type: application/json' -d "{\"location\":\"${LOCATION}\"}" \
        "https://management.azure.com/${deleted}?api-version=2022-10-01")" =~ ^20[01]$ ]]
    [[ "$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE -H "Authorization: Bearer $(arm_token)" \
        "https://management.azure.com/${deleted}?api-version=2022-10-01&force=true")" == "200" ]]
}
