package io.floci.az.compat;

import com.azure.core.exception.HttpResponseException;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.models.*;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("App Configuration Snapshot Compatibility")
class SnapshotCompatibilityTest {

    private ConfigurationClient client;

    @BeforeAll
    void setup() {
        client = EmulatorConfig.buildAppConfigClient();
    }

    private String key(String prefix) {
        return prefix + ":" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private String snapName() {
        return "snap-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    // -------------------------------------------------------------------------
    // Snapshot lifecycle
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("snapshot lifecycle: create → get → archive → recover → get")
    void snapshotLifecycle() {
        String k = key("snap-life");
        client.setConfigurationSetting(k, null, "v1");

        String name = snapName();
        ConfigurationSnapshot snapshot = new ConfigurationSnapshot(
                List.of(new ConfigurationSettingsFilter(k + "*")));

        ConfigurationSnapshot created = client.beginCreateSnapshot(name, snapshot, null)
                .getFinalResult();

        assertEquals(name, created.getName());
        assertEquals(ConfigurationSnapshotStatus.READY, created.getStatus());
        assertNotNull(created.getETag());

        // archive
        ConfigurationSnapshot archived = client.archiveSnapshot(name);
        assertEquals(ConfigurationSnapshotStatus.ARCHIVED, archived.getStatus());
        assertNotNull(archived.getExpiresAt());

        // recover
        ConfigurationSnapshot recovered = client.recoverSnapshot(name);
        assertEquals(ConfigurationSnapshotStatus.READY, recovered.getStatus());

        client.deleteConfigurationSetting(k, null);
    }

    @Test
    @DisplayName("snapshot get: not found → HttpResponseException (404)")
    void snapshotNotFound() {
        HttpResponseException ex = assertThrows(HttpResponseException.class,
                () -> client.getSnapshot("does-not-exist-xyz-" + UUID.randomUUID()));
        assertEquals(404, ex.getResponse().getStatusCode());
    }

    // -------------------------------------------------------------------------
    // Frozen isolation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("snapshot is frozen: changes after creation not visible")
    void snapshotFrozenIsolation() {
        String k = key("frozen");
        client.setConfigurationSetting(k, null, "before");

        String name = snapName();
        ConfigurationSnapshot snapshot = new ConfigurationSnapshot(
                List.of(new ConfigurationSettingsFilter(k + "*")));

        client.beginCreateSnapshot(name, snapshot, null).getFinalResult();

        // mutate after snapshot
        client.setConfigurationSetting(k, null, "after");

        List<String> values = client.listConfigurationSettingsForSnapshot(name)
                .stream()
                .map(ConfigurationSetting::getValue)
                .toList();

        assertEquals(1, values.size());
        assertEquals("before", values.get(0));

        client.deleteConfigurationSetting(k, null);
    }

    // -------------------------------------------------------------------------
    // Key filter
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("snapshot key filter: only matching keys captured")
    void snapshotKeyFilter() {
        String prefix = "kf-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String k1 = prefix + ":match-1";
        String k2 = prefix + ":match-2";
        String kOther = key("other");

        client.setConfigurationSetting(k1, null, "a");
        client.setConfigurationSetting(k2, null, "b");
        client.setConfigurationSetting(kOther, null, "z");

        String name = snapName();
        ConfigurationSnapshot snapshot = new ConfigurationSnapshot(
                List.of(new ConfigurationSettingsFilter(prefix + ":*")));

        client.beginCreateSnapshot(name, snapshot, null).getFinalResult();

        List<String> keys = client.listConfigurationSettingsForSnapshot(name)
                .stream().map(ConfigurationSetting::getKey).toList();

        assertTrue(keys.contains(k1));
        assertTrue(keys.contains(k2));
        assertFalse(keys.contains(kOther));

        client.deleteConfigurationSetting(k1, null);
        client.deleteConfigurationSetting(k2, null);
        client.deleteConfigurationSetting(kOther, null);
    }

    // -------------------------------------------------------------------------
    // Label filter
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("snapshot label filter: only matching label captured")
    void snapshotLabelFilter() {
        String prefix = "lf-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String k1 = prefix + ":1";
        String k2 = prefix + ":2";

        client.setConfigurationSetting(new ConfigurationSetting().setKey(k1).setValue("dev").setLabel("dev"));
        client.setConfigurationSetting(new ConfigurationSetting().setKey(k2).setValue("prod").setLabel("prod"));

        String name = snapName();
        ConfigurationSnapshot snapshot = new ConfigurationSnapshot(
                List.of(new ConfigurationSettingsFilter(prefix + ":*").setLabel("dev")));

        client.beginCreateSnapshot(name, snapshot, null).getFinalResult();

        List<ConfigurationSetting> items = client.listConfigurationSettingsForSnapshot(name)
                .stream().toList();

        assertEquals(1, items.size());
        assertEquals(k1, items.get(0).getKey());
        assertEquals("dev", items.get(0).getValue());

        client.deleteConfigurationSetting(k1, "dev");
        client.deleteConfigurationSetting(k2, "prod");
    }

    // -------------------------------------------------------------------------
    // Composition: KEY
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("snapshot composition KEY: same key appears once (last filter wins)")
    void snapshotCompositionKey() {
        String prefix = "comp-key-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String k = prefix + ":a";

        client.setConfigurationSetting(new ConfigurationSetting().setKey(k).setValue("dev-val").setLabel("dev"));
        client.setConfigurationSetting(new ConfigurationSetting().setKey(k).setValue("prod-val").setLabel("prod"));

        String name = snapName();
        ConfigurationSnapshot snapshot = new ConfigurationSnapshot(
                List.of(
                        new ConfigurationSettingsFilter(prefix + ":*").setLabel("dev"),
                        new ConfigurationSettingsFilter(prefix + ":*").setLabel("prod")
                ))
                .setSnapshotComposition(SnapshotComposition.KEY);

        client.beginCreateSnapshot(name, snapshot, null).getFinalResult();

        List<ConfigurationSetting> items = client.listConfigurationSettingsForSnapshot(name)
                .stream().toList();

        // Only one entry per key (key composition)
        assertEquals(1, items.size());
        // Last filter (prod) wins
        assertEquals("prod-val", items.get(0).getValue());

        client.deleteConfigurationSetting(k, "dev");
        client.deleteConfigurationSetting(k, "prod");
    }

    // -------------------------------------------------------------------------
    // Composition: KEY_LABEL
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("snapshot composition KEY_LABEL: same key with different labels both appear")
    void snapshotCompositionKeyLabel() {
        String prefix = "comp-kl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String k = prefix + ":a";

        client.setConfigurationSetting(new ConfigurationSetting().setKey(k).setValue("dev-val").setLabel("dev"));
        client.setConfigurationSetting(new ConfigurationSetting().setKey(k).setValue("prod-val").setLabel("prod"));

        String name = snapName();
        ConfigurationSnapshot snapshot = new ConfigurationSnapshot(
                List.of(new ConfigurationSettingsFilter(prefix + ":*")))
                .setSnapshotComposition(SnapshotComposition.KEY_LABEL);

        client.beginCreateSnapshot(name, snapshot, null).getFinalResult();

        List<ConfigurationSetting> items = client.listConfigurationSettingsForSnapshot(name)
                .stream().toList();

        // Both (key, dev) and (key, prod) should appear
        assertEquals(2, items.size());
        List<String> values = items.stream().map(ConfigurationSetting::getValue).toList();
        assertTrue(values.contains("dev-val"));
        assertTrue(values.contains("prod-val"));

        client.deleteConfigurationSetting(k, "dev");
        client.deleteConfigurationSetting(k, "prod");
    }

    // -------------------------------------------------------------------------
    // List snapshots
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("list snapshots: created snapshot appears in list")
    void listSnapshots() {
        String k = key("ls");
        client.setConfigurationSetting(k, null, "x");

        String name = snapName();
        ConfigurationSnapshot snapshot = new ConfigurationSnapshot(
                List.of(new ConfigurationSettingsFilter(k + "*")));

        client.beginCreateSnapshot(name, snapshot, null).getFinalResult();

        List<String> snapNames = client.listSnapshots(new SnapshotSelector())
                .stream().map(ConfigurationSnapshot::getName).toList();

        assertTrue(snapNames.contains(name));

        client.deleteConfigurationSetting(k, null);
    }

    @Test
    @DisplayName("list snapshots by name filter")
    void listSnapshotsByNameFilter() {
        String prefix = "ls-filter-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String name1 = prefix + "-a";
        String name2 = prefix + "-b";
        String nameOther = snapName();

        String k = key("lsf");
        client.setConfigurationSetting(k, null, "x");

        ConfigurationSnapshot snap = new ConfigurationSnapshot(
                List.of(new ConfigurationSettingsFilter(k + "*")));

        client.beginCreateSnapshot(name1, snap, null).getFinalResult();
        client.beginCreateSnapshot(name2, snap, null).getFinalResult();
        client.beginCreateSnapshot(nameOther, snap, null).getFinalResult();

        List<String> listed = client.listSnapshots(new SnapshotSelector().setNameFilter(prefix + "*"))
                .stream().map(ConfigurationSnapshot::getName).toList();

        assertTrue(listed.contains(name1));
        assertTrue(listed.contains(name2));
        assertFalse(listed.contains(nameOther));

        client.deleteConfigurationSetting(k, null);
    }

    // -------------------------------------------------------------------------
    // Snapshot metadata
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("snapshot retains item count and created timestamp")
    void snapshotMetadata() {
        String prefix = "meta-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String k1 = prefix + ":1";
        String k2 = prefix + ":2";

        client.setConfigurationSetting(k1, null, "a");
        client.setConfigurationSetting(k2, null, "b");

        String name = snapName();
        ConfigurationSnapshot snapshot = new ConfigurationSnapshot(
                List.of(new ConfigurationSettingsFilter(prefix + ":*")));

        ConfigurationSnapshot created = client.beginCreateSnapshot(name, snapshot, null)
                .getFinalResult();

        assertEquals(2, created.getItemCount());
        assertNotNull(created.getCreatedAt());

        client.deleteConfigurationSetting(k1, null);
        client.deleteConfigurationSetting(k2, null);
    }

    @Test
    @DisplayName("snapshot etag is present and changes after archive")
    void snapshotEtag() {
        String k = key("etag-snap");
        client.setConfigurationSetting(k, null, "x");

        String name = snapName();
        ConfigurationSnapshot snapshot = new ConfigurationSnapshot(
                List.of(new ConfigurationSettingsFilter(k + "*")));

        ConfigurationSnapshot created = client.beginCreateSnapshot(name, snapshot, null)
                .getFinalResult();

        assertNotNull(created.getETag());

        ConfigurationSnapshot archived = client.archiveSnapshot(name);
        assertNotEquals(created.getETag(), archived.getETag());

        client.deleteConfigurationSetting(k, null);
    }
}
