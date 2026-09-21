"""Azure Container Instances compatibility test.

Drives the ``Microsoft.ContainerInstance/containerGroups`` ARM surface through
the real Azure REST wire protocol.

Mirrors ``test_vm.py``: the established pattern for ARM management-plane
services in this suite is raw ``requests`` against the REST paths rather than
the fluent ``azure-mgmt-containerinstance`` SDK. Covers the lifecycle the SDK,
the ``az container`` CLI, and ``azurerm_container_group`` exercise: create →
get (with instanceView) → list (without instanceView) → actions with the spec's
exact LRO shapes (start 202 + Location, restart 204 + Location, stop bare 204)
→ container logs → delete (200 with the body, 204 once absent). Also pins the azurerm read-back
contract: container ports and resource requests always present, canonical enum
casing, secrets never echoed.
"""
import os

import pytest
import requests

EMULATOR_BASE = os.environ.get("FLOCI_AZ_ENDPOINT", "http://localhost:4577")
SUB = os.environ.get("FLOCI_AZ_SUBSCRIPTION", "00000000-0000-0000-0000-000000000001")
RG = "sdk-test-rg-aci"
GROUP = "sdktestcg"

ACI_API = "2023-05-01"
RG_API = "2021-04-01"

HEADERS = {"Authorization": "Bearer fake", "Content-Type": "application/json"}

RG_BASE = f"{EMULATOR_BASE}/subscriptions/{SUB}/resourceGroups/{RG}"
ACI_BASE = f"{RG_BASE}/providers/Microsoft.ContainerInstance"
GROUP_URL = f"{ACI_BASE}/containerGroups/{GROUP}"

CREATE_BODY = {
    "location": "eastus",
    "tags": {"env": "compat"},
    "properties": {
        "containers": [
            {
                "name": "web",
                "properties": {
                    "image": "hashicorp/http-echo:latest",
                    "command": ["/http-echo", "-text=hi"],
                    "ports": [{"port": 5678, "protocol": "tcp"}],
                    "environmentVariables": [
                        {"name": "PLAIN", "value": "visible"},
                        {"name": "SECRET", "secureValue": "hunter2"},
                    ],
                },
            }
        ],
        "osType": "linux",
        "imageRegistryCredentials": [
            {"server": "example.azurecr.io", "username": "admin", "password": "p"}
        ],
        "ipAddress": {
            "type": "Public",
            "ports": [{"port": 5678}],
            "dnsNameLabel": "sdkcompat",
        },
    },
}


@pytest.fixture(scope="module")
def provisioned_group():
    requests.put(
        f"{RG_BASE}?api-version={RG_API}",
        json={"location": "eastus"},
        headers=HEADERS,
        timeout=10,
    )
    resp = requests.put(
        f"{GROUP_URL}?api-version={ACI_API}",
        json=CREATE_BODY,
        headers=HEADERS,
        timeout=30,
    )
    assert resp.status_code in (200, 201), resp.text
    yield resp.json()
    requests.delete(f"{GROUP_URL}?api-version={ACI_API}", headers=HEADERS, timeout=30)


def test_create_normalizes_readback(provisioned_group):
    props = provisioned_group["properties"]
    assert props["provisioningState"] == "Succeeded"
    # azurerm read-back contract: canonical casing + defaulted resources.
    assert props["osType"] == "Linux"
    assert props["restartPolicy"] == "Always"
    container = props["containers"][0]["properties"]
    assert container["ports"][0]["protocol"] == "TCP"
    assert container["resources"]["requests"]["cpu"] > 0
    assert container["resources"]["requests"]["memoryInGB"] > 0
    # Secrets are write-only.
    secret_var = container["environmentVariables"][1]
    assert secret_var["name"] == "SECRET"
    assert "secureValue" not in secret_var and "value" not in secret_var
    assert "password" not in props["imageRegistryCredentials"][0]
    assert props["ipAddress"]["fqdn"] == "sdkcompat.eastus.azurecontainer.io"


def test_get_includes_instance_view(provisioned_group):
    resp = requests.get(f"{GROUP_URL}?api-version={ACI_API}", headers=HEADERS, timeout=10)
    assert resp.status_code == 200, resp.text
    props = resp.json()["properties"]
    assert "instanceView" in props
    assert "instanceView" in props["containers"][0]["properties"]


def test_lists_omit_instance_view(provisioned_group):
    rg_list = requests.get(
        f"{ACI_BASE}/containerGroups?api-version={ACI_API}", headers=HEADERS, timeout=10
    )
    assert rg_list.status_code == 200, rg_list.text
    entry = next(v for v in rg_list.json()["value"] if v["name"] == GROUP)
    assert "instanceView" not in entry["properties"]

    sub_list = requests.get(
        f"{EMULATOR_BASE}/subscriptions/{SUB}/providers/Microsoft.ContainerInstance"
        f"/containerGroups?api-version={ACI_API}",
        headers=HEADERS,
        timeout=10,
    )
    assert sub_list.status_code == 200, sub_list.text
    assert any(v["name"] == GROUP for v in sub_list.json()["value"])


def test_actions_match_spec_lro_shapes(provisioned_group):
    start = requests.post(
        f"{GROUP_URL}/start?api-version={ACI_API}", headers=HEADERS, timeout=10
    )
    assert start.status_code == 202, start.text
    location = start.headers.get("Location", "")
    assert "/providers/Microsoft.ContainerInstance/locations/" in location

    op = requests.get(location, headers=HEADERS, timeout=10)
    assert op.status_code == 200 and op.json()["status"] == "Succeeded"

    restart = requests.post(
        f"{GROUP_URL}/restart?api-version={ACI_API}", headers=HEADERS, timeout=10
    )
    assert restart.status_code == 204, "restart signals its LRO on a 204 per spec"
    assert "Location" in restart.headers

    stop = requests.post(
        f"{GROUP_URL}/stop?api-version={ACI_API}", headers=HEADERS, timeout=10
    )
    assert stop.status_code == 204, "stop is the spec's only synchronous action"
    assert "Location" not in stop.headers


def test_container_logs_envelope(provisioned_group):
    resp = requests.get(
        f"{GROUP_URL}/containers/web/logs?api-version={ACI_API}",
        headers=HEADERS,
        timeout=10,
    )
    assert resp.status_code == 200, resp.text
    assert "content" in resp.json()


def test_exec_returns_honest_501(provisioned_group):
    resp = requests.post(
        f"{GROUP_URL}/containers/web/exec?api-version={ACI_API}",
        json={"command": "/bin/sh", "terminalSize": {"rows": 24, "cols": 80}},
        headers=HEADERS,
        timeout=10,
    )
    assert resp.status_code == 501, resp.text
    assert resp.json()["error"]["code"] == "NotImplemented"


def test_group_in_resource_index(provisioned_group):
    resp = requests.get(
        f"{RG_BASE}/resources?api-version={RG_API}", headers=HEADERS, timeout=10
    )
    assert resp.status_code == 200, resp.text
    entry = next(v for v in resp.json()["value"] if v["name"] == GROUP)
    assert entry["type"] == "Microsoft.ContainerInstance/containerGroups"


def test_delete_returns_body_then_404_then_204():
    delete = requests.delete(f"{GROUP_URL}?api-version={ACI_API}", headers=HEADERS, timeout=30)
    assert delete.status_code == 200, delete.text
    assert delete.json()["name"] == GROUP
    assert (
        requests.get(f"{GROUP_URL}?api-version={ACI_API}", headers=HEADERS, timeout=10).status_code
        == 404
    )
    # Idempotent: 204 once the group is absent.
    assert (
        requests.delete(f"{GROUP_URL}?api-version={ACI_API}", headers=HEADERS, timeout=10).status_code
        == 204
    )
