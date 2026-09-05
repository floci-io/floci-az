#!/usr/bin/env bats
# Azure Policy control plane: definitions, assignments and exemptions through `az policy`.

setup_file() {
    load 'test_helper/common-setup'

    az group create -n "$RG_NAME" -l "$LOCATION" -o none
}

setup() {
    load 'test_helper/common-setup'

    export POLICY_NAME="floci-test-policy"
    export ASSIGNMENT_NAME="floci-test-assignment"
    export EXEMPTION_NAME="floci-test-exemption"
    export POLICY_RULE='{"if":{"field":"location","notIn":["eastus"]},"then":{"effect":"deny"}}'
}

@test "az policy definition: create is Custom and shows the rule" {
    run az_json policy definition create --name "$POLICY_NAME" --display-name "Floci test policy" \
        --mode All --rules "$POLICY_RULE"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.policyType')" "Custom"
    assert_equal "$(echo "$output" | jq -r '.mode')" "All"

    run az_json policy definition show --name "$POLICY_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.name')" "$POLICY_NAME"
    assert_equal "$(echo "$output" | jq -r '.policyRule.then.effect')" "deny"
}

@test "az policy definition: listed in the subscription" {
    run az_json policy definition list --query "[?name=='$POLICY_NAME'] | length(@)"
    assert_success
    assert_output "1"
}

@test "az policy assignment: create at resource group scope" {
    run az_json policy assignment create --name "$ASSIGNMENT_NAME" --policy "$POLICY_NAME" \
        -g "$RG_NAME" --display-name "Floci test assignment"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.enforcementMode')" "Default"
    assert_output --partial "/resourceGroups/$RG_NAME"

    run az_json policy assignment list -g "$RG_NAME" --query "[?name=='$ASSIGNMENT_NAME'] | length(@)"
    assert_success
    assert_output "1"
}

@test "az policy exemption: create for the assignment and delete" {
    run az_json policy assignment show --name "$ASSIGNMENT_NAME" -g "$RG_NAME" --query id -o tsv
    assert_success
    local assignment_id="$output"

    run az_json policy exemption create --name "$EXEMPTION_NAME" -g "$RG_NAME" \
        --policy-assignment "$assignment_id" --exemption-category Waiver \
        --display-name "Floci test exemption"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.exemptionCategory')" "Waiver"

    run az policy exemption delete --name "$EXEMPTION_NAME" -g "$RG_NAME"
    assert_success
}

@test "az policy definition: cannot be deleted while assigned, then deleted after the assignment" {
    run az policy definition delete --name "$POLICY_NAME"
    assert_failure
    assert_output --partial "InvalidDeletePolicyDefinitionRequest"

    run az policy assignment delete --name "$ASSIGNMENT_NAME" -g "$RG_NAME"
    assert_success

    run az policy definition delete --name "$POLICY_NAME"
    assert_success

    run az policy definition show --name "$POLICY_NAME"
    assert_failure
    assert_output --partial "PolicyDefinitionNotFound"
}
