#!/usr/bin/env bash
set -euo pipefail

# Derive host:port from FLOCI_AZ_ENDPOINT (strip scheme and trailing slash)
_raw="${FLOCI_AZ_ENDPOINT#http://}"
_raw="${_raw#https://}"
FLOCI_AZ_HOST="${_raw%/}"

# Install TLS cert so go-azure-sdk (used by azurerm provider) trusts floci-az HTTPS.
# Fetch via HTTP since the port accepts both HTTP and HTTPS.
echo "Waiting for floci-az and installing TLS certificate..."
for i in $(seq 1 30); do
    if curl -sf "http://${FLOCI_AZ_HOST}/_floci/tls-cert" \
            -o /usr/local/share/ca-certificates/floci-az.crt 2>/dev/null; then
        update-ca-certificates 2>/dev/null
        echo "TLS certificate installed."
        break
    fi
    echo "  Attempt $i/30: floci-az not ready, retrying..."
    sleep 2
done

# Pass host:port to azurerm provider for Azure Stack endpoint discovery
export TF_VAR_metadata_host="$FLOCI_AZ_HOST"

# Add /etc/hosts entries so *.vault.azure.net resolves to floci-az.
# The azurerm provider validates vault URIs as name.vault.azure.net and calls data-plane there.
FLOCI_AZ_HOST_ONLY="${FLOCI_AZ_HOST%%:*}"
FLOCI_AZ_HOST_IP=$(getent hosts "$FLOCI_AZ_HOST_ONLY" 2>/dev/null | awk '{print $1}' | head -1 || echo "127.0.0.1")
# Map all domain-based endpoints to loopback so socat can intercept and forward to floci-az.
# Blob/queue use standard HTTP port 80; Key Vault uses standard HTTPS port 443.
# kv-default is the fixed account name used when the azurerm provider sends KV data-plane
# requests to the ARM base URL (metadata/endpoints returns 404).
echo "127.0.0.1 floci-test-kv.vault.azure.net" >> /etc/hosts
echo "127.0.0.1 kv-default.vault.azure.net" >> /etc/hosts
echo "127.0.0.1 flocitestsa.blob.core.windows.net" >> /etc/hosts
echo "127.0.0.1 flocitestsa.queue.core.windows.net" >> /etc/hosts

# Forward port 80 (HTTP) for storage data-plane and port 443 (HTTPS) for Key Vault
# data-plane to the emulator so azurerm provider can use standard domain-based endpoints.
socat TCP-LISTEN:80,bind=127.0.0.1,fork,reuseaddr TCP:"${FLOCI_AZ_HOST}" &
socat TCP-LISTEN:443,bind=127.0.0.1,fork,reuseaddr TCP:"${FLOCI_AZ_HOST}" &
sleep 1

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
