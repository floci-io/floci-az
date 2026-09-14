#!/usr/bin/env bats
# ACR data plane: `az acr login` performs the Entra token exchange against
# {name}.azurecr.io, and the token it returns pushes and pulls an image through the
# Docker Registry HTTP API V2 on the same host.
#
# `az acr login` without --expose-token shells out to `docker`, which this container does
# not have, so the token flow is exercised with --expose-token (the same challenge and
# /oauth2/exchange calls) and the push/pull is driven over the registry API directly.

setup_file() {
    load 'test_helper/common-setup'

    az group create -n "$RG_NAME" -l "$LOCATION" -o none
    az acr create -n "$ACR_NAME" -g "$RG_NAME" -l "$LOCATION" --sku Basic -o none
}

setup() {
    load 'test_helper/common-setup'

    export LOGIN_SERVER="${ACR_NAME}.azurecr.io"
    export REPO="compat/app"
    export CA_BUNDLE=/tmp/floci-az.crt

    if [ ! -f "$CA_BUNDLE" ]; then
        skip "floci-az TLS certificate was not fetched; the registry host cannot be verified"
    fi
}

# base64url payload of a JWT, on stdin, decoded to stdout.
decode_base64url() {
    python3 -c 'import base64, sys; s = sys.stdin.read().strip(); sys.stdout.write(base64.urlsafe_b64decode(s + "=" * (-len(s) % 4)).decode())'
}

# curl against the registry host, verifying floci-az's own certificate: the generated cert
# must carry *.azurecr.io for this to succeed.
registry_curl() {
    curl -sS --cacert "$CA_BUNDLE" "$@"
}

# An ACR refresh token from `az acr login`, exchanged for an access token scoped to $REPO.
acr_access_token() {
    local refresh_token
    refresh_token="$(az acr login -n "$ACR_NAME" --expose-token -o json 2>/dev/null | jq -r '.refreshToken')"
    [ -n "$refresh_token" ] && [ "$refresh_token" != "null" ] || return 1

    registry_curl -X POST "https://${LOGIN_SERVER}/oauth2/token" \
        --data-urlencode "grant_type=refresh_token" \
        --data-urlencode "service=${LOGIN_SERVER}" \
        --data-urlencode "scope=repository:${REPO}:pull,push" \
        --data-urlencode "refresh_token=${refresh_token}" | jq -r '.access_token'
}

@test "az acr: loginServer is the Azure host name" {
    run az_json acr show -n "$ACR_NAME" -g "$RG_NAME"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.loginServer')" "$LOGIN_SERVER"
}

@test "acr data plane: GET /v2/ challenges with the bearer realm and service" {
    run registry_curl -o /dev/null -D - "https://${LOGIN_SERVER}/v2/"
    assert_success
    assert_output --partial "401"
    assert_output --partial "realm=\"https://${LOGIN_SERVER}/oauth2/token\""
    assert_output --partial "service=\"${LOGIN_SERVER}\""
}

@test "az acr login: exchanges an Entra token for an ACR refresh token" {
    run az_json acr login -n "$ACR_NAME" --expose-token
    assert_success
    assert_equal "$(echo "$output" | jq -r '.loginServer')" "$LOGIN_SERVER"
    assert_equal "$(echo "$output" | jq -r '.username')" "00000000-0000-0000-0000-000000000000"
    # The refresh token is a JWT: clients decode it, so it must have three segments.
    # (The azure-cli image ships no awk, so count in bash.)
    IFS='.' read -ra segments <<< "$(echo "$output" | jq -r '.refreshToken')"
    assert_equal "${#segments[@]}" "3"
}

@test "acr data plane: the refresh token buys a scoped access token" {
    run acr_access_token
    assert_success
    # The Azure CLI decodes this token and reads its access claim when verifying permissions.
    claims="$(echo "$output" | cut -d. -f2 | decode_base64url)"
    assert_equal "$(echo "$claims" | jq -r '.access[0].name')" "$REPO"
    assert_equal "$(echo "$claims" | jq -r '.access[0].actions | join(",")')" "pull,push"
}

@test "acr data plane: push and pull an image through the login server" {
    token="$(acr_access_token)"
    [ -n "$token" ] && [ "$token" != "null" ] || fail "could not obtain an ACR access token"
    auth=(-H "Authorization: Bearer ${token}")

    config='{}'
    digest="sha256:$(printf '%s' "$config" | sha256sum | cut -d' ' -f1)"

    # Start an upload session; the Location must come back without the internal prefix.
    location="$(registry_curl -o /dev/null -D - -X POST "${auth[@]}" \
        "https://${LOGIN_SERVER}/v2/${REPO}/blobs/uploads/" \
        | grep -i '^location:' | sed 's/^[^:]*: *//' | tr -d '\r')"
    [ -n "$location" ] || fail "no upload Location header"
    case "$location" in
        "/v2/${REPO}/blobs/uploads/"*) ;;
        *) fail "upload Location leaked the internal repository prefix: $location" ;;
    esac

    separator="?"
    case "$location" in *"?"*) separator="&" ;; esac
    run registry_curl -o /dev/null -w '%{http_code}' -X PUT "${auth[@]}" \
        -H "Content-Type: application/octet-stream" --data-binary "$config" \
        "https://${LOGIN_SERVER}${location}${separator}digest=${digest}"
    assert_success
    assert_output "201"

    manifest="{\"schemaVersion\":2,\"mediaType\":\"application/vnd.docker.distribution.manifest.v2+json\",\"config\":{\"mediaType\":\"application/vnd.docker.container.image.v1+json\",\"size\":${#config},\"digest\":\"${digest}\"},\"layers\":[]}"
    run registry_curl -o /dev/null -w '%{http_code}' -X PUT "${auth[@]}" \
        -H "Content-Type: application/vnd.docker.distribution.manifest.v2+json" \
        --data-binary "$manifest" "https://${LOGIN_SERVER}/v2/${REPO}/manifests/v1"
    assert_success
    assert_output "201"

    # Pull it back: manifest, then the config blob it references.
    run registry_curl "${auth[@]}" \
        -H "Accept: application/vnd.docker.distribution.manifest.v2+json" \
        "https://${LOGIN_SERVER}/v2/${REPO}/manifests/v1"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.config.digest')" "$digest"

    run registry_curl "${auth[@]}" "https://${LOGIN_SERVER}/v2/${REPO}/blobs/${digest}"
    assert_success
    assert_output "$config"

    run registry_curl "${auth[@]}" "https://${LOGIN_SERVER}/v2/${REPO}/tags/list"
    assert_success
    assert_equal "$(echo "$output" | jq -r '.name')" "$REPO"
    assert_output --partial "v1"

    # The shared registry stores this as {registry}/{repo}; the catalog must not say so.
    run registry_curl "${auth[@]}" "https://${LOGIN_SERVER}/v2/_catalog"
    assert_success
    assert_output --partial "\"${REPO}\""
    refute_output --partial "${ACR_NAME}/${REPO}"
}
