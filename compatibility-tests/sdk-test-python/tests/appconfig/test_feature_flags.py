import pytest
import uuid
from azure.appconfiguration import ConfigurationSetting, FeatureFlagConfigurationSetting
from azure.core.exceptions import ResourceNotFoundError

FEATURE_FLAG_CONTENT_TYPE = "application/vnd.microsoft.appconfig.ff+json;charset=utf-8"


def make_flag_name():
    return f"Flag{uuid.uuid4().hex[:8]}"


# --- Golden path ---

def test_feature_flag_lifecycle(appconfig_client):
    name = make_flag_name()

    flag = FeatureFlagConfigurationSetting(name, enabled=True)
    appconfig_client.set_configuration_setting(flag)

    retrieved = appconfig_client.get_configuration_setting(
        key=f".appconfig.featureflag/{name}"
    )
    assert retrieved.content_type.startswith(FEATURE_FLAG_CONTENT_TYPE.split(";")[0])

    appconfig_client.delete_configuration_setting(key=f".appconfig.featureflag/{name}")

    with pytest.raises(ResourceNotFoundError):
        appconfig_client.get_configuration_setting(key=f".appconfig.featureflag/{name}")


def test_feature_flag_enabled_value_is_preserved(appconfig_client):
    name = make_flag_name()

    flag = FeatureFlagConfigurationSetting(name, enabled=True)
    appconfig_client.set_configuration_setting(flag)

    retrieved = appconfig_client.get_configuration_setting(
        key=f".appconfig.featureflag/{name}"
    )
    assert isinstance(retrieved, FeatureFlagConfigurationSetting)
    assert retrieved.enabled is True

    appconfig_client.delete_configuration_setting(key=f".appconfig.featureflag/{name}")


def test_feature_flag_disabled_value_is_preserved(appconfig_client):
    name = make_flag_name()

    flag = FeatureFlagConfigurationSetting(name, enabled=False)
    appconfig_client.set_configuration_setting(flag)

    retrieved = appconfig_client.get_configuration_setting(
        key=f".appconfig.featureflag/{name}"
    )
    assert isinstance(retrieved, FeatureFlagConfigurationSetting)
    assert retrieved.enabled is False

    appconfig_client.delete_configuration_setting(key=f".appconfig.featureflag/{name}")


def test_feature_flag_toggle(appconfig_client):
    name = make_flag_name()

    appconfig_client.set_configuration_setting(FeatureFlagConfigurationSetting(name, enabled=True))
    appconfig_client.set_configuration_setting(FeatureFlagConfigurationSetting(name, enabled=False))

    retrieved = appconfig_client.get_configuration_setting(
        key=f".appconfig.featureflag/{name}"
    )
    assert isinstance(retrieved, FeatureFlagConfigurationSetting)
    assert retrieved.enabled is False

    appconfig_client.delete_configuration_setting(key=f".appconfig.featureflag/{name}")


def test_feature_flags_appear_in_list(appconfig_client):
    name = make_flag_name()
    key = f".appconfig.featureflag/{name}"

    appconfig_client.set_configuration_setting(FeatureFlagConfigurationSetting(name, enabled=True))

    all_keys = [s.key for s in appconfig_client.list_configuration_settings()]
    assert key in all_keys

    appconfig_client.delete_configuration_setting(key=key)


def test_feature_flag_content_type_is_preserved(appconfig_client):
    name = make_flag_name()

    appconfig_client.set_configuration_setting(FeatureFlagConfigurationSetting(name, enabled=True))

    retrieved = appconfig_client.get_configuration_setting(
        key=f".appconfig.featureflag/{name}"
    )
    assert FEATURE_FLAG_CONTENT_TYPE.split(";")[0] in retrieved.content_type

    appconfig_client.delete_configuration_setting(key=f".appconfig.featureflag/{name}")
