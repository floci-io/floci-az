package io.floci.az.services.eventhub;

import io.floci.az.services.eventhub.ArtemisConfigGenerator.EntitySpec;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.CoreAddressConfiguration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.deployers.impl.FileConfigurationParser;
import org.apache.activemq.artemis.utils.XMLUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In Event Hubs a read never removes an event: an event stays until its hub's retention ends. The
 * broker config does that with non-destructive consumer group queues and a retention per queue,
 * which these check as Artemis itself parses them.
 */
class EventHubStreamConfigTest {

    private static final Pattern RETENTION_PROPERTY =
            Pattern.compile("<property key=\"retention\" value=\"([^\"]*)\"/>");

    @Test
    @DisplayName("every consumer group queue is non-destructive, and no other queue is")
    void consumerGroupQueuesAreNonDestructive() throws Exception {
        Map<String, Boolean> nonDestructive = new HashMap<>();
        for (CoreAddressConfiguration address : parse(brokerXml()).getAddressConfigurations()) {
            for (QueueConfiguration queue : address.getQueueConfigs()) {
                nonDestructive.put(queue.getName().toString(), Boolean.TRUE.equals(queue.isNonDestructive()));
            }
        }

        for (String queue : List.of(
                "eh1/ConsumerGroups/$Default/Partitions/0", "eh1/ConsumerGroups/audit/Partitions/1",
                "ns/eh1/$Default", "amqp://localhost/ns/eh1/audit")) {
            assertTrue(nonDestructive.get(queue), queue + " in " + nonDestructive);
        }
        for (String queue : List.of("eh1", "eh1/Partitions/0", "$cbs-intercept", "$management-intercept",
                "amqp://localhost/ns/eh1")) {
            assertFalse(nonDestructive.get(queue), queue + " in " + nonDestructive);
        }
    }

    @Test
    @DisplayName("the retention plugin gets every consumer group queue with its hub's retention")
    void retentionPluginGetsEveryConsumerGroupQueue() {
        Matcher property = RETENTION_PROPERTY.matcher(new ArtemisConfigGenerator(null).generate("ns", Map.of(
                "eh1", new EntitySpec(2, List.of("$Default"), Duration.ofSeconds(5)),
                "eh2", new EntitySpec(1, List.of("$Default")))));
        assertTrue(property.find());

        Map<String, Long> retention = new HashMap<>();
        for (String entry : property.group(1).split(",")) {
            retention.put(entry.substring(0, entry.lastIndexOf('=')), Long.parseLong(entry.substring(entry.lastIndexOf('=') + 1)));
        }

        assertEquals(5_000L, retention.get("eh1/ConsumerGroups/$Default/Partitions/0"));
        assertEquals(5_000L, retention.get("eh1/ConsumerGroups/$Default/Partitions/1"));
        assertEquals(5_000L, retention.get("ns/eh1/$Default"));
        assertEquals(5_000L, retention.get("amqp://localhost/ns/eh1/$Default"));
        assertEquals(3_600_000L, retention.get("eh2/ConsumerGroups/$Default/Partitions/0"));
        assertFalse(retention.containsKey("eh1"), retention.toString());
    }

    @Test
    @DisplayName("a hub keeps events for Azure's default hour unless its entry names a retention")
    void parsesTheRetention() {
        Map<String, EntitySpec> entities = ArtemisConfigGenerator.parseEntities("eh1:4,eh2:2:PT10M,eh3", "$Default");

        assertEquals(Duration.ofHours(1), entities.get("eh1").retention());
        assertEquals(Duration.ofMinutes(10), entities.get("eh2").retention());
        assertEquals(2, entities.get("eh2").partitionCount());
        assertEquals(Duration.ofHours(1), entities.get("eh3").retention());
    }

    /** Azure allows at most 90 days, on the premium and dedicated tiers. */
    @Test
    @DisplayName("a retention that is not a positive ISO-8601 duration of at most 90 days is refused")
    void refusesAnInvalidRetention() {
        for (String entities : List.of("eh1:4:ten", "eh1:4:PT0S", "eh1:4:-PT5S", "eh1:4:P91D")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ArtemisConfigGenerator.parseEntities(entities, "$Default"), entities);
        }
    }

    private static String brokerXml() {
        return new ArtemisConfigGenerator(null).generate("ns", Map.of(
                "eh1", new EntitySpec(2, List.of("$Default", "audit"))));
    }

    /** The core element as Artemis parses it. Its broker plugins are built into the broker's jar alone. */
    private static Configuration parse(String brokerXml) throws Exception {
        Element core = (Element) XMLUtil.streamToElement(
                new ByteArrayInputStream(brokerXml.getBytes(StandardCharsets.UTF_8))).getElementsByTagName("core").item(0);
        core.removeChild(core.getElementsByTagName("broker-plugins").item(0));
        Configuration parsed = new ConfigurationImpl();
        new FileConfigurationParser().parseMainConfig(core, parsed);
        return parsed;
    }
}
