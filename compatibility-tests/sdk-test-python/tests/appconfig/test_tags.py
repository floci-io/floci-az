import uuid
from azure.appconfiguration import ConfigurationSetting


def test_tags_filter_uses_and_semantics(appconfig_client):
    prefix = f"tag-{uuid.uuid4().hex[:8]}"
    appconfig_client.set_configuration_setting(
        ConfigurationSetting(key=f"{prefix}-a", value="a", tags={"env": "prod", "tier": "web"})
    )
    appconfig_client.set_configuration_setting(
        ConfigurationSetting(key=f"{prefix}-b", value="b", tags={"env": "prod"})
    )
    appconfig_client.set_configuration_setting(
        ConfigurationSetting(key=f"{prefix}-c", value="c", tags={"env": "dev"})
    )

    # Single tag matches both prod settings.
    one_tag = list(
        appconfig_client.list_configuration_settings(key_filter=f"{prefix}-*", tags_filter=["env=prod"])
    )
    assert {s.key for s in one_tag} == {f"{prefix}-a", f"{prefix}-b"}

    # Two tags (AND) narrow to the single fully-matching setting.
    two_tags = list(
        appconfig_client.list_configuration_settings(
            key_filter=f"{prefix}-*", tags_filter=["env=prod", "tier=web"]
        )
    )
    assert [s.key for s in two_tags] == [f"{prefix}-a"]
