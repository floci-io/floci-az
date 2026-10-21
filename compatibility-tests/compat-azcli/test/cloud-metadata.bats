#!/usr/bin/env bats
# ARM_CLOUD_METADATA_URL: a fresh az profile pointed at floci-az's /metadata/endpoints discovers the emulator as
# its only cloud and makes it the active one, with no `az cloud register` or `az cloud set`, then signs in and
# calls ARM through it. The suite's own profile registers its cloud explicitly, so this runs in a profile of its
# own.

setup() {
    load 'test_helper/common-setup'
    local host="${FLOCI_AZ_ENDPOINT#http://}"
    host="${host#https://}"
    # MSAL refuses a non-HTTPS authority; the metadata answers with the scheme it is fetched over.
    HTTPS_BASE="https://${host%/}"
    PROFILE_DIR="$(mktemp -d)"
}

teardown() {
    rm -rf "$PROFILE_DIR"
}

# az in the fresh profile, discovering its clouds from floci-az.
discovered_az() {
    AZURE_CONFIG_DIR="$PROFILE_DIR" \
        ARM_CLOUD_METADATA_URL="${HTTPS_BASE}/metadata/endpoints?api-version=2019-05-01" \
        az "$@" 2>/dev/null
}

@test "ARM_CLOUD_METADATA_URL: the discovered cloud is active and points at floci-az" {
    run discovered_az cloud show -o json
    assert_success
    assert_equal "$(echo "$output" | jq -r '.name')" "floci-az"
    assert_equal "$(echo "$output" | jq -r '.isActive')" "true"
    assert_equal "$(echo "$output" | jq -r '.endpoints.resourceManager')" "${HTTPS_BASE}"
    assert_equal "$(echo "$output" | jq -r '.endpoints.activeDirectory')" "${HTTPS_BASE}/"
}

@test "ARM_CLOUD_METADATA_URL: az signs in and lists resource groups through the discovered cloud" {
    discovered_az config set core.instance_discovery=false -o none
    run discovered_az login --service-principal -u "$CLIENT_ID" -p "${AZURE_CLIENT_SECRET:-fake-secret}" \
        --tenant "$TENANT_ID" -o json
    assert_success
    run discovered_az group list -o json
    assert_success
    echo "$output" | jq -e 'type == "array"'
}
