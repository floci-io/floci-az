package io.floci.az.compat;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.util.Context;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.models.ConfigurationSetting;
import com.azure.data.appconfiguration.models.ConfigurationSettingsFilter;
import com.azure.data.appconfiguration.models.ConfigurationSnapshot;
import com.azure.data.appconfiguration.models.ConfigurationSnapshotStatus;
import com.azure.data.appconfiguration.models.FeatureFlagConfigurationSetting;
import com.azure.data.appconfiguration.models.SettingFields;
import com.azure.data.appconfiguration.models.SettingSelector;
import org.junit.jupiter.api.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("App Configuration Compatibility")
class AppConfigCompatibilityTest {

    private ConfigurationClient client;

    @BeforeAll
    void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        client = EmulatorConfig.buildAppConfigClient();
    }

    private String key(String prefix) {
        return prefix + ":" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String flagName() {
        return "Flag" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    // -------------------------------------------------------------------------
    // Key-values
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("kv lifecycle: set → get → delete → not found")
    void kvLifecycle() {
        String k = key("test");

        client.setConfigurationSetting(k, null, "hello");

        ConfigurationSetting retrieved = client.getConfigurationSetting(k, null);
        assertEquals("hello", retrieved.getValue());

        client.deleteConfigurationSetting(k, null);

        HttpResponseException ex = assertThrows(HttpResponseException.class,
                () -> client.getConfigurationSetting(k, null));
        assertEquals(404, ex.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("kv update: second set replaces value")
    void kvUpdate() {
        String k = key("update");

        client.setConfigurationSetting(k, null, "v1");
        client.setConfigurationSetting(k, null, "v2");

        assertEquals("v2", client.getConfigurationSetting(k, null).getValue());

        client.deleteConfigurationSetting(k, null);
    }

    @Test
    @DisplayName("kv content-type is preserved")
    void kvContentType() {
        String k = key("ct");
        ConfigurationSetting setting = new ConfigurationSetting()
                .setKey(k)
                .setValue("{\"port\":8080}")
                .setContentType("application/json");

        client.setConfigurationSetting(setting);

        ConfigurationSetting retrieved = client.getConfigurationSetting(k, null);
        assertEquals("{\"port\":8080}", retrieved.getValue());
        assertEquals("application/json", retrieved.getContentType());

        client.deleteConfigurationSetting(k, null);
    }

    @Test
    @DisplayName("kv tags are preserved")
    void kvTags() {
        String k = key("tags");
        ConfigurationSetting setting = new ConfigurationSetting()
                .setKey(k)
                .setValue("tagged")
                .setTags(Map.of("env", "dev", "owner", "team-a"));

        client.setConfigurationSetting(setting);

        ConfigurationSetting retrieved = client.getConfigurationSetting(k, null);
        assertEquals("dev", retrieved.getTags().get("env"));
        assertEquals("team-a", retrieved.getTags().get("owner"));

        client.deleteConfigurationSetting(k, null);
    }

    @Test
    @DisplayName("kv list: all set keys appear in list")
    void kvList() {
        String prefix = "list-test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String k1 = prefix + ":a";
        String k2 = prefix + ":b";
        String k3 = prefix + ":c";

        client.setConfigurationSetting(k1, null, "1");
        client.setConfigurationSetting(k2, null, "2");
        client.setConfigurationSetting(k3, null, "3");

        List<String> keys = client.listConfigurationSettings(
                new SettingSelector().setKeyFilter(prefix + ":*"))
                .stream().map(ConfigurationSetting::getKey).toList();

        assertTrue(keys.contains(k1));
        assertTrue(keys.contains(k2));
        assertTrue(keys.contains(k3));

        client.deleteConfigurationSetting(k1, null);
        client.deleteConfigurationSetting(k2, null);
        client.deleteConfigurationSetting(k3, null);
    }

    @Test
    @DisplayName("kv list by key filter: only matching keys returned")
    void kvListByKeyFilter() {
        String prefix = "filter-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String m1 = prefix + ":match-1";
        String m2 = prefix + ":match-2";
        String other = key("other");

        client.setConfigurationSetting(m1, null, "x");
        client.setConfigurationSetting(m2, null, "x");
        client.setConfigurationSetting(other, null, "y");

        List<String> listed = client.listConfigurationSettings(
                new SettingSelector().setKeyFilter(prefix + ":*"))
                .stream().map(ConfigurationSetting::getKey).toList();

        assertTrue(listed.contains(m1));
        assertTrue(listed.contains(m2));
        assertFalse(listed.contains(other));

        client.deleteConfigurationSetting(m1, null);
        client.deleteConfigurationSetting(m2, null);
        client.deleteConfigurationSetting(other, null);
    }

    @Test
    @DisplayName("get non-existent key → HttpResponseException (404)")
    void kvNotFound() {
        HttpResponseException ex = assertThrows(HttpResponseException.class,
                () -> client.getConfigurationSetting("does-not-exist-xyz-abc", null));
        assertEquals(404, ex.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("delete non-existent key → HttpResponseException (404)")
    void kvDeleteNotFound() {
        HttpResponseException ex = assertThrows(HttpResponseException.class,
                () -> client.deleteConfigurationSetting("does-not-exist-xyz-abc", null));
        assertEquals(404, ex.getResponse().getStatusCode());
    }

    // -------------------------------------------------------------------------
    // Labels
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("labels isolate values: same key, different labels, independent values")
    void labelsIsolateValues() {
        String k = key("lbl");

        client.setConfigurationSetting(new ConfigurationSetting().setKey(k).setValue("prod-value").setLabel("prod"));
        client.setConfigurationSetting(new ConfigurationSetting().setKey(k).setValue("dev-value").setLabel("dev"));

        assertEquals("prod-value", client.getConfigurationSetting(k, "prod").getValue());
        assertEquals("dev-value",  client.getConfigurationSetting(k, "dev").getValue());

        client.deleteConfigurationSetting(k, "prod");
        client.deleteConfigurationSetting(k, "dev");
    }

    @Test
    @DisplayName("no-label and labeled are independent")
    void noLabelAndLabeledAreIndependent() {
        String k = key("nolbl");

        client.setConfigurationSetting(k, null, "default");
        client.setConfigurationSetting(new ConfigurationSetting().setKey(k).setValue("staging").setLabel("staging"));

        assertEquals("default",  client.getConfigurationSetting(k, null).getValue());
        assertEquals("staging",  client.getConfigurationSetting(k, "staging").getValue());

        client.deleteConfigurationSetting(k, null);
        client.deleteConfigurationSetting(k, "staging");
    }

    @Test
    @DisplayName("list by label filter: only matching label returned")
    void listByLabelFilter() {
        String prefix = "lbl-filter-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String k1 = prefix + ":1";
        String k2 = prefix + ":2";
        String kOther = prefix + ":other";

        client.setConfigurationSetting(new ConfigurationSetting().setKey(k1).setValue("x").setLabel("dev"));
        client.setConfigurationSetting(new ConfigurationSetting().setKey(k2).setValue("x").setLabel("dev"));
        client.setConfigurationSetting(new ConfigurationSetting().setKey(kOther).setValue("y").setLabel("prod"));

        List<String> listed = client.listConfigurationSettings(
                new SettingSelector().setKeyFilter(prefix + ":*").setLabelFilter("dev"))
                .stream().map(ConfigurationSetting::getKey).toList();

        assertTrue(listed.contains(k1));
        assertTrue(listed.contains(k2));
        assertFalse(listed.contains(kOther));

        client.deleteConfigurationSetting(k1, "dev");
        client.deleteConfigurationSetting(k2, "dev");
        client.deleteConfigurationSetting(kOther, "prod");
    }

    @Test
    @DisplayName("update only affects matching label")
    void updateOnlyAffectsMatchingLabel() {
        String k = key("lblupd");

        client.setConfigurationSetting(new ConfigurationSetting().setKey(k).setValue("original").setLabel("dev"));
        client.setConfigurationSetting(new ConfigurationSetting().setKey(k).setValue("original").setLabel("prod"));

        client.setConfigurationSetting(new ConfigurationSetting().setKey(k).setValue("updated").setLabel("dev"));

        assertEquals("updated",  client.getConfigurationSetting(k, "dev").getValue());
        assertEquals("original", client.getConfigurationSetting(k, "prod").getValue());

        client.deleteConfigurationSetting(k, "dev");
        client.deleteConfigurationSetting(k, "prod");
    }

    @Test
    @DisplayName("get with wrong label → HttpResponseException (404)")
    void getWithWrongLabel() {
        String k = key("wronglbl");
        client.setConfigurationSetting(new ConfigurationSetting().setKey(k).setValue("x").setLabel("dev"));

        HttpResponseException ex = assertThrows(HttpResponseException.class,
                () -> client.getConfigurationSetting(k, "nonexistent"));
        assertEquals(404, ex.getResponse().getStatusCode());

        client.deleteConfigurationSetting(k, "dev");
    }

    // -------------------------------------------------------------------------
    // Feature flags
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("feature flag lifecycle: set → get → delete → not found")
    void featureFlagLifecycle() {
        String name = flagName();
        String flagKey = ".appconfig.featureflag/" + name;

        client.setConfigurationSetting(new FeatureFlagConfigurationSetting(name, true));

        ConfigurationSetting retrieved = client.getConfigurationSetting(flagKey, null);
        assertNotNull(retrieved);

        client.deleteConfigurationSetting(flagKey, null);

        HttpResponseException ex = assertThrows(HttpResponseException.class,
                () -> client.getConfigurationSetting(flagKey, null));
        assertEquals(404, ex.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("feature flag enabled=true is preserved")
    void featureFlagEnabledPreserved() {
        String name = flagName();

        client.setConfigurationSetting(new FeatureFlagConfigurationSetting(name, true));

        ConfigurationSetting retrieved = client.getConfigurationSetting(
                ".appconfig.featureflag/" + name, null);
        assertInstanceOf(FeatureFlagConfigurationSetting.class, retrieved);
        assertTrue(((FeatureFlagConfigurationSetting) retrieved).isEnabled());

        client.deleteConfigurationSetting(".appconfig.featureflag/" + name, null);
    }

    @Test
    @DisplayName("feature flag enabled=false is preserved")
    void featureFlagDisabledPreserved() {
        String name = flagName();

        client.setConfigurationSetting(new FeatureFlagConfigurationSetting(name, false));

        ConfigurationSetting retrieved = client.getConfigurationSetting(
                ".appconfig.featureflag/" + name, null);
        assertInstanceOf(FeatureFlagConfigurationSetting.class, retrieved);
        assertFalse(((FeatureFlagConfigurationSetting) retrieved).isEnabled());

        client.deleteConfigurationSetting(".appconfig.featureflag/" + name, null);
    }

    @Test
    @DisplayName("feature flag toggle: enabled → disabled")
    void featureFlagToggle() {
        String name = flagName();

        client.setConfigurationSetting(new FeatureFlagConfigurationSetting(name, true));
        client.setConfigurationSetting(new FeatureFlagConfigurationSetting(name, false));

        ConfigurationSetting retrieved = client.getConfigurationSetting(
                ".appconfig.featureflag/" + name, null);
        assertInstanceOf(FeatureFlagConfigurationSetting.class, retrieved);
        assertFalse(((FeatureFlagConfigurationSetting) retrieved).isEnabled());

        client.deleteConfigurationSetting(".appconfig.featureflag/" + name, null);
    }

    @Test
    @DisplayName("feature flag appears in list")
    void featureFlagInList() {
        String name = flagName();
        String flagKey = ".appconfig.featureflag/" + name;

        client.setConfigurationSetting(new FeatureFlagConfigurationSetting(name, true));

        List<String> allKeys = client.listConfigurationSettings(new SettingSelector())
                .stream().map(ConfigurationSetting::getKey).toList();
        assertTrue(allKeys.contains(flagKey));

        client.deleteConfigurationSetting(flagKey, null);
    }

    @Test
    @DisplayName("feature flag content-type is preserved")
    void featureFlagContentType() {
        String name = flagName();

        client.setConfigurationSetting(new FeatureFlagConfigurationSetting(name, true));

        ConfigurationSetting retrieved = client.getConfigurationSetting(
                ".appconfig.featureflag/" + name, null);
        assertNotNull(retrieved.getContentType());
        assertTrue(retrieved.getContentType().contains("application/vnd.microsoft.appconfig.ff+json"));

        client.deleteConfigurationSetting(".appconfig.featureflag/" + name, null);
    }

    // -------------------------------------------------------------------------
    // ETags / conditional operations
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("etag is returned on set")
    void etagReturnedOnSet() {
        String k = key("etag");

        ConfigurationSetting result = client.setConfigurationSetting(k, null, "v1");
        assertNotNull(result.getETag());
        assertFalse(result.getETag().isBlank());

        client.deleteConfigurationSetting(k, null);
    }

    @Test
    @DisplayName("etag changes on update")
    void etagChangesOnUpdate() {
        String k = key("etag2");

        ConfigurationSetting r1 = client.setConfigurationSetting(k, null, "v1");
        ConfigurationSetting r2 = client.setConfigurationSetting(k, null, "v2");

        assertNotEquals(r1.getETag(), r2.getETag());

        client.deleteConfigurationSetting(k, null);
    }

    @Test
    @DisplayName("conditional update with correct etag succeeds")
    void conditionalUpdateCorrectEtag() {
        String k = key("etag3");

        ConfigurationSetting created = client.setConfigurationSetting(k, null, "original");

        ConfigurationSetting toUpdate = new ConfigurationSetting()
                .setKey(k).setValue("updated").setETag(created.getETag());
        ConfigurationSetting result = client.setConfigurationSettingWithResponse(
                toUpdate, true, null).getValue();

        assertEquals("updated", result.getValue());

        client.deleteConfigurationSetting(k, null);
    }

    @Test
    @DisplayName("conditional update with wrong etag → HttpResponseException (412)")
    void conditionalUpdateWrongEtag() {
        String k = key("etag4");

        client.setConfigurationSetting(k, null, "original");

        ConfigurationSetting stale = new ConfigurationSetting()
                .setKey(k).setValue("conflict").setETag("wrong-etag-value");

        HttpResponseException ex = assertThrows(HttpResponseException.class,
                () -> client.setConfigurationSettingWithResponse(stale, true, null));
        assertEquals(412, ex.getResponse().getStatusCode());

        client.deleteConfigurationSetting(k, null);
    }

    @Test
    @DisplayName("conditional delete with correct etag succeeds")
    void conditionalDeleteCorrectEtag() {
        String k = key("etag5");

        ConfigurationSetting created = client.setConfigurationSetting(k, null, "to-delete");

        client.deleteConfigurationSettingWithResponse(created, true, null);

        HttpResponseException ex = assertThrows(HttpResponseException.class,
                () -> client.getConfigurationSetting(k, null));
        assertEquals(404, ex.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("conditional delete with wrong etag → HttpResponseException (412)")
    void conditionalDeleteWrongEtag() {
        String k = key("etag6");

        client.setConfigurationSetting(k, null, "protected");

        ConfigurationSetting stale = new ConfigurationSetting()
                .setKey(k).setETag("wrong-etag");

        HttpResponseException ex = assertThrows(HttpResponseException.class,
                () -> client.deleteConfigurationSettingWithResponse(stale, true, null));
        assertEquals(412, ex.getResponse().getStatusCode());

        // value still exists
        assertEquals("protected", client.getConfigurationSetting(k, null).getValue());

        client.deleteConfigurationSetting(k, null);
    }

    // -------------------------------------------------------------------------
    // Pagination
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("list pages transparently across >100 settings")
    void listPaginatesBeyondOnePage() {
        String prefix = "page-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        for (int i = 0; i < 120; i++) {
            client.setConfigurationSetting(prefix + ":" + String.format("%03d", i), null, "v");
        }

        long count = client.listConfigurationSettings(
                        new SettingSelector().setKeyFilter(prefix + ":*"))
                .stream().count();
        assertEquals(120, count);
    }

    // -------------------------------------------------------------------------
    // $select projection
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("$select returns only requested fields")
    void selectProjectsFields() {
        String k = key("sel");
        client.setConfigurationSetting(new ConfigurationSetting()
                .setKey(k).setValue("hello").setContentType("text/plain"));

        ConfigurationSetting projected = client.listConfigurationSettings(
                        new SettingSelector().setKeyFilter(k)
                                .setFields(SettingFields.KEY, SettingFields.VALUE))
                .stream().findFirst().orElseThrow();

        assertEquals(k, projected.getKey());
        assertEquals("hello", projected.getValue());
        assertNull(projected.getContentType());

        client.deleteConfigurationSetting(k, null);
    }

    // -------------------------------------------------------------------------
    // Tags filtering
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("tags filter uses AND semantics")
    void tagsFilterAndSemantics() {
        String prefix = "tag-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        client.setConfigurationSetting(new ConfigurationSetting()
                .setKey(prefix + ":a").setValue("a").setTags(Map.of("env", "prod", "tier", "web")));
        client.setConfigurationSetting(new ConfigurationSetting()
                .setKey(prefix + ":b").setValue("b").setTags(Map.of("env", "prod")));
        client.setConfigurationSetting(new ConfigurationSetting()
                .setKey(prefix + ":c").setValue("c").setTags(Map.of("env", "dev")));

        long oneTag = client.listConfigurationSettings(new SettingSelector()
                        .setKeyFilter(prefix + ":*").setTagsFilter(List.of("env=prod")))
                .stream().count();
        assertEquals(2, oneTag);

        List<String> twoTags = client.listConfigurationSettings(new SettingSelector()
                        .setKeyFilter(prefix + ":*").setTagsFilter(List.of("env=prod", "tier=web")))
                .stream().map(ConfigurationSetting::getKey).toList();
        assertEquals(List.of(prefix + ":a"), twoTags);

        client.deleteConfigurationSetting(prefix + ":a", null);
        client.deleteConfigurationSetting(prefix + ":b", null);
        client.deleteConfigurationSetting(prefix + ":c", null);
    }

    // -------------------------------------------------------------------------
    // Accept-Datetime time-travel
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("accept-datetime list returns historical value")
    void acceptDatetimeTimeTravel() throws InterruptedException {
        String k = key("tt");

        // The Java SDK sends Accept-Datetime as RFC1123 (whole-second resolution), so the two
        // writes must straddle a full second for the as-of read to distinguish them.
        client.setConfigurationSetting(k, null, "old");
        Thread.sleep(1100);
        OffsetDateTime between = OffsetDateTime.now(ZoneOffset.UTC);
        Thread.sleep(1100);
        client.setConfigurationSetting(k, null, "new");

        assertEquals("new", client.getConfigurationSetting(k, null).getValue());

        ConfigurationSetting asOf = client.listConfigurationSettings(
                        new SettingSelector().setKeyFilter(k).setAcceptDatetime(between))
                .stream().findFirst().orElseThrow();
        assertEquals("old", asOf.getValue());

        client.deleteConfigurationSetting(k, null);
    }

    // -------------------------------------------------------------------------
    // Snapshots (async provisioning poller)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("beginCreateSnapshot polls to a READY snapshot")
    void snapshotCreatePoller() {
        String prefix = "snap-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        client.setConfigurationSetting(prefix + ":a", null, "1");
        client.setConfigurationSetting(prefix + ":b", null, "2");

        String snapshotName = "s-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        ConfigurationSnapshot snapshot = client.beginCreateSnapshot(
                        snapshotName,
                        new ConfigurationSnapshot(List.of(new ConfigurationSettingsFilter(prefix + ":*"))),
                        Context.NONE)
                .getFinalResult();

        assertEquals(ConfigurationSnapshotStatus.READY, snapshot.getStatus());

        long inSnapshot = client.listConfigurationSettingsForSnapshot(snapshotName).stream().count();
        assertEquals(2, inSnapshot);

        client.archiveSnapshot(snapshotName);
    }
}
