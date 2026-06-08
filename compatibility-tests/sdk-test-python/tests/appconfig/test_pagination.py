import uuid
from azure.appconfiguration import ConfigurationSetting


def test_list_follows_nextlink_across_pages(appconfig_client):
    """The SDK transparently follows @nextLink; all 150 settings must come back."""
    prefix = f"pg-{uuid.uuid4().hex[:8]}"
    for i in range(150):
        appconfig_client.set_configuration_setting(
            ConfigurationSetting(key=f"{prefix}-{i:03d}", value="v")
        )

    items = list(appconfig_client.list_configuration_settings(key_filter=f"{prefix}-*"))
    keys = {s.key for s in items}
    assert len(keys) == 150


def test_first_page_is_capped_at_100(appconfig_client):
    """Azure App Configuration returns at most 100 items per page."""
    prefix = f"pgpage-{uuid.uuid4().hex[:8]}"
    for i in range(150):
        appconfig_client.set_configuration_setting(
            ConfigurationSetting(key=f"{prefix}-{i:03d}", value="v")
        )

    # Consume each page iterator exactly once.
    page_sizes = [
        len(list(page))
        for page in appconfig_client.list_configuration_settings(key_filter=f"{prefix}-*").by_page()
    ]
    assert page_sizes[0] == 100
    assert sum(page_sizes) == 150
