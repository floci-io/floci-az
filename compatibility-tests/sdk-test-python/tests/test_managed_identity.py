"""Managed Identity compatibility test.

Drives the ``Microsoft.ManagedIdentity/userAssignedIdentities`` ARM surface and the
IMDS token endpoint through the real Azure REST wire protocol.

Mirrors ``test_vm.py``: ARM management-plane CRUD uses raw ``requests``. The IMDS
data plane is additionally exercised through the real ``azure-identity``
``ManagedIdentityCredential``, which reaches the emulator when
``AZURE_POD_IDENTITY_AUTHORITY_HOST`` overrides ``http://169.254.169.254``.
"""
import base64
import json
import os
import re

import pytest
import requests

EMULATOR_BASE = os.environ.get("FLOCI_AZ_ENDPOINT", "http://localhost:4577")
SUB = os.environ.get("FLOCI_AZ_SUBSCRIPTION", "00000000-0000-0000-0000-000000000001")
RG = "sdk-test-rg-msi"
IDENTITY = "sdktestmsi"

MSI_API = "2024-11-30"
IMDS_API = "2018-02-01"
RG_API = "2021-04-01"

HEADERS = {"Authorization": "Bearer fake", "Content-Type": "application/json"}
GUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

RG_BASE = f"{EMULATOR_BASE}/subscriptions/{SUB}/resourceGroups/{RG}"
IDENTITY_URL = (
    f"{RG_BASE}/providers/Microsoft.ManagedIdentity/userAssignedIdentities/{IDENTITY}"
)
IMDS_URL = f"{EMULATOR_BASE}/metadata/identity/oauth2/token"


def _decode_jwt_payload(token: str) -> dict:
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


@pytest.fixture(scope="module")
def identity():
    requests.put(
        f"{RG_BASE}?api-version={RG_API}",
        json={"location": "eastus"},
        headers=HEADERS,
        timeout=10,
    )
    resp = requests.put(
        f"{IDENTITY_URL}?api-version={MSI_API}",
        json={"location": "eastus", "tags": {"env": "compat"}},
        headers=HEADERS,
        timeout=10,
    )
    assert resp.status_code in (200, 201), resp.text
    yield resp.json()
    requests.delete(f"{IDENTITY_URL}?api-version={MSI_API}", headers=HEADERS, timeout=10)


def test_identity_create_generates_guids(identity):
    assert identity["name"] == IDENTITY
    assert identity["type"] == "Microsoft.ManagedIdentity/userAssignedIdentities"
    props = identity["properties"]
    for field in ("tenantId", "principalId", "clientId"):
        assert GUID.match(props[field]), f"{field} must be a GUID: {props[field]}"


def test_identity_get_and_list(identity):
    resp = requests.get(f"{IDENTITY_URL}?api-version={MSI_API}", headers=HEADERS, timeout=10)
    assert resp.status_code == 200, resp.text
    assert resp.json()["properties"]["clientId"] == identity["properties"]["clientId"]

    listing = requests.get(
        f"{RG_BASE}/providers/Microsoft.ManagedIdentity/userAssignedIdentities"
        f"?api-version={MSI_API}",
        headers=HEADERS,
        timeout=10,
    )
    assert listing.status_code == 200, listing.text
    assert IDENTITY in [r["name"] for r in listing.json()["value"]]


def test_federated_identity_credential_lifecycle(identity):
    fic_url = f"{IDENTITY_URL}/federatedIdentityCredentials/gha?api-version={MSI_API}"
    body = {
        "properties": {
            "issuer": "https://token.actions.githubusercontent.com",
            "subject": "repo:floci-io/floci-az:ref:refs/heads/main",
            "audiences": ["api://AzureADTokenExchange"],
        }
    }
    resp = requests.put(fic_url, json=body, headers=HEADERS, timeout=10)
    assert resp.status_code == 201, resp.text
    assert resp.json()["properties"]["issuer"] == body["properties"]["issuer"]

    resp = requests.delete(fic_url, headers=HEADERS, timeout=10)
    assert resp.status_code in (200, 204), resp.text


def test_imds_requires_metadata_header():
    resp = requests.get(
        IMDS_URL,
        params={"resource": "https://management.azure.com/", "api-version": IMDS_API},
        timeout=10,
    )
    assert resp.status_code == 400
    assert resp.json()["error"] == "invalid_request"


def test_imds_token_raw_http(identity):
    client_id = identity["properties"]["clientId"]
    resp = requests.get(
        IMDS_URL,
        params={
            "resource": "https://management.azure.com/",
            "api-version": IMDS_API,
            "client_id": client_id,
        },
        headers={"Metadata": "true"},
        timeout=10,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    for field in (
        "access_token",
        "client_id",
        "expires_in",
        "expires_on",
        "ext_expires_in",
        "not_before",
        "resource",
        "token_type",
    ):
        assert isinstance(body[field], str), f"{field} must be a string"
    assert body["token_type"] == "Bearer"
    assert body["client_id"] == client_id

    claims = _decode_jwt_payload(body["access_token"])
    assert claims["aud"] == "https://management.azure.com/"
    assert claims["appid"] == client_id
    assert claims["oid"] == identity["properties"]["principalId"]
    assert claims["ver"] == "1.0"


@pytest.mark.skipif(
    "AZURE_POD_IDENTITY_AUTHORITY_HOST" not in os.environ,
    reason="AZURE_POD_IDENTITY_AUTHORITY_HOST not set; SDK IMDS path targets 169.254.169.254",
)
def test_managed_identity_credential_sdk(identity):
    from azure.identity import ManagedIdentityCredential

    client_id = identity["properties"]["clientId"]

    token = ManagedIdentityCredential(client_id=client_id).get_token(
        "https://management.azure.com/.default"
    )
    claims = _decode_jwt_payload(token.token)
    assert claims["appid"] == client_id

    # System-assigned (no client_id) also succeeds.
    system_token = ManagedIdentityCredential().get_token(
        "https://management.azure.com/.default"
    )
    assert _decode_jwt_payload(system_token.token)["ver"] == "1.0"
