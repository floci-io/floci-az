"""Azure Cache for Redis compatibility test.

Provisions a cache through the ARM management plane, then connects to the backing
Redis sidecar with the standard redis-py client using the returned access key.

Requires floci-az to run with ``floci-az.services.redis.mocked=false`` (a real Redis
container per cache). When the cache never becomes connectable (e.g. the emulator runs
in mocked mode without Docker), the data-plane assertions are skipped.
"""
import os
import socket
import ssl
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


@pytest.mark.skipif(
    os.environ.get("FLOCI_AZ_SERVICES_REDIS_TLS_ENABLED", "").lower() != "true",
    reason="Redis TLS is opt-in: set FLOCI_AZ_SERVICES_REDIS_TLS_ENABLED=true on the emulator and the suite",
)
def test_ping_over_tls_on_the_ssl_port(provisioned_cache):
    # azurerm builds primary_connection_string as host:sslPort,password=...,ssl=True, so with Redis
    # TLS enabled the SSL port must serve TLS. The suite has no copy of the CA (with floci-az TLS off
    # it lives only in the cache container, at /tls/ca.crt), so this checks the TLS port, not the chain;
    # test_the_ssl_port_verifies_against_the_floci_ca_and_the_returned_host_name checks the chain.
    props = provisioned_cache
    try:
        _client(props, props["primaryKey"]).ping()
    except redis.exceptions.RedisError:
        pytest.skip("Redis sidecar not reachable (mocked mode or no Docker)")

    client = redis.Redis(
        host=props["hostName"],
        port=props["sslPort"],
        password=props["primaryKey"],
        ssl=True,
        ssl_cert_reqs="none",
        socket_connect_timeout=3,
        socket_timeout=3,
    )
    assert client.ping()


@pytest.fixture(scope="module")
def floci_ca():
    # floci-az issues the cache certificate from the CA it serves here once FLOCI_AZ_TLS_ENABLED=true
    # (the compat emulator sets it). With floci-az TLS off the CA is not published.
    got = requests.get(f"{EMULATOR_BASE}/_floci/tls-cert", timeout=10)
    if got.status_code != 200 or "BEGIN CERTIFICATE" not in got.text:
        pytest.skip("floci-az does not publish its CA: set FLOCI_AZ_TLS_ENABLED=true on the emulator")
    return got.text


@pytest.mark.skipif(
    os.environ.get("FLOCI_AZ_SERVICES_REDIS_TLS_ENABLED", "").lower() != "true",
    reason="Redis TLS is opt-in: set FLOCI_AZ_SERVICES_REDIS_TLS_ENABLED=true on the emulator and the suite",
)
def test_the_ssl_port_verifies_against_the_floci_ca_and_the_returned_host_name(provisioned_cache, floci_ca):
    # A client that trusts only floci-az's CA must accept the cache at the hostName ARM returns: the
    # chain has to lead to that CA, the certificate has to name that host, and strict X.509 checks
    # (Python 3.13+ defaults) have to pass. A certificate for any other name must still be refused.
    props = provisioned_cache
    try:
        _client(props, props["primaryKey"]).ping()
    except redis.exceptions.RedisError:
        pytest.skip("Redis sidecar not reachable (mocked mode or no Docker)")

    host, port = props["hostName"], props["sslPort"]
    context = ssl.create_default_context(cadata=floci_ca)
    context.verify_flags |= ssl.VERIFY_X509_STRICT

    with socket.create_connection((host, port), timeout=5) as raw:
        with context.wrap_socket(raw, server_hostname=host) as tls:
            assert tls.getpeercert()

    with pytest.raises(ssl.SSLCertVerificationError):
        with socket.create_connection((host, port), timeout=5) as raw:
            context.wrap_socket(raw, server_hostname="github.com").close()

    client = redis.Redis(
        host=host,
        port=port,
        password=props["primaryKey"],
        ssl=True,
        ssl_ca_data=floci_ca,
        ssl_cert_reqs="required",
        ssl_check_hostname=True,
        socket_connect_timeout=3,
        socket_timeout=3,
    )
    client.set("floci:tls", "verified")
    assert client.get("floci:tls") == b"verified"
