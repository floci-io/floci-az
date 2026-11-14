#!/usr/bin/env bash
set -euo pipefail

_raw="${FLOCI_AZ_ENDPOINT#http://}"
_raw="${_raw#https://}"
FLOCI_AZ_HOST="${_raw%/}"
HTTP_BASE="http://${FLOCI_AZ_HOST}"

# azd has no custom-cloud support, so it needs floci-az's certificate trusted for Azure's own hosts, which the
# emulator names under FLOCI_AZ_TLS_PUBLIC_HOSTS. Go reads the system trust store.
echo "Waiting for floci-az and installing TLS certificate..."
for i in $(seq 1 30); do
    if curl -sf "${HTTP_BASE}/_floci/tls-cert" -o /usr/local/share/ca-certificates/floci-az.crt 2>/dev/null; then
        update-ca-certificates >/dev/null
        echo "TLS certificate installed."
        break
    fi
    echo "  Attempt $i/30: floci-az not ready, retrying..."
    sleep 2
done

# Send azd's hardcoded hosts to floci-az, the way the azcli and terraform suites reach *.vault.azure.net.
for host in management.azure.com management.core.windows.net login.microsoftonline.com graph.microsoft.com; do
    echo "127.0.0.1 ${host}" >> /etc/hosts
done
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
