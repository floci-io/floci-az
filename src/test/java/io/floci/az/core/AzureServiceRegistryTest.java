package io.floci.az.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("AzureServiceRegistry — handler resolution and routing-table drift")
class AzureServiceRegistryTest {

    @Inject
    AzureServiceRegistry registry;

    @Inject
    AzureRoutingFilter filter;

    /**
     * Guards against drift between the routing tables and the handler set. {@code isEnabled} ends in
     * {@code default -> true}, so a service type that no handler implements is reported as "enabled"
     * and then fails to resolve — surfacing as a puzzling {@code 501 NotImplemented} instead of a
     * startup or test failure. This asserts every type a route can produce has a handler behind it.
     */
    @Test
    void everyRoutedServiceTypeHasARegisteredHandler() {
        Set<String> routed = filter.routedServiceTypes();
        // Without this the loop below would pass vacuously if the tables ever came back empty.
        assertTrue(routed.size() > 20, "expected the full routing surface, got " + routed);

        List<String> orphans = new ArrayList<>();
        for (String serviceType : routed) {
            if (!registry.isKnown(serviceType)) {
                orphans.add(serviceType);
            }
        }
        assertEquals(List.of(), orphans,
            "routing tables name service types with no registered handler: " + orphans);
    }

    /** The Cosmos engine suffixes are served by the one multi-type handler, via handlesServiceType. */
    @Test
    void cosmosEngineSuffixesResolveToTheMultiTypeHandler() {
        for (String engine : Set.of("cosmos-mongo", "cosmos-table", "cosmos-cassandra",
                "cosmos-gremlin", "cosmos-postgresql", "cosmos-nosql")) {
            assertTrue(registry.isKnown(engine), engine + " should be known");
        }
    }

    /** An unknown service type resolves to nothing (and must not blow up the memoisation cache). */
    @Test
    void unknownServiceTypeIsNotKnownAndDoesNotResolve() {
        assertTrue(registry.resolve("no-such-service").isEmpty());
        assertTrue(!registry.isKnown("no-such-service"));
        // Repeat: the negative answer is memoised as an empty Optional, not recomputed into a NPE.
        assertTrue(registry.resolve("no-such-service").isEmpty());
    }

    /**
     * {@code AzureServiceHandler.enabled} defaults to {@code true}. That default is a convenience for
     * the interface, not a licence to skip it: a handler that forgets to override it can never be turned
     * off by config, silently reinstating the {@code default -> true} wart that A5 removed from the
     * registry switch. Every registered handler must state its own enablement.
     */
    @Test
    void everyHandlerOverridesEnabled() throws Exception {
        List<String> inheriting = new ArrayList<>();
        for (AzureServiceHandler handler : registry.handlers()) {
            // Unwrap the Arc client proxy to reach the real handler class.
            Class<?> actual = handler.getClass().getSuperclass() != null
                    && handler.getClass().getSimpleName().endsWith("_ClientProxy")
                    ? handler.getClass().getSuperclass()
                    : handler.getClass();
            if (actual.getMethod("enabled", String.class).getDeclaringClass() == AzureServiceHandler.class) {
                inheriting.add(actual.getSimpleName());
            }
        }
        assertEquals(List.of(), inheriting,
            "these handlers inherit the permissive default enabled(): " + inheriting);
    }

    /** Memoised lookup must return the same handler instance every time. */
    @Test
    void lookupIsStableAcrossCalls() {
        AzureServiceHandler first = registry.resolve("blob").orElseThrow();
        AzureServiceHandler second = registry.resolve("blob").orElseThrow();
        assertSame(first, second);
    }
}
