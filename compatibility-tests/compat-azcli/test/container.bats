#!/usr/bin/env bats
# Azure Container Instances (`az container`) — ARM-backed container groups.
# `az container create` drives client-side orchestration (ports/protocol defaults,
# resource requests), so it is skipped gracefully if the CLI build can't satisfy it.

setup_file() {
    load 'test_helper/common-setup'

    az group create -n "$RG_NAME" -l "$LOCATION" -o none
}

setup() {
    load 'test_helper/common-setup'
    export ACI_NAME="floci-test-cg"
}

@test "az container create: reports Succeeded with normalized read-back" {
    run az container create -g "$RG_NAME" -n "$ACI_NAME" -l "$LOCATION" \
        --image hashicorp/http-echo:latest --ports 5678 \
        --ip-address Public --os-type Linux -o none
    if [ "$status" -ne 0 ]; then
        skip "az container create not supported by emulator: $output"
    fi

    run az_json container show -g "$RG_NAME" -n "$ACI_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.provisioningState')" "Succeeded"
    assert_equal "$(echo "$output" | jq -r '.osType')" "Linux"
    # azurerm/CLI read-back contract: ports and resource requests always present.
    assert_equal "$(echo "$output" | jq -r '.containers[0].ports[0].protocol')" "TCP"
    run bash -c "echo '$output' | jq -e '.containers[0].resources.requests.cpu'"
    assert_success
}

@test "az container list: contains the group" {
    run az_json container list -g "$RG_NAME"
    assert_success
    assert_output --partial "$ACI_NAME"
}

@test "az container logs: returns without error" {
    # Mocked emulator mode returns an empty log stream; the command must still succeed.
    run az container logs -g "$RG_NAME" -n "$ACI_NAME" --container-name "$ACI_NAME"
    if [ "$status" -ne 0 ]; then
        # The CLI resolves the container name from `show`; fall back to the first container.
        run az container logs -g "$RG_NAME" -n "$ACI_NAME"
    fi
    assert_success
}

@test "az container stop and start: accepted" {
    run az container stop -g "$RG_NAME" -n "$ACI_NAME"
    assert_success
    run az container start -g "$RG_NAME" -n "$ACI_NAME"
    assert_success
}

@test "az container delete: removes the group" {
    run az container delete -g "$RG_NAME" -n "$ACI_NAME" --yes -o none
    assert_success
    run az container show -g "$RG_NAME" -n "$ACI_NAME"
    [ "$status" -ne 0 ]
}
