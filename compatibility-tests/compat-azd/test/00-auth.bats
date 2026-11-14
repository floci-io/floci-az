#!/usr/bin/env bats

setup() {
    load 'test_helper/common-setup'
}

@test "azd: reports the pinned version" {
    run azd version
    assert_success
    assert_output --partial "1.36.0"
}

@test "azd: logs in as a service principal against floci-az" {
    run azd auth login --client-id "${AZURE_CLIENT_ID}" --client-secret "${AZURE_CLIENT_SECRET}" \
        --tenant-id "${AZURE_TENANT_ID}"
    assert_success
}

@test "azd: gets a token for each ARM scope it asks for" {
    for scope in "https://management.azure.com//.default" "https://management.core.windows.net//.default"; do
        run azd auth token --scope "${scope}" --output json
        assert_success
        token="$(jq -r .token <<<"${output}")"
        payload="$(cut -d. -f2 <<<"${token}" | tr '_-' '/+')"
        while (( ${#payload} % 4 )); do payload+="="; done
        claims="$(base64 -d <<<"${payload}")"
        assert_equal "$(jq -r .tid <<<"${claims}")" "${AZURE_TENANT_ID}"
        assert_equal "$(jq -r .aud <<<"${claims}")" "${scope%/.default}"
        [[ -n "$(jq -r '.oid // empty' <<<"${claims}")" ]]
    done
}
