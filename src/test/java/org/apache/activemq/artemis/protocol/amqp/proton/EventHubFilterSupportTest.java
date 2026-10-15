package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.filter.Filter;
import org.apache.activemq.artemis.core.filter.impl.FilterImpl;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPMessage;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPStandardMessage;
import org.apache.activemq.artemis.utils.collections.TypedProperties;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every Event Hubs start position arrives as a selector, and the SDKs do not write them the same
 * way — so these cover each spelling one of them emits, rather than letting the shape a single
 * client happens to send stand in for all of them.
 */
class EventHubFilterSupportTest {

    private static URLClassLoader loader;
    private static Method rewriteSelector;

    @BeforeAll
    static void loadFromPatchJar() throws Exception {
        loader = PatchJarLoader.open();
        rewriteSelector = Class.forName(
                        "org.apache.activemq.artemis.protocol.amqp.proton.EventHubFilterSupport",
                        true, loader)
                .getMethod("rewriteSelector", String.class);
    }

    @AfterAll
    static void close() throws Exception {
        loader.close();
    }

    private static String rewrite(String selector) throws Exception {
        return (String) rewriteSelector.invoke(null, selector);
    }

    /**
     * The Java and Rust SDKs quote the operand; the .NET one does not, building it straight into
     * the expression. Both mean the same position, and requiring the quotes refused a .NET
     * consumer's attach: {@code @latest} is not a valid selector token either way, so what reached
     * the parser had nothing where the operand should be.
     */
    @Test
    @DisplayName("the latest position is honoured whether or not the SDK quotes it")
    void latestIsRecognisedQuotedAndUnquoted() throws Exception {
        for (String selector : new String[]{
                "amqp.annotation.x-opt-offset > '@latest'",
                "amqp.annotation.x-opt-offset > @latest",
                "amqp.annotation.x-opt-offset >= '@latest'",
                "amqp.annotation.x-opt-offset >=@latest"}) {
            String rewritten = rewrite(selector);
            // Inclusive: the clock has only millisecond resolution, and rewriteSelector's javadoc
            // explains why that boundary is the safer way round.
            assertTrue(rewritten.matches("floci_enqueued_time >= \\d+"),
                    "not rewritten to a clock comparison: " + selector + " -> " + rewritten);
        }
    }

    /**
     * A numeric operand needs no special casing either way: the name mapping does not care about
     * quoting, and unquoting only has to happen when the SDK supplied quotes, because Artemis
     * compares a numeric property against a string constant only under a thread-local flag.
     */
    @Test
    @DisplayName("numeric positions are rewritten whichever way the SDK writes them")
    void numericPositionsAreRewritten() throws Exception {
        assertEquals("floci_offset > -1", rewrite("amqp.annotation.x-opt-offset > '-1'"));
        assertEquals("floci_offset > -1", rewrite("amqp.annotation.x-opt-offset > -1"));
        assertEquals("floci_sequence_number >= 12345",
                rewrite("amqp.annotation.x-opt-sequence-number >= '12345'"));
        assertEquals("floci_sequence_number >= 12345",
                rewrite("amqp.annotation.x-opt-sequence-number >= 12345"));
        assertEquals("floci_enqueued_time > 1756400000000",
                rewrite("amqp.annotation.x-opt-enqueued-time > '1756400000000'"));
        assertEquals("floci_enqueued_time > 1756400000000",
                rewrite("amqp.annotation.x-opt-enqueued-time > 1756400000000"));
    }

    /**
     * The partition plugin keeps an event's stream position as broker properties, which Artemis
     * reads after the application properties and does not send. Every start position must still
     * select by them: an event at or after the position passes, an event before it does not.
     */
    @Test
    @DisplayName("every start position selects events by the stream position the broker keeps")
    void startPositionsSelectByTheBrokerProperties() throws Exception {
        long now = System.currentTimeMillis();
        AMQPMessage before = event(4, now - 60_000);
        AMQPMessage at = event(5, now - 30_000);
        AMQPMessage after = event(6, now + 60_000);
        List<AMQPMessage> events = List.of(before, at, after);

        assertSelects("amqp.annotation.x-opt-offset > '-1'", events, before, at, after);
        assertSelects("amqp.annotation.x-opt-offset > '@latest'", events, after);
        assertSelects("amqp.annotation.x-opt-offset > '5'", events, after);
        assertSelects("amqp.annotation.x-opt-offset >= '5'", events, at, after);
        assertSelects("amqp.annotation.x-opt-sequence-number > '4'", events, at, after);
        assertSelects("amqp.annotation.x-opt-sequence-number >= 6", events, after);
        assertSelects("amqp.annotation.x-opt-enqueued-time > '" + (now - 30_000) + "'", events, after);
        assertSelects("amqp.annotation.x-opt-enqueued-time >= " + (now - 30_000), events, at, after);
    }

    private static void assertSelects(String selector, List<AMQPMessage> events, AMQPMessage... selected)
            throws Exception {
        Filter filter = FilterImpl.createFilter(rewrite(selector));
        List<AMQPMessage> expected = List.of(selected);
        for (AMQPMessage event : events) {
            assertEquals(expected.contains(event), filter.match(event),
                    selector + " on the event at offset " + event.getExtraProperties().getProperty(
                            SimpleString.of("floci_offset")));
        }
    }

    /** An event as the partition plugin leaves it: its stream position in broker properties only. */
    private static AMQPMessage event(long sequence, long enqueuedTime) {
        // Proton's Message: Artemis has a Message type of its own.
        org.apache.qpid.proton.message.Message message = Proton.message();
        message.setApplicationProperties(new ApplicationProperties(Map.of("testId", "abc")));
        byte[] buffer = new byte[1024];
        int size = message.encode(buffer, 0, buffer.length);
        AMQPStandardMessage event = new AMQPStandardMessage(0, Arrays.copyOf(buffer, size), null);
        TypedProperties brokerProperties = event.createExtraProperties();
        brokerProperties.putLongProperty(SimpleString.of("floci_offset"), sequence);
        brokerProperties.putLongProperty(SimpleString.of("floci_sequence_number"), sequence);
        brokerProperties.putLongProperty(SimpleString.of("floci_enqueued_time"), enqueuedTime);
        return event;
    }

    /** Anything that is not an Event Hubs start position is somebody else's selector. */
    @Test
    @DisplayName("an unrelated selector passes through untouched")
    void leavesOtherSelectorsAlone() throws Exception {
        assertEquals("testId = 'abc123'", rewrite("testId = 'abc123'"));
        assertEquals("floci_partition = '0'", rewrite("floci_partition = '0'"));
        assertNull(rewrite(null));
    }
}
