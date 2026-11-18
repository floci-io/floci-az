#!/usr/bin/env bats

# azd deploy of a container app: azd finds the app by its azd-service-name tag, reads it, puts the values listSecrets
# answers back, and PATCHes a new revision (suffix azd-<unix seconds>) with the service's image. The image is passed
# through, so no Docker build or push runs.

PLACEHOLDER_IMAGE="mcr.microsoft.com/k8se/quickstart:latest"
SERVICE_IMAGE="docker.io/library/nginx:1.27-alpine"

setup() {
    load 'test_helper/common-setup'
    new_project containerapp aca
    GROUP="rg-aca"
}

app_path() {
    local name
    name="$(azd env get-value SERVICE_WEB_NAME)"
    echo "subscriptions/${SUB_ID}/resourceGroups/${GROUP}/providers/Microsoft.App/containerApps/${name}"
}

app_get() {
    arm_get "$(app_path)?api-version=2025-01-01"
}

@test "azd provision (container app): creates the environment and the tagged app" {
    run azd provision --no-prompt
    assert_success
    app="$(app_get)"
    run jq -r '.tags["azd-service-name"], .properties.template.containers[0].image' <<<"${app}"
    assert_line --index 0 'web'
    assert_line --index 1 "${PLACEHOLDER_IMAGE}"
}

@test "azd deploy (container app): rolls the app to the service image in a new azd revision" {
    run azd deploy web --no-prompt
    assert_success
    assert_output --partial 'https://ca-web-'
    app="$(app_get)"
    run jq -r '.properties.template.containers[0].image' <<<"${app}"
    assert_output "${SERVICE_IMAGE}"
    run jq -r '.properties.latestRevisionName' <<<"${app}"
    assert_output --regexp '^ca-web-[a-z0-9]+--azd-[0-9]+$'
    # azd sends the secrets back with the values listSecrets answered, so the secret keeps its value.
    run bash -c "curl -sS -X POST -H 'Authorization: Bearer $(arm_token)' -H 'Content-Length: 0' \
        'https://management.azure.com/$(app_path)/listSecrets?api-version=2025-01-01' | jq -r '.value[0].value'"
    assert_output 'hello-from-floci'
    run azd env get-values
    assert_output --partial "SERVICE_WEB_IMAGE_NAME=\"${SERVICE_IMAGE}\""
}

@test "azd deploy (container app): a second deploy adds another revision" {
    before="$(app_get | jq -r '.properties.latestRevisionName')"
    # azd's revision suffix has one-second granularity; two deploys in the same second would reuse the name.
    sleep 1
    run azd deploy web --no-prompt
    assert_success
    after="$(app_get | jq -r '.properties.latestRevisionName')"
    [[ "${after}" =~ --azd-[0-9]+$ ]]
    [[ "${after}" != "${before}" ]]
}

@test "azd down (container app): removes the group" {
    run azd down --force --purge --no-prompt
    assert_success
    [[ "$(arm_status "subscriptions/${SUB_ID}/resourceGroups/${GROUP}?api-version=2021-04-01")" == "404" ]]
}
