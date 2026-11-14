#!/usr/bin/env bats
# Azure RBAC: built-in role definitions and role assignments through `az role` (#567). The principal is passed
# by object id with its type, which is the form that needs no Microsoft Graph lookup.

setup_file() {
    load 'test_helper/common-setup'

    az group create -n "$RG_NAME" -l "$LOCATION" -o none
}

setup() {
    load 'test_helper/common-setup'

    export RG_SCOPE="/subscriptions/${SUB_ID}/resourceGroups/${RG_NAME}"
    export PRINCIPAL_ID="3f2c6a1e-7d4b-4a9e-8c21-5b6d7e8f9a01"
    export READER_GUID="acdd72a7-3385-48ef-bd42-f606fba81ae7"
}

@test "az role definition list: a built-in role is found by name" {
    run az_json role definition list --name "AcrPull"
    assert_success
    assert_equal "$(echo "$output" | jq 'length')" "1"
    assert_equal "$(echo "$output" | jq -r '.[0].name')" "7f951dda-4ed3-4680-a7ca-43fe172d538d"
    assert_equal "$(echo "$output" | jq -r '.[0].roleType')" "BuiltInRole"
}

@test "az role assignment create: Reader on the resource group, by object id" {
    run az_json role assignment create --assignee-object-id "$PRINCIPAL_ID" \
        --assignee-principal-type ServicePrincipal --role Reader --scope "$RG_SCOPE"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.principalId')" "$PRINCIPAL_ID"
    assert_equal "$(echo "$output" | jq -r '.principalType')" "ServicePrincipal"
    assert_equal "$(echo "$output" | jq -r '.scope')" "$RG_SCOPE"
    assert_equal "$(echo "$output" | jq -r '.roleDefinitionId | split("/") | last')" "$READER_GUID"
}

@test "az role assignment create: the same grant again succeeds, Azure's 409 RoleAssignmentExists" {
    run az_json role assignment create --assignee-object-id "$PRINCIPAL_ID" \
        --assignee-principal-type ServicePrincipal --role Reader --scope "$RG_SCOPE"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.principalId')" "$PRINCIPAL_ID"
}

@test "az role assignment list: the assignment is listed with its role name" {
    run az_json role assignment list --scope "$RG_SCOPE" --fill-principal-name false
    assert_success
    assert_equal "$(echo "$output" | jq "[.[] | select(.principalId == \"$PRINCIPAL_ID\")] | length")" "1"
    assert_equal "$(echo "$output" | jq -r ".[] | select(.principalId == \"$PRINCIPAL_ID\") | .roleDefinitionName")" "Reader"
}

@test "az role assignment delete: by id, then the scope lists none" {
    id=$(az role assignment list --scope "$RG_SCOPE" --fill-principal-name false \
        --query "[?principalId=='$PRINCIPAL_ID'].id | [0]" -o tsv)
    [ -n "$id" ]
    run az role assignment delete --ids "$id"
    assert_success

    run az_json role assignment list --scope "$RG_SCOPE" --fill-principal-name false
    assert_success
    assert_equal "$(echo "$output" | jq "[.[] | select(.principalId == \"$PRINCIPAL_ID\")] | length")" "0"
}
