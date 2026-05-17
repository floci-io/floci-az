"""
Compatibility tests for Azure Key Vault Secrets — set / get / list / delete / recover / purge.
"""
import uuid
import pytest
from azure.core.exceptions import ResourceNotFoundError


def unique(prefix="secret"):
    return f"{prefix}-{uuid.uuid4().hex[:8]}"


# ---------------------------------------------------------------------------
# Basic CRUD
# ---------------------------------------------------------------------------

def test_set_and_get_secret(client):
    name = unique()
    created = client.set_secret(name, "hello-world")
    assert created.value == "hello-world"
    assert created.name == name
    assert created.properties.version is not None

    fetched = client.get_secret(name)
    assert fetched.value == "hello-world"
    assert fetched.name == name


def test_set_secret_with_content_type(client):
    name = unique()
    client.set_secret(name, "json-secret", content_type="application/json")
    fetched = client.get_secret(name)
    assert fetched.properties.content_type == "application/json"


def test_set_secret_with_tags(client):
    name = unique()
    client.set_secret(name, "tagged", tags={"env": "test", "owner": "ci"})
    fetched = client.get_secret(name)
    assert fetched.properties.tags == {"env": "test", "owner": "ci"}


def test_update_overwrites_value(client):
    name = unique()
    client.set_secret(name, "v1")
    client.set_secret(name, "v2")
    fetched = client.get_secret(name)
    assert fetched.value == "v2"


def test_get_nonexistent_secret_raises(client):
    with pytest.raises(ResourceNotFoundError):
        client.get_secret(unique("nonexistent"))


def test_delete_secret(client):
    name = unique()
    client.set_secret(name, "to-be-deleted")
    poller = client.begin_delete_secret(name)
    deleted = poller.result()
    assert deleted.name == name

    with pytest.raises(ResourceNotFoundError):
        client.get_secret(name)


def test_delete_nonexistent_raises(client):
    with pytest.raises(ResourceNotFoundError):
        client.begin_delete_secret(unique("missing")).result()


# ---------------------------------------------------------------------------
# Soft-delete lifecycle
# ---------------------------------------------------------------------------

def test_get_deleted_secret(client):
    name = unique()
    client.set_secret(name, "soft-del")
    client.begin_delete_secret(name).result()

    deleted = client.get_deleted_secret(name)
    assert deleted.name == name
    assert deleted.deleted_date is not None
    assert deleted.scheduled_purge_date is not None


def test_recover_deleted_secret(client):
    name = unique()
    client.set_secret(name, "recoverable")
    client.begin_delete_secret(name).result()

    poller = client.begin_recover_deleted_secret(name)
    recovered = poller.result()
    assert recovered.name == name

    fetched = client.get_secret(name)
    assert fetched.value == "recoverable"


def test_purge_deleted_secret(client):
    name = unique()
    client.set_secret(name, "to-purge")
    client.begin_delete_secret(name).result()
    client.purge_deleted_secret(name)

    with pytest.raises(ResourceNotFoundError):
        client.get_deleted_secret(name)


# ---------------------------------------------------------------------------
# List operations
# ---------------------------------------------------------------------------

def test_list_secrets(client):
    prefix = unique("list")
    names = [f"{prefix}-{i}" for i in range(3)]
    for n in names:
        client.set_secret(n, "value")

    listed = [s.name for s in client.list_properties_of_secrets()]
    for n in names:
        assert n in listed


def test_list_deleted_secrets(client):
    name = unique("dlist")
    client.set_secret(name, "will-be-deleted")
    client.begin_delete_secret(name).result()

    deleted_names = [s.name for s in client.list_deleted_secrets()]
    assert name in deleted_names


# ---------------------------------------------------------------------------
# Update properties
# ---------------------------------------------------------------------------

def test_update_secret_properties(client):
    name = unique()
    s = client.set_secret(name, "original", content_type="text/plain")
    version = s.properties.version

    client.update_secret_properties(name, version, content_type="application/json",
                                    tags={"updated": "yes"})
    fetched = client.get_secret(name)
    assert fetched.properties.content_type == "application/json"
    assert fetched.properties.tags.get("updated") == "yes"
    # Value must be unchanged
    assert fetched.value == "original"


# ---------------------------------------------------------------------------
# Attributes (enabled, exp, nbf)
# ---------------------------------------------------------------------------

def test_secret_enabled_false(client):
    from azure.core.exceptions import HttpResponseError
    name = unique()
    from datetime import datetime, timezone
    s = client.set_secret(name, "disabled-secret")
    client.update_secret_properties(name, s.properties.version, enabled=False)

    with pytest.raises(HttpResponseError):
        client.get_secret(name)


def test_secret_enabled_attribute(client):
    name = unique()
    s = client.set_secret(name, "enabled-secret")
    assert s.properties.enabled is True

    client.update_secret_properties(name, s.properties.version, enabled=False)
    props = next(p for p in client.list_properties_of_secrets() if p.name == name)
    assert props.enabled is False
