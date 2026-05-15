import pytest
import uuid
from azure.appconfiguration import ConfigurationSetting
from azure.core.exceptions import ResourceNotFoundError


def make_key():
    return f"label-test:{uuid.uuid4().hex[:12]}"


def test_labels_isolate_values(appconfig_client):
    key = make_key()

    appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="prod-value", label="prod"))
    appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="dev-value", label="dev"))

    prod = appconfig_client.get_configuration_setting(key=key, label="prod")
    dev = appconfig_client.get_configuration_setting(key=key, label="dev")

    assert prod.value == "prod-value"
    assert dev.value == "dev-value"

    appconfig_client.delete_configuration_setting(key=key, label="prod")
    appconfig_client.delete_configuration_setting(key=key, label="dev")


def test_no_label_and_label_are_independent(appconfig_client):
    key = make_key()

    appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="default"))
    appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="labeled", label="staging"))

    no_label = appconfig_client.get_configuration_setting(key=key)
    with_label = appconfig_client.get_configuration_setting(key=key, label="staging")

    assert no_label.value == "default"
    assert with_label.value == "labeled"

    appconfig_client.delete_configuration_setting(key=key)
    appconfig_client.delete_configuration_setting(key=key, label="staging")


def test_list_by_label_filter(appconfig_client):
    prefix = f"lbl-filter-{uuid.uuid4().hex[:8]}"
    keys_dev = [f"{prefix}:{i}" for i in range(3)]

    for k in keys_dev:
        appconfig_client.set_configuration_setting(ConfigurationSetting(key=k, value="x", label="dev"))
    # add one with a different label that should not appear
    appconfig_client.set_configuration_setting(
        ConfigurationSetting(key=f"{prefix}:other", value="y", label="prod")
    )

    listed = [
        s.key for s in appconfig_client.list_configuration_settings(
            key_filter=f"{prefix}:*",
            label_filter="dev"
        )
    ]
    assert sorted(listed) == sorted(keys_dev)

    for k in keys_dev:
        appconfig_client.delete_configuration_setting(key=k, label="dev")
    appconfig_client.delete_configuration_setting(key=f"{prefix}:other", label="prod")


def test_update_only_affects_matching_label(appconfig_client):
    key = make_key()

    appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="original", label="dev"))
    appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="original", label="prod"))

    appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="updated", label="dev"))

    dev = appconfig_client.get_configuration_setting(key=key, label="dev")
    prod = appconfig_client.get_configuration_setting(key=key, label="prod")

    assert dev.value == "updated"
    assert prod.value == "original"

    appconfig_client.delete_configuration_setting(key=key, label="dev")
    appconfig_client.delete_configuration_setting(key=key, label="prod")


def test_get_with_wrong_label_returns_not_found(appconfig_client):
    key = make_key()
    appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="x", label="dev"))

    with pytest.raises(ResourceNotFoundError):
        appconfig_client.get_configuration_setting(key=key, label="nonexistent")

    appconfig_client.delete_configuration_setting(key=key, label="dev")
