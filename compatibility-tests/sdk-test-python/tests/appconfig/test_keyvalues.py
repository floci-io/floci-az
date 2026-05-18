import pytest
import uuid
from azure.appconfiguration import ConfigurationSetting
from azure.core.exceptions import ResourceNotFoundError


def make_key(prefix="test"):
    return f"{prefix}:{uuid.uuid4().hex[:12]}"


# --- Golden path ---

def test_kv_lifecycle(appconfig_client):
    key = make_key()

    appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="hello"))

    retrieved = appconfig_client.get_configuration_setting(key=key)
    assert retrieved.value == "hello"

    appconfig_client.delete_configuration_setting(key=key)

    with pytest.raises(ResourceNotFoundError):
        appconfig_client.get_configuration_setting(key=key)


def test_kv_update(appconfig_client):
    key = make_key()

    appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="v1"))
    appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="v2"))

    retrieved = appconfig_client.get_configuration_setting(key=key)
    assert retrieved.value == "v2"

    appconfig_client.delete_configuration_setting(key=key)


def test_kv_with_content_type(appconfig_client):
    key = make_key()
    setting = ConfigurationSetting(
        key=key,
        value='{"port": 8080}',
        content_type="application/json"
    )

    appconfig_client.set_configuration_setting(setting)

    retrieved = appconfig_client.get_configuration_setting(key=key)
    assert retrieved.value == '{"port": 8080}'
    assert retrieved.content_type == "application/json"

    appconfig_client.delete_configuration_setting(key=key)


def test_kv_with_tags(appconfig_client):
    key = make_key()
    setting = ConfigurationSetting(
        key=key,
        value="tagged",
        tags={"env": "dev", "owner": "team-a"}
    )

    appconfig_client.set_configuration_setting(setting)

    retrieved = appconfig_client.get_configuration_setting(key=key)
    assert retrieved.tags == {"env": "dev", "owner": "team-a"}

    appconfig_client.delete_configuration_setting(key=key)


def test_kv_list(appconfig_client):
    prefix = f"list-test-{uuid.uuid4().hex[:8]}"
    keys = [f"{prefix}:key-{i}" for i in range(3)]

    for k in keys:
        appconfig_client.set_configuration_setting(ConfigurationSetting(key=k, value=f"value-{k}"))

    listed = [
        s.key for s in appconfig_client.list_configuration_settings()
        if s.key.startswith(prefix)
    ]
    assert sorted(listed) == sorted(keys)

    for k in keys:
        appconfig_client.delete_configuration_setting(key=k)


def test_kv_list_by_key_filter(appconfig_client):
    prefix = f"filter-test-{uuid.uuid4().hex[:8]}"
    matching = [f"{prefix}:match-{i}" for i in range(3)]
    other = make_key("other")

    for k in matching:
        appconfig_client.set_configuration_setting(ConfigurationSetting(key=k, value="x"))
    appconfig_client.set_configuration_setting(ConfigurationSetting(key=other, value="y"))

    listed = [
        s.key for s in appconfig_client.list_configuration_settings(key_filter=f"{prefix}:*")
    ]
    assert sorted(listed) == sorted(matching)
    assert other not in listed

    for k in matching:
        appconfig_client.delete_configuration_setting(key=k)
    appconfig_client.delete_configuration_setting(key=other)


# --- Error cases ---

def test_kv_not_found(appconfig_client):
    with pytest.raises(ResourceNotFoundError):
        appconfig_client.get_configuration_setting(key="does-not-exist-xyz")


def test_kv_delete_not_found(appconfig_client):
    with pytest.raises(ResourceNotFoundError):
        appconfig_client.delete_configuration_setting(key="does-not-exist-xyz")
