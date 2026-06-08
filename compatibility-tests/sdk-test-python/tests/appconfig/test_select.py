import uuid
from azure.appconfiguration import ConfigurationSetting


def make_key(prefix="sel"):
    return f"{prefix}:{uuid.uuid4().hex[:12]}"


def test_select_projects_only_requested_fields(appconfig_client):
    key = make_key()
    appconfig_client.set_configuration_setting(
        ConfigurationSetting(key=key, value="hello", content_type="text/plain")
    )

    results = list(
        appconfig_client.list_configuration_settings(key_filter=key, fields=["key", "value"])
    )
    assert len(results) == 1
    setting = results[0]
    assert setting.key == key
    assert setting.value == "hello"
    # Fields not selected are absent from the wire response → SDK leaves them unset.
    assert setting.content_type is None
