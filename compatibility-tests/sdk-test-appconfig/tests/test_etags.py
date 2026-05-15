import pytest
import uuid
from azure.appconfiguration import ConfigurationSetting
from azure.core import MatchConditions
from azure.core.exceptions import ResourceModifiedError, ResourceNotFoundError


def make_key():
    return f"etag-test:{uuid.uuid4().hex[:12]}"


def test_etag_returned_on_set(appconfig_client):
    key = make_key()

    result = appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="v1"))
    assert result.etag is not None
    assert len(result.etag) > 0

    appconfig_client.delete_configuration_setting(key=key)


def test_etag_changes_on_update(appconfig_client):
    key = make_key()

    r1 = appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="v1"))
    r2 = appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="v2"))

    assert r1.etag != r2.etag

    appconfig_client.delete_configuration_setting(key=key)


def test_conditional_update_with_correct_etag(appconfig_client):
    key = make_key()

    created = appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="original"))

    updated = ConfigurationSetting(key=key, value="updated", etag=created.etag)
    result = appconfig_client.set_configuration_setting(updated, match_condition=MatchConditions.IfNotModified)
    assert result.value == "updated"

    appconfig_client.delete_configuration_setting(key=key)


def test_conditional_update_with_wrong_etag_fails(appconfig_client):
    key = make_key()

    appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="original"))

    stale = ConfigurationSetting(key=key, value="conflict", etag="wrong-etag-value")
    with pytest.raises(ResourceModifiedError):
        appconfig_client.set_configuration_setting(stale, match_condition=MatchConditions.IfNotModified)

    appconfig_client.delete_configuration_setting(key=key)


def test_conditional_delete_with_correct_etag(appconfig_client):
    key = make_key()

    created = appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="to-delete"))

    appconfig_client.delete_configuration_setting(
        key=key,
        etag=created.etag,
        match_condition=MatchConditions.IfNotModified
    )

    with pytest.raises(ResourceNotFoundError):
        appconfig_client.get_configuration_setting(key=key)


def test_conditional_delete_with_wrong_etag_fails(appconfig_client):
    key = make_key()

    appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="protected"))

    with pytest.raises(ResourceModifiedError):
        appconfig_client.delete_configuration_setting(
            key=key,
            etag="wrong-etag",
            match_condition=MatchConditions.IfNotModified
        )

    # value still there
    retrieved = appconfig_client.get_configuration_setting(key=key)
    assert retrieved.value == "protected"

    appconfig_client.delete_configuration_setting(key=key)
