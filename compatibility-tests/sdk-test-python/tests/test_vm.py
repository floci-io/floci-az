"""Azure Virtual Machines compatibility test.

Drives the ``Microsoft.Compute/virtualMachines`` ARM surface and its
``Microsoft.Network`` dependency stubs (virtual network / network interface)
through the real Azure REST wire protocol.

Mirrors ``test_acr.py``: the established pattern for ARM management-plane
services in this suite is raw ``requests`` against the REST paths rather than the
fluent ``azure-mgmt-compute`` SDK (which expects subscription / provider-registration
endpoints floci-az does not emulate). Covers the lifecycle the
``azure-mgmt-compute`` SDK and ``azurerm_linux_virtual_machine`` exercise:
create (vnet/subnet/nic → VM) → get → ``$expand=instanceView`` → instanceView →
power actions (powerOff / start / deallocate / restart, asserting the
``Azure-AsyncOperation`` + ``Location`` LRO headers and the resulting PowerState) →
list by resource group and by subscription → delete.
"""
import os

import pytest
import requests

EMULATOR_BASE = os.environ.get("FLOCI_AZ_ENDPOINT", "http://localhost:4577")
SUB = os.environ.get("FLOCI_AZ_SUBSCRIPTION", "00000000-0000-0000-0000-000000000001")
RG = "sdk-test-rg-vm"
VM = "sdktestvm"
VNET = "sdktestvnet"
NIC = f"{VM}-nic"

COMPUTE_API = "2024-11-01"
NETWORK_API = "2024-05-01"
RG_API = "2021-04-01"

HEADERS = {"Authorization": "Bearer fake", "Content-Type": "application/json"}

RG_BASE = f"{EMULATOR_BASE}/subscriptions/{SUB}/resourceGroups/{RG}"
COMPUTE_BASE = f"{RG_BASE}/providers/Microsoft.Compute"
VM_URL = f"{COMPUTE_BASE}/virtualMachines/{VM}"
VNET_ID = f"/subscriptions/{SUB}/resourceGroups/{RG}/providers/Microsoft.Network/virtualNetworks/{VNET}"
NIC_ID = f"/subscriptions/{SUB}/resourceGroups/{RG}/providers/Microsoft.Network/networkInterfaces/{NIC}"


def _statuses_codes(statuses):
    return [s.get("code") for s in statuses]


@pytest.fixture(scope="module")
def provisioned_vm():
    requests.put(
        f"{RG_BASE}?api-version={RG_API}",
        json={"location": "eastus"},
        headers=HEADERS,
        timeout=10,
    )

    vnet_body = {
        "location": "eastus",
        "properties": {
            "addressSpace": {"addressPrefixes": ["10.0.0.0/16"]},
            "subnets": [
                {"name": "default", "properties": {"addressPrefix": "10.0.0.0/24"}}
            ],
        },
    }
    vnet = requests.put(
        f"{EMULATOR_BASE}{VNET_ID}?api-version={NETWORK_API}",
        json=vnet_body,
        headers=HEADERS,
        timeout=10,
    )
    assert vnet.status_code in (200, 201), vnet.text
    assert vnet.json()["properties"]["provisioningState"] == "Succeeded"

    nic_body = {
        "location": "eastus",
        "properties": {
            "ipConfigurations": [
                {
                    "name": "ipconfig1",
                    "properties": {"subnet": {"id": f"{VNET_ID}/subnets/default"}},
                }
            ]
        },
    }
    nic = requests.put(
        f"{EMULATOR_BASE}{NIC_ID}?api-version={NETWORK_API}",
        json=nic_body,
        headers=HEADERS,
        timeout=10,
    )
    assert nic.status_code in (200, 201), nic.text
    nic_props = nic.json()["properties"]
    assert nic_props["provisioningState"] == "Succeeded"
    assert nic_props["ipConfigurations"][0]["properties"].get("privateIPAddress")

    vm_body = {
        "location": "eastus",
        "tags": {"env": "compat"},
        "properties": {
            "hardwareProfile": {"vmSize": "Standard_D2s_v3"},
            "storageProfile": {
                "imageReference": {
                    "publisher": "Canonical",
                    "offer": "0001-com-ubuntu-server-jammy",
                    "sku": "22_04-lts",
                    "version": "latest",
                },
                "osDisk": {"createOption": "FromImage", "name": f"{VM}-osdisk"},
            },
            "osProfile": {"adminUsername": "azureuser", "computerName": VM},
            "networkProfile": {"networkInterfaces": [{"id": NIC_ID}]},
        },
    }
    put = requests.put(
        f"{VM_URL}?api-version={COMPUTE_API}", json=vm_body, headers=HEADERS, timeout=10
    )
    assert put.status_code == 201, put.text
    yield put.json()

    requests.delete(f"{VM_URL}?api-version={COMPUTE_API}", headers=HEADERS, timeout=10)


def _power_action(action):
    resp = requests.post(
        f"{VM_URL}/{action}?api-version={COMPUTE_API}", headers=HEADERS, timeout=10
    )
    assert resp.status_code == 202, resp.text
    return resp


def _instance_view():
    resp = requests.get(
        f"{VM_URL}/instanceView?api-version={COMPUTE_API}", headers=HEADERS, timeout=10
    )
    assert resp.status_code == 200, resp.text
    return resp.json()


def test_create_returns_succeeded_with_echoed_properties(provisioned_vm):
    assert provisioned_vm["name"] == VM
    assert provisioned_vm["type"] == "Microsoft.Compute/virtualMachines"
    assert provisioned_vm["location"] == "eastus"
    assert provisioned_vm["tags"]["env"] == "compat"
    props = provisioned_vm["properties"]
    assert props["provisioningState"] == "Succeeded"
    assert props.get("vmId")
    assert props["hardwareProfile"]["vmSize"] == "Standard_D2s_v3"
    assert props["osProfile"]["adminUsername"] == "azureuser"
    assert props["networkProfile"]["networkInterfaces"][0]["id"] == NIC_ID


def test_get_and_expand_instance_view(provisioned_vm):
    got = requests.get(f"{VM_URL}?api-version={COMPUTE_API}", headers=HEADERS, timeout=10)
    assert got.status_code == 200, got.text
    assert got.json()["name"] == VM

    expand = requests.get(
        f"{VM_URL}?api-version={COMPUTE_API}&$expand=instanceView",
        headers=HEADERS,
        timeout=10,
    )
    assert expand.status_code == 200, expand.text
    statuses = expand.json()["properties"]["instanceView"]["statuses"]
    assert "PowerState/running" in _statuses_codes(statuses)


def test_instance_view_reports_provisioning_and_power_state(provisioned_vm):
    codes = _statuses_codes(_instance_view()["statuses"])
    assert "ProvisioningState/succeeded" in codes
    assert "PowerState/running" in codes


def test_power_actions_emit_async_header_and_change_state(provisioned_vm):
    off = _power_action("powerOff")
    assert "/operations/" in off.headers.get("Azure-AsyncOperation", "")
    assert off.headers.get("Location"), "powerOff must return a Location header"
    assert "PowerState/stopped" in _statuses_codes(_instance_view()["statuses"])

    _power_action("start")
    assert "PowerState/running" in _statuses_codes(_instance_view()["statuses"])

    _power_action("deallocate")
    assert "PowerState/deallocated" in _statuses_codes(_instance_view()["statuses"])

    _power_action("restart")
    assert "PowerState/running" in _statuses_codes(_instance_view()["statuses"])


def test_async_operation_status_returns_succeeded(provisioned_vm):
    action = _power_action("start")
    async_url = action.headers["Azure-AsyncOperation"]
    status = requests.get(async_url, headers=HEADERS, timeout=10)
    assert status.status_code == 200, status.text
    assert status.json()["status"] == "Succeeded"


def test_list_by_resource_group_and_subscription(provisioned_vm):
    rg_list = requests.get(
        f"{COMPUTE_BASE}/virtualMachines?api-version={COMPUTE_API}",
        headers=HEADERS,
        timeout=10,
    )
    assert rg_list.status_code == 200, rg_list.text
    assert VM in [v["name"] for v in rg_list.json()["value"]]

    sub_list = requests.get(
        f"{EMULATOR_BASE}/subscriptions/{SUB}/providers/Microsoft.Compute/virtualMachines"
        f"?api-version={COMPUTE_API}",
        headers=HEADERS,
        timeout=10,
    )
    assert sub_list.status_code == 200, sub_list.text
    assert VM in [v["name"] for v in sub_list.json()["value"]]


def test_delete_then_get_returns_404(provisioned_vm):
    delete = requests.delete(f"{VM_URL}?api-version={COMPUTE_API}", headers=HEADERS, timeout=10)
    assert delete.status_code in (200, 202, 204), delete.text
    got = requests.get(f"{VM_URL}?api-version={COMPUTE_API}", headers=HEADERS, timeout=10)
    assert got.status_code == 404, got.text
    # Delete is idempotent.
    again = requests.delete(f"{VM_URL}?api-version={COMPUTE_API}", headers=HEADERS, timeout=10)
    assert again.status_code in (200, 202, 204), again.text
