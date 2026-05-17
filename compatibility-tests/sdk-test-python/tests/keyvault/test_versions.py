"""
Compatibility tests for Key Vault secret versioning.
"""
import uuid
import pytest
from azure.core.exceptions import ResourceNotFoundError


def unique(prefix="ver"):
    return f"{prefix}-{uuid.uuid4().hex[:8]}"


def test_multiple_versions_created(client):
    name = unique()
    v1 = client.set_secret(name, "value-1").properties.version
    v2 = client.set_secret(name, "value-2").properties.version
    v3 = client.set_secret(name, "value-3").properties.version

    assert v1 != v2
    assert v2 != v3


def test_get_latest_returns_most_recent(client):
    name = unique()
    client.set_secret(name, "first")
    client.set_secret(name, "second")
    client.set_secret(name, "third")

    latest = client.get_secret(name)
    assert latest.value == "third"


def test_get_specific_version(client):
    name = unique()
    v1 = client.set_secret(name, "v1-value")
    client.set_secret(name, "v2-value")

    by_version = client.get_secret(name, v1.properties.version)
    assert by_version.value == "v1-value"


def test_get_nonexistent_version_raises(client):
    name = unique()
    client.set_secret(name, "exists")
    with pytest.raises(ResourceNotFoundError):
        client.get_secret(name, "0" * 32)


def test_list_versions(client):
    name = unique()
    versions = set()
    for i in range(3):
        s = client.set_secret(name, f"value-{i}")
        versions.add(s.properties.version)

    listed = {p.version for p in client.list_properties_of_secret_versions(name)}
    assert versions == listed


def test_version_id_format(client):
    name = unique()
    s = client.set_secret(name, "formatted")
    version = s.properties.version
    # Azure version IDs are 32-char lowercase hex strings
    assert len(version) == 32
    assert all(c in "0123456789abcdef" for c in version)


def test_created_time_preserved_across_versions(client):
    name = unique()
    v1 = client.set_secret(name, "first")
    v2 = client.set_secret(name, "second")
    # created time on the second set should not be earlier than the first
    assert v2.properties.created_on >= v1.properties.created_on


def test_id_includes_version(client):
    name = unique()
    s = client.set_secret(name, "has-version")
    assert s.properties.version in s.id


def test_backup_secret(client):
    name = unique()
    client.set_secret(name, "backup-me")
    backup = client.backup_secret(name)
    assert backup is not None
    assert len(backup) > 0
