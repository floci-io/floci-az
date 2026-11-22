#!/usr/bin/env bats
# Service Bus under azurerm 4.x (Microsoft.ServiceBus 2024-01-01): apply, then a plan with no changes.

setup_file() {
    load 'test_helper/common-setup'
    cd "$TF_DIR/azurerm4"
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true

    echo "# --- azurerm 4.x: terraform init ---" >&3
    run terraform init -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform init failed: $output" >&3
        return 1
    fi
    echo "# --- azurerm 4.x: terraform apply ---" >&3
    run terraform apply -auto-approve -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform apply failed: $output" >&3
        return 1
    fi
}

teardown_file() {
    load 'test_helper/common-setup'
    cd "$TF_DIR/azurerm4"
    terraform destroy -auto-approve -input=false -no-color >/dev/null 2>&1 || true
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true
}

setup() {
    load 'test_helper/common-setup'
}

@test "azurerm 4.x: a plan after apply shows no Service Bus changes" {
    cd "$TF_DIR/azurerm4"
    run terraform plan -detailed-exitcode -input=false -no-color
    echo "$output" | grep -E '^\s+[~+-]|must be replaced|will be updated' >&3 || true
    assert_equal "$status" "0"
}

@test "azurerm 4.x: the topic keeps the unbounded default TTL" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/floci-test-sb4-rg/providers/Microsoft.ServiceBus/namespaces/floci-test-sb4ns/topics/floci-test-sb4-topic?api-version=2024-01-01"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.properties.defaultMessageTimeToLive')" "P10675199DT2H48M5.4775807S"
}

@test "azurerm 4.x: the subscription keeps batched operations off and its idle timeout" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/floci-test-sb4-rg/providers/Microsoft.ServiceBus/namespaces/floci-test-sb4ns/topics/floci-test-sb4-topic/subscriptions/floci-test-sb4-sub?api-version=2024-01-01"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.properties.enableBatchedOperations')" "false"
    assert_equal "$(echo "$output" | jq -r '.properties.autoDeleteOnIdle')" "PT1H"
}

@test "azurerm 4.x: a rule on a Premium namespace applies" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/floci-test-sb4-rg/providers/Microsoft.ServiceBus/namespaces/floci-test-sb4prem/AuthorizationRules/floci-test-sb4-rule?api-version=2024-01-01"
    assert_success
}

@test "azurerm 4.x: the Linux consumption plan reads back as reserved with its sku" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/floci-test-web4-rg/providers/Microsoft.Web/serverFarms/floci-test-plan-y1?api-version=2023-12-01"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.properties.reserved')" "true"
    assert_equal "$(echo "$output" | jq -r '.sku.name')" "Y1"
}

@test "azurerm 4.x: the elastic premium plan keeps its maximum elastic worker count" {
    run arm_get "subscriptions/${SUB_ID}/resourceGroups/floci-test-web4-rg/providers/Microsoft.Web/serverFarms/floci-test-plan-ep1?api-version=2023-12-01"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.sku.name')" "EP1"
    assert_equal "$(echo "$output" | jq -r '.properties.maximumElasticWorkerCount')" "3"
}
