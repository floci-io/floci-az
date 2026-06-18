#!/usr/bin/env bash
set -euo pipefail

# Derive host:port from FLOCI_AZ_ENDPOINT (strip scheme and trailing slash).
_raw="${FLOCI_AZ_ENDPOINT#http://}"
_raw="${_raw#https://}"
FLOCI_AZ_HOST="${_raw%/}"
HTTP_BASE="http://${FLOCI_AZ_HOST}"
# MSAL refuses a non-HTTPS authority, so the Entra/AAD + ARM endpoints must be HTTPS.
# floci-az serves both HTTP and HTTPS on the same port via its protocol-sniffing proxy.
HTTPS_BASE="https://${FLOCI_AZ_HOST}"

# Install floci-az's self-signed TLS cert into the system trust store so the az CLI /
# MSAL trust the HTTPS endpoints. Fetch over HTTP since the port accepts both protocols.
# Trust-store layout differs by distro (Azure Linux/RHEL: update-ca-trust; Debian:
# update-ca-certificates), so handle both.
install_ca() {
    local crt="$1"
    if command -v update-ca-trust >/dev/null 2>&1; then
        cp "$crt" /etc/pki/ca-trust/source/anchors/floci-az.crt && update-ca-trust extract
    elif command -v update-ca-certificates >/dev/null 2>&1; then
        cp "$crt" /usr/local/share/ca-certificates/floci-az.crt && update-ca-certificates
    fi
}

echo "Waiting for floci-az and installing TLS certificate..."
for i in $(seq 1 30); do
    if curl -sf "${HTTP_BASE}/_floci/tls-cert" -o /tmp/floci-az.crt 2>/dev/null; then
        install_ca /tmp/floci-az.crt 2>/dev/null || true
        echo "TLS certificate installed."
        break
    fi
    echo "  Attempt $i/30: floci-az not ready, retrying..."
    sleep 2
done

# The az CLI (Python/requests) verifies TLS against its own CA bundle, not the system
# trust store. Build a combined bundle = existing trust + floci-az cert, and point
# requests at it so HTTPS calls to the custom cloud are trusted.
COMBINED_CA=/tmp/floci-ca-bundle.pem
: > "$COMBINED_CA"
for src in \
    "$(python3 -c 'import certifi; print(certifi.where())' 2>/dev/null || true)" \
    /etc/pki/tls/certs/ca-bundle.crt \
    /etc/ssl/certs/ca-certificates.crt; do
    if [ -n "$src" ] && [ -f "$src" ]; then cat "$src" >> "$COMBINED_CA"; fi
done
cat /tmp/floci-az.crt >> "$COMBINED_CA"
export REQUESTS_CA_BUNDLE="$COMBINED_CA"
export SSL_CERT_FILE="$COMBINED_CA"

# *.vault.azure.net data-plane interception: map the test vault DNS to loopback and
# forward 443 to floci-az, mirroring the terraform suite so `az keyvault secret` works.
echo "127.0.0.1 floci-test-kv.vault.azure.net" >> /etc/hosts
socat TCP-LISTEN:443,bind=127.0.0.1,fork,reuseaddr TCP:"${FLOCI_AZ_HOST}" &
sleep 1

# Register a custom cloud pointing every control-plane endpoint at floci-az, then log in
# as the dev service principal. The login exercises floci-az's Entra OAuth token endpoint.
# Quiet az/urllib3 warnings so they don't pollute JSON output captured by the tests.
export AZURE_CLI_DISABLE_CONNECTION_VERIFICATION=1
export AZURE_CORE_ONLY_SHOW_ERRORS=true
export PYTHONWARNINGS=ignore

az cloud register -n floci-az \
    --endpoint-resource-manager                   "${HTTPS_BASE}/" \
    --endpoint-active-directory                   "${HTTPS_BASE}" \
    --endpoint-active-directory-resource-id       "${HTTPS_BASE}/" \
    --endpoint-active-directory-graph-resource-id "${HTTPS_BASE}/" \
    --suffix-storage-endpoint                     "core.windows.net" \
    --suffix-keyvault-dns                         ".vault.azure.net" \
    --suffix-acr-login-server-endpoint            ".azurecr.io" \
    2>/dev/null || az cloud update -n floci-az \
    --endpoint-resource-manager                   "${HTTPS_BASE}/" \
    --endpoint-active-directory                   "${HTTPS_BASE}" \
    --endpoint-active-directory-resource-id       "${HTTPS_BASE}/" \
    --endpoint-active-directory-graph-resource-id "${HTTPS_BASE}/" \
    --suffix-storage-endpoint                     "core.windows.net" \
    --suffix-keyvault-dns                         ".vault.azure.net"

az cloud set -n floci-az

# floci-az is not a public Azure instance, so disable MSAL instance discovery — otherwise
# the login rejects the authority with "invalid_instance".
az config set core.instance_discovery=false 2>/dev/null || true

echo "Logging in as service principal ${AZURE_CLIENT_ID} (tenant ${AZURE_TENANT_ID})..."
if ! az login --service-principal \
        -u "${AZURE_CLIENT_ID}" \
        -p "${AZURE_CLIENT_SECRET}" \
        --tenant "${AZURE_TENANT_ID}" \
        -o none 2>login-err.txt; then
    echo "Standard login failed; retrying with --allow-no-subscriptions:" >&2
    cat login-err.txt >&2
    az login --service-principal \
        -u "${AZURE_CLIENT_ID}" \
        -p "${AZURE_CLIENT_SECRET}" \
        --tenant "${AZURE_TENANT_ID}" \
        --allow-no-subscriptions -o none
fi
az account set --subscription "${AZURE_SUBSCRIPTION_ID}" 2>/dev/null || true

report_dir="$(mktemp -d /tmp/bats-junit-XXXXXX)"
trap 'rm -rf "$report_dir"' EXIT

set +e
/opt/bats-core/bin/bats --report-formatter junit -o "$report_dir" test/
status=$?
set -e

if [ -f "$report_dir/report.xml" ]; then
    mv "$report_dir/report.xml" /results/junit.xml
fi

exit "$status"
