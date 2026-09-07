package org.apache.activemq.artemis.protocol.amqp.proton;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

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
        Path patchJar = Path.of("target", "classes", "artemis",
                "artemis-amqp-protocol-2.44.0-floci-az-artemis-amqp-patch.jar");
        loader = new URLClassLoader(new URL[]{patchJar.toUri().toURL()},
                EventHubFilterSupportTest.class.getClassLoader());
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

    /** Anything that is not an Event Hubs start position is somebody else's selector. */
    @Test
    @DisplayName("an unrelated selector passes through untouched")
    void leavesOtherSelectorsAlone() throws Exception {
        assertEquals("testId = 'abc123'", rewrite("testId = 'abc123'"));
        assertEquals("floci_partition = '0'", rewrite("floci_partition = '0'"));
        assertNull(rewrite(null));
    }
}
