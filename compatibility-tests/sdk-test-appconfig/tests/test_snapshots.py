"""Snapshot compatibility tests for the App Configuration emulator."""
import uuid
import pytest
from azure.appconfiguration import (
    ConfigurationSetting,
    ConfigurationSettingsFilter,
    SnapshotComposition,
    SnapshotStatus,
)
from azure.core.exceptions import HttpResponseError


def unique_key(prefix="snap"):
    return f"{prefix}:{uuid.uuid4().hex[:10]}"


def snap_name():
    return f"snap-{uuid.uuid4().hex[:10]}"


# ---------------------------------------------------------------------------
# Snapshot lifecycle
# ---------------------------------------------------------------------------

def test_snapshot_lifecycle(appconfig_client):
    """create → get → archive → recover"""
    k = unique_key("life")
    appconfig_client.set_configuration_setting(ConfigurationSetting(key=k, value="v1"))

    name = snap_name()
    created = appconfig_client.begin_create_snapshot(
        name=name,
        filters=[ConfigurationSettingsFilter(key=k + "*")],
    ).result()

    assert created.name == name
    assert created.status == SnapshotStatus.READY
    assert created.etag is not None

    archived = appconfig_client.archive_snapshot(name)
    assert archived.status == SnapshotStatus.ARCHIVED
    assert archived.expires is not None

    recovered = appconfig_client.recover_snapshot(name)
    assert recovered.status == SnapshotStatus.READY

    appconfig_client.delete_configuration_setting(key=k)


def test_snapshot_not_found(appconfig_client):
    """get non-existent snapshot → 404"""
    with pytest.raises(HttpResponseError) as exc_info:
        appconfig_client.get_snapshot("does-not-exist-" + uuid.uuid4().hex)
    assert exc_info.value.status_code == 404


# ---------------------------------------------------------------------------
# Frozen isolation
# ---------------------------------------------------------------------------

def test_snapshot_frozen_isolation(appconfig_client):
    """changes after snapshot creation must not be visible in snapshot"""
    k = unique_key("frozen")
    appconfig_client.set_configuration_setting(ConfigurationSetting(key=k, value="before"))

    name = snap_name()
    appconfig_client.begin_create_snapshot(
        name=name,
        filters=[ConfigurationSettingsFilter(key=k + "*")],
    ).result()

    appconfig_client.set_configuration_setting(ConfigurationSetting(key=k, value="after"))

    items = list(appconfig_client.list_configuration_settings(snapshot_name=name))
    assert len(items) == 1
    assert items[0].value == "before"

    appconfig_client.delete_configuration_setting(key=k)


# ---------------------------------------------------------------------------
# Key filter
# ---------------------------------------------------------------------------

def test_snapshot_key_filter(appconfig_client):
    prefix = f"kf-{uuid.uuid4().hex[:8]}"
    k1 = f"{prefix}:match-1"
    k2 = f"{prefix}:match-2"
    k_other = unique_key("other")

    for k, v in [(k1, "a"), (k2, "b"), (k_other, "z")]:
        appconfig_client.set_configuration_setting(ConfigurationSetting(key=k, value=v))

    name = snap_name()
    appconfig_client.begin_create_snapshot(
        name=name,
        filters=[ConfigurationSettingsFilter(key=f"{prefix}:*")],
    ).result()

    keys = [s.key for s in appconfig_client.list_configuration_settings(snapshot_name=name)]
    assert k1 in keys
    assert k2 in keys
    assert k_other not in keys

    for k in [k1, k2, k_other]:
        appconfig_client.delete_configuration_setting(key=k)


# ---------------------------------------------------------------------------
# Label filter
# ---------------------------------------------------------------------------

def test_snapshot_label_filter(appconfig_client):
    prefix = f"lf-{uuid.uuid4().hex[:8]}"
    k1 = f"{prefix}:1"
    k2 = f"{prefix}:2"

    appconfig_client.set_configuration_setting(ConfigurationSetting(key=k1, value="dev", label="dev"))
    appconfig_client.set_configuration_setting(ConfigurationSetting(key=k2, value="prod", label="prod"))

    name = snap_name()
    appconfig_client.begin_create_snapshot(
        name=name,
        filters=[ConfigurationSettingsFilter(key=f"{prefix}:*", label="dev")],
    ).result()

    items = list(appconfig_client.list_configuration_settings(snapshot_name=name))
    assert len(items) == 1
    assert items[0].key == k1
    assert items[0].value == "dev"

    appconfig_client.delete_configuration_setting(key=k1, label="dev")
    appconfig_client.delete_configuration_setting(key=k2, label="prod")


# ---------------------------------------------------------------------------
# Composition: KEY
# ---------------------------------------------------------------------------

def test_snapshot_composition_key(appconfig_client):
    """KEY composition: same key appears once, last filter wins."""
    prefix = f"comp-key-{uuid.uuid4().hex[:8]}"
    k = f"{prefix}:a"

    appconfig_client.set_configuration_setting(ConfigurationSetting(key=k, value="dev-val", label="dev"))
    appconfig_client.set_configuration_setting(ConfigurationSetting(key=k, value="prod-val", label="prod"))

    name = snap_name()
    appconfig_client.begin_create_snapshot(
        name=name,
        filters=[
            ConfigurationSettingsFilter(key=f"{prefix}:*", label="dev"),
            ConfigurationSettingsFilter(key=f"{prefix}:*", label="prod"),
        ],
        composition_type=SnapshotComposition.KEY,
    ).result()

    items = list(appconfig_client.list_configuration_settings(snapshot_name=name))
    assert len(items) == 1
    assert items[0].value == "prod-val"

    appconfig_client.delete_configuration_setting(key=k, label="dev")
    appconfig_client.delete_configuration_setting(key=k, label="prod")


# ---------------------------------------------------------------------------
# Composition: KEY_LABEL
# ---------------------------------------------------------------------------

def test_snapshot_composition_key_label(appconfig_client):
    """KEY_LABEL composition: same key with different labels both appear."""
    prefix = f"comp-kl-{uuid.uuid4().hex[:8]}"
    k = f"{prefix}:a"

    appconfig_client.set_configuration_setting(ConfigurationSetting(key=k, value="dev-val", label="dev"))
    appconfig_client.set_configuration_setting(ConfigurationSetting(key=k, value="prod-val", label="prod"))

    name = snap_name()
    appconfig_client.begin_create_snapshot(
        name=name,
        filters=[ConfigurationSettingsFilter(key=f"{prefix}:*")],
        composition_type=SnapshotComposition.KEY_LABEL,
    ).result()

    items = list(appconfig_client.list_configuration_settings(snapshot_name=name))
    assert len(items) == 2
    values = {s.value for s in items}
    assert "dev-val" in values
    assert "prod-val" in values

    appconfig_client.delete_configuration_setting(key=k, label="dev")
    appconfig_client.delete_configuration_setting(key=k, label="prod")


# ---------------------------------------------------------------------------
# List snapshots
# ---------------------------------------------------------------------------

def test_list_snapshots(appconfig_client):
    """created snapshot appears in list"""
    k = unique_key("ls")
    appconfig_client.set_configuration_setting(ConfigurationSetting(key=k, value="x"))

    name = snap_name()
    appconfig_client.begin_create_snapshot(
        name=name,
        filters=[ConfigurationSettingsFilter(key=k + "*")],
    ).result()

    names = [s.name for s in appconfig_client.list_snapshots()]
    assert name in names

    appconfig_client.delete_configuration_setting(key=k)


def test_list_snapshots_by_name_filter(appconfig_client):
    prefix = f"ls-filter-{uuid.uuid4().hex[:8]}"
    name1 = f"{prefix}-a"
    name2 = f"{prefix}-b"
    name_other = snap_name()

    k = unique_key("lsf")
    appconfig_client.set_configuration_setting(ConfigurationSetting(key=k, value="x"))
    filters = [ConfigurationSettingsFilter(key=k + "*")]

    for n in [name1, name2, name_other]:
        appconfig_client.begin_create_snapshot(name=n, filters=filters).result()

    listed = [s.name for s in appconfig_client.list_snapshots(name=prefix + "*")]
    assert name1 in listed
    assert name2 in listed
    assert name_other not in listed

    appconfig_client.delete_configuration_setting(key=k)


# ---------------------------------------------------------------------------
# Snapshot metadata
# ---------------------------------------------------------------------------

def test_snapshot_item_count(appconfig_client):
    prefix = f"meta-{uuid.uuid4().hex[:8]}"
    k1 = f"{prefix}:1"
    k2 = f"{prefix}:2"

    for k in [k1, k2]:
        appconfig_client.set_configuration_setting(ConfigurationSetting(key=k, value="v"))

    name = snap_name()
    created = appconfig_client.begin_create_snapshot(
        name=name,
        filters=[ConfigurationSettingsFilter(key=f"{prefix}:*")],
    ).result()

    assert created.items_count == 2
    assert created.created is not None

    for k in [k1, k2]:
        appconfig_client.delete_configuration_setting(key=k)


def test_snapshot_etag_changes_on_archive(appconfig_client):
    k = unique_key("etag-snap")
    appconfig_client.set_configuration_setting(ConfigurationSetting(key=k, value="x"))

    name = snap_name()
    created = appconfig_client.begin_create_snapshot(
        name=name,
        filters=[ConfigurationSettingsFilter(key=k + "*")],
    ).result()

    assert created.etag is not None
    archived = appconfig_client.archive_snapshot(name)
    assert archived.etag != created.etag

    appconfig_client.delete_configuration_setting(key=k)
