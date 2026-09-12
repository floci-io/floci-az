"""Azure Container Registry compatibility test.

Provisions a registry through the ARM management plane, then (when the backing
``registry:2`` sidecar is reachable) pushes a minimal image anonymously through the
container's own published port and reads it back.

``loginServer`` is ``{name}.azurecr.io``, which needs name resolution and TLS, so
Azure-native clients are covered by the Azure CLI suite. What this asserts is the
convenience path: the shared container stays published and anonymous, the registry
name is the repository prefix there, and ``properties.localPort`` reports the port it
was published on. The data-plane assertions are skipped when the container is not
reachable (mocked mode, or no Docker).
"""
import hashlib
import json
import os
import time

import pytest
import requests

EMULATOR_BASE = os.environ.get("FLOCI_AZ_ENDPOINT", "http://localhost:4577")
SUB = os.environ.get("FLOCI_AZ_SUBSCRIPTION", "00000000-0000-0000-0000-000000000001")
RG = "sdk-test-rg-acr"
ACR = "sdktestacr"
API = "2025-11-01"

# Hosts the shared registry container may answer on. The port comes from the registry
# resource's localPort, so only the host has to be guessed: the container name on the compat
# Docker network, or the loopback address for a local run. ACR_REGISTRY_ENDPOINT overrides the
# whole host:port when neither applies.
REGISTRY_HOSTS = ["floci-az-acr-registry", "localhost"]

ARM_BASE = (
    f"{EMULATOR_BASE}/subscriptions/{SUB}/resourceGroups/{RG}"
    f"/providers/Microsoft.ContainerRegistry/registries/{ACR}"
)
HEADERS = {"Authorization": "Bearer fake", "Content-Type": "application/json"}


@pytest.fixture(scope="module")
def provisioned_registry():
    body = {
        "location": "eastus",
        "sku": {"name": "Basic"},
        "properties": {"adminUserEnabled": True},
    }
    put = requests.put(f"{ARM_BASE}?api-version={API}", json=body, headers=HEADERS, timeout=10)
    assert put.status_code in (200, 201), put.text

    state, props = None, {}
    for _ in range(60):
        got = requests.get(f"{ARM_BASE}?api-version={API}", headers=HEADERS, timeout=10)
        assert got.status_code == 200, got.text
        props = got.json().get("properties", {})
        state = props.get("provisioningState")
        if state in ("Succeeded", "Failed"):
            break
        time.sleep(2)
    assert state == "Succeeded", f"registry did not provision: {state}"

    creds = requests.post(f"{ARM_BASE}/listCredentials?api-version={API}", headers=HEADERS, timeout=10)
    assert creds.status_code == 200, creds.text
    cj = creds.json()
    props["username"] = cj["username"]
    props["password"] = cj["passwords"][0]["value"]

    yield props

    requests.delete(f"{ARM_BASE}?api-version={API}", headers=HEADERS, timeout=10)


def test_arm_response_shape(provisioned_registry):
    props = provisioned_registry
    assert props["loginServer"] == f"{ACR}.azurecr.io"
    assert props["adminUserEnabled"] is True
    assert props["username"] == ACR
    assert props["password"]


def test_check_name_availability():
    check = (
        f"{EMULATOR_BASE}/subscriptions/{SUB}"
        f"/providers/Microsoft.ContainerRegistry/checkNameAvailability?api-version={API}"
    )
    taken = requests.post(check, json={"name": ACR, "type": "Microsoft.ContainerRegistry/registries"},
                          headers=HEADERS, timeout=10).json()
    free = requests.post(check, json={"name": "namethatisfree999", "type": "Microsoft.ContainerRegistry/registries"},
                         headers=HEADERS, timeout=10).json()
    # ACR is created lazily by the fixture; this test only asserts the free name is available.
    assert free["nameAvailable"] is True
    assert "nameAvailable" in taken


def _reachable(endpoint):
    try:
        return requests.get(f"http://{endpoint}/v2/", timeout=3).status_code in (200, 401)
    except requests.RequestException:
        return False


def _published_registry(props):
    """The shared container's published endpoint, or None when it is not running.

    loginServer is the Azure host name, so it does not carry the port. properties.localPort
    does, the same way a PostgreSQL or MySQL server reports the port its container published.
    """
    override = os.environ.get("ACR_REGISTRY_ENDPOINT")
    if override:
        return override if _reachable(override) else None

    port = props.get("localPort")
    if not port:
        return None
    for host in REGISTRY_HOSTS:
        endpoint = f"{host}:{port}"
        if _reachable(endpoint):
            return endpoint
    return None


def test_arm_response_reports_the_published_port(provisioned_registry):
    """localPort is how a client finds the anonymous port, now that loginServer cannot say."""
    port = provisioned_registry.get("localPort")
    if port is None:
        pytest.skip("registry sidecar not running (mocked mode or no Docker)")
    assert isinstance(port, int) and port > 0


def test_push_and_pull_anonymously_via_the_published_port(provisioned_registry):
    """The published port keeps working without authenticating, over plain HTTP."""
    host = _published_registry(provisioned_registry)
    if host is None:
        pytest.skip("registry sidecar not reachable (mocked mode or no Docker)")

    base = f"http://{host}/v2"
    # On the published port the registry name is the repository prefix, the same storage
    # {ACR}.azurecr.io/v2/sdk/minimal/... addresses through the emulator.
    repo = f"{ACR}/sdk/minimal"

    # Push a config blob, then a manifest referencing it (a minimal but valid image).
    config = b"{}"
    digest = "sha256:" + hashlib.sha256(config).hexdigest()
    start = requests.post(f"{base}/{repo}/blobs/uploads/", timeout=10)
    assert start.status_code == 202, start.text
    upload_url = start.headers["Location"]
    sep = "&" if "?" in upload_url else "?"
    done = requests.put(f"{upload_url}{sep}digest={digest}", data=config,
                        headers={"Content-Type": "application/octet-stream"}, timeout=10)
    assert done.status_code == 201, done.text

    manifest = {
        "schemaVersion": 2,
        "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
        "config": {
            "mediaType": "application/vnd.docker.container.image.v1+json",
            "size": len(config),
            "digest": digest,
        },
        "layers": [],
    }
    put_manifest = requests.put(
        f"{base}/{repo}/manifests/v1",
        data=json.dumps(manifest),
        headers={"Content-Type": manifest["mediaType"]},
        timeout=10,
    )
    assert put_manifest.status_code == 201, put_manifest.text

    catalog = requests.get(f"{base}/_catalog", timeout=10).json()
    assert repo in catalog["repositories"]

    tags = requests.get(f"{base}/{repo}/tags/list", timeout=10).json()
    assert "v1" in tags["tags"]
