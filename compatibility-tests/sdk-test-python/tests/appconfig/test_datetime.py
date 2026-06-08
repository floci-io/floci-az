import datetime
import time
import uuid
from azure.appconfiguration import ConfigurationSetting


def make_key(prefix="tt"):
    return f"{prefix}:{uuid.uuid4().hex[:12]}"


def test_accept_datetime_returns_historical_value(appconfig_client):
    key = make_key()

    appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="old"))
    time.sleep(0.05)
    between = datetime.datetime.now(datetime.timezone.utc)
    time.sleep(0.05)
    appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="new"))

    current = appconfig_client.get_configuration_setting(key=key)
    assert current.value == "new"

    as_of = appconfig_client.get_configuration_setting(key=key, accept_datetime=between)
    assert as_of.value == "old"


def test_accept_datetime_on_list(appconfig_client):
    key = make_key("ttlist")

    appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="v1"))
    time.sleep(0.05)
    between = datetime.datetime.now(datetime.timezone.utc)
    time.sleep(0.05)
    appconfig_client.set_configuration_setting(ConfigurationSetting(key=key, value="v2"))

    as_of = list(
        appconfig_client.list_configuration_settings(key_filter=key, accept_datetime=between)
    )
    assert len(as_of) == 1
    assert as_of[0].value == "v1"
