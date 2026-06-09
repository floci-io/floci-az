"""Azure Cache for Redis compatibility test.

Provisions a cache through the ARM management plane, then connects to the backing
Redis sidecar with the standard redis-py client using the returned access key.

Requires floci-az to run with ``floci-az.services.redis.mocked=false`` (a real Redis
container per cache). When the cache never becomes connectable (e.g. the emulator runs
in mocked mode without Docker), the data-plane assertions are skipped.
"""
import os
import time

import pytest
import redis
import requests

EMULATOR_BASE = os.environ.get("FLOCI_AZ_ENDPOINT", "http://localhost:4577")
SUB = os.environ.get("FLOCI_AZ_SUBSCRIPTION", "00000000-0000-0000-0000-000000000001")
RG = "sdk-test-rg-redis"
CACHE = "sdk-test-redis"
API = "2024-11-01"

ARM_BASE = (
    f"{EMULATOR_BASE}/subscriptions/{SUB}/resourceGroups/{RG}"
    f"/providers/Microsoft.Cache/redis/{CACHE}"
)
HEADERS = {"Authorization": "Bearer fake", "Content-Type": "application/json"}


@pytest.fixture(scope="module")
def provisioned_cache():
    body = {
        "location": "eastus",
        "properties": {
            "sku": {"name": "Basic", "family": "C", "capacity": 0},
            "enableNonSslPort": True,
            "minimumTlsVersion": "1.2",
        },
    }
    put = requests.put(f"{ARM_BASE}?api-version={API}", json=body, headers=HEADERS, timeout=10)
    assert put.status_code in (200, 201), put.text

    # Poll until the cache reports Succeeded (the sidecar is up).
    state, props = None, {}
    for _ in range(60):
        got = requests.get(f"{ARM_BASE}?api-version={API}", headers=HEADERS, timeout=10)
        assert got.status_code == 200, got.text
        props = got.json().get("properties", {})
        state = props.get("provisioningState")
        if state in ("Succeeded", "Failed"):
            break
        time.sleep(2)
    assert state == "Succeeded", f"cache did not provision: {state}"

    keys = requests.post(f"{ARM_BASE}/listKeys?api-version={API}", headers=HEADERS, timeout=10)
    assert keys.status_code == 200, keys.text
    props["primaryKey"] = keys.json()["primaryKey"]

    yield props

    requests.delete(f"{ARM_BASE}?api-version={API}", headers=HEADERS, timeout=10)


def _client(props, password):
    return redis.Redis(
        host=props["hostName"],
        port=props["port"],
        password=password,
        socket_connect_timeout=3,
        socket_timeout=3,
    )


def test_arm_response_shape(provisioned_cache):
    props = provisioned_cache
    assert props["hostName"]
    assert props["port"] == 6379
    assert props["sslPort"] == 6380
    assert props["primaryKey"]


def test_set_get_with_primary_key(provisioned_cache):
    props = provisioned_cache
    client = _client(props, props["primaryKey"])
    try:
        client.ping()
    except redis.exceptions.RedisError:
        pytest.skip("Redis sidecar not reachable (mocked mode or no Docker)")

    client.set("floci:greeting", "hello-from-redis-py")
    assert client.get("floci:greeting") == b"hello-from-redis-py"


def test_wrong_password_rejected(provisioned_cache):
    props = provisioned_cache
    # Confirm the sidecar is up before asserting auth behavior.
    try:
        _client(props, props["primaryKey"]).ping()
    except redis.exceptions.RedisError:
        pytest.skip("Redis sidecar not reachable (mocked mode or no Docker)")

    # A wrong password raises AuthenticationError (newer redis-py) or a ResponseError "WRONGPASS"
    # (older); accept either.
    with pytest.raises((redis.exceptions.AuthenticationError, redis.exceptions.ResponseError)):
        _client(props, "wrong-password").ping()
