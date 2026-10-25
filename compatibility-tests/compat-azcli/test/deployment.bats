#!/usr/bin/env bats
# ARM template deployments: az deployment group validate / create / show / export / delete.
# The az CLI validates first, splices the template file's raw text (comments included) into the body,
# and reads a 201 whose provisioningState is already Succeeded as complete.

DEPLOY_RG="floci-test-deploy-rg"
DEPLOY_NAME="floci-test-deployment"
DEPLOY_SA="flocitestazdeploysa"

setup_file() {
    load 'test_helper/common-setup'

    az group create -n "$DEPLOY_RG" -l "$LOCATION" -o none
    TEMPLATE_FILE="$(mktemp --suffix .json)"
    export TEMPLATE_FILE
    cat > "$TEMPLATE_FILE" <<'JSON'
{
  // a comment, as template files often carry
  "$schema": "https://schema.management.azure.com/schemas/2019-04-01/deploymentTemplate.json#",
  "contentVersion": "1.0.0.0",
  "parameters": {
    "accountName": {"type": "string"},
    "location": {"type": "string", "defaultValue": "[resourceGroup().location]"}
  },
  "resources": [
    {
      "type": "Microsoft.Storage/storageAccounts",
      "apiVersion": "2023-01-01",
      "name": "[parameters('accountName')]",
      "location": "[parameters('location')]",
      "kind": "StorageV2",
      "sku": {"name": "Standard_LRS"}
    }
  ],
  "outputs": {
    "accountId": {"type": "string", "value": "[resourceId('Microsoft.Storage/storageAccounts', parameters('accountName'))]"},
    "blobEndpoint": {"type": "string", "value": "[reference(parameters('accountName')).primaryEndpoints.blob]"}
  }
}
JSON
}

setup() {
    load 'test_helper/common-setup'
}

teardown_file() {
    az group delete -n "$DEPLOY_RG" --yes -o none 2>/dev/null || true
    az group delete -n floci-test-sub-deploy-rg --yes -o none 2>/dev/null || true
    rm -f "$TEMPLATE_FILE"
}

@test "az deployment group validate: succeeds without creating anything" {
    run az_json deployment group validate -g "$DEPLOY_RG" --template-file "$TEMPLATE_FILE" \
        --parameters accountName="$DEPLOY_SA"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.properties.provisioningState')" "Succeeded"
    run az storage account show -n "$DEPLOY_SA" -g "$DEPLOY_RG" -o none
    assert_failure
}

@test "az deployment group create: deploys the storage account and answers its outputs" {
    run az_json deployment group create -g "$DEPLOY_RG" -n "$DEPLOY_NAME" --template-file "$TEMPLATE_FILE" \
        --parameters accountName="$DEPLOY_SA"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.properties.provisioningState')" "Succeeded"
    assert_equal "$(echo "$output" | jq -r '.properties.outputs.accountId.value')" \
        "/subscriptions/${SUB_ID}/resourceGroups/${DEPLOY_RG}/providers/Microsoft.Storage/storageAccounts/${DEPLOY_SA}"
    assert_output --partial "${DEPLOY_SA}.blob"

    run az_json storage account show -n "$DEPLOY_SA" -g "$DEPLOY_RG"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.location')" "$LOCATION"
}

@test "az deployment group show, list and export read the deployment back" {
    run az_json deployment group show -g "$DEPLOY_RG" -n "$DEPLOY_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.properties.parameters.accountName.value')" "$DEPLOY_SA"

    run az_json deployment group list -g "$DEPLOY_RG"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.[0].name')" "$DEPLOY_NAME"

    run az_json deployment group export -g "$DEPLOY_RG" -n "$DEPLOY_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.contentVersion')" "1.0.0.0"
}

@test "az deployment operation group list: one Create per resource and the output evaluation" {
    run az_json deployment operation group list -g "$DEPLOY_RG" -n "$DEPLOY_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '[.[] | select(.properties.provisioningOperation == "Create")] | length')" "1"
    assert_equal "$(echo "$output" | jq -r '.[0].properties.targetResource.resourceName')" "$DEPLOY_SA"
    assert_equal "$(echo "$output" | jq -r '.[0].properties.provisioningState')" "Succeeded"
    assert_equal "$(echo "$output" | jq -r '.[-1].properties.provisioningOperation')" "EvaluateDeploymentOutput"
}

@test "az deployment group delete: removes the deployment and keeps its resources" {
    run az deployment group delete -g "$DEPLOY_RG" -n "$DEPLOY_NAME" -o none
    assert_success

    run az deployment group show -g "$DEPLOY_RG" -n "$DEPLOY_NAME" -o none
    assert_failure
    run az storage account show -n "$DEPLOY_SA" -g "$DEPLOY_RG" -o none
    assert_success
}

@test "az deployment sub create: a subscription-scope template creates a resource group" {
    local sub_template
    sub_template="$(mktemp --suffix .json)"
    cat > "$sub_template" <<'JSON'
{
  "$schema": "https://schema.management.azure.com/schemas/2018-05-01/subscriptionDeploymentTemplate.json#",
  "contentVersion": "1.0.0.0",
  "parameters": {"rgName": {"type": "string"}},
  "resources": [
    {"type": "Microsoft.Resources/resourceGroups", "apiVersion": "2021-04-01",
     "name": "[parameters('rgName')]", "location": "westeurope", "tags": {"made": "sub"}}
  ],
  "outputs": {
    "rgId": {"type": "string", "value": "[subscriptionResourceId('Microsoft.Resources/resourceGroups', parameters('rgName'))]"}
  }
}
JSON
    run az_json deployment sub create -n floci-test-sub-deployment -l "$LOCATION" --template-file "$sub_template" \
        --parameters rgName=floci-test-sub-deploy-rg
    rm -f "$sub_template"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.properties.provisioningState')" "Succeeded"

    run az_json group show -n floci-test-sub-deploy-rg
    assert_success
    assert_equal "$(echo "$output" | jq -r '.location')" "westeurope"
    assert_equal "$(echo "$output" | jq -r '.tags.made')" "sub"
}
