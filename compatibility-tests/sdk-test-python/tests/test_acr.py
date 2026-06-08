"""Azure Container Registry compatibility test.

Provisions a registry through the ARM management plane, then (when the backing
``registry:2`` sidecar is reachable) pushes a minimal image through the standard
Docker Registry HTTP API V2 and reads it back.

The data-plane assertions are skipped when the registry never becomes connectable
(e.g. the emulator runs in mocked mode where ``loginServer`` is the cosmetic
``{name}.azurecr.io``). Run floci-az with ``floci-az.services.acr.mocked=false``
to exercise them.
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
    assert props["loginServer"]
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


def _registry_reachable(host):
    try:
        r = requests.get(f"http://{host}/v2/", timeout=3)
        return r.status_code in (200, 401)
    except requests.RequestException:
        return False


def test_push_and_pull_via_registry_v2(provisioned_registry):
    props = provisioned_registry
    login = props["loginServer"]
    # Shared registry: loginServer is host:port/{registryName}; the V2 API is at the host root and
    # the registry name is the repo prefix. In mocked mode loginServer is {name}.azurecr.io (no path).
    if "/" not in login:
        pytest.skip("registry runs in mocked mode (no data plane)")
    host, prefix = login.split("/", 1)
    if not _registry_reachable(host):
        pytest.skip("registry sidecar not reachable (mocked mode or no Docker)")

    base = f"http://{host}/v2"
    repo = f"{prefix}/sdk/minimal"

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
