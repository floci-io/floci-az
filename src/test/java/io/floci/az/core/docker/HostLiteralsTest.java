package io.floci.az.core.docker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HostLiterals: IPv6 literals in URLs and address fields")
class HostLiteralsTest {

    @Test
    @DisplayName("an IPv6 literal is bracketed for URLs and bare as an address")
    void ipv6() {
        assertTrue(HostLiterals.isBareIpv6("::1"));
        assertEquals("[::1]", HostLiterals.forUrl("::1"));
        assertEquals("[::1]", HostLiterals.forUrl("[::1]"));
        assertEquals("::1", HostLiterals.bare("[::1]"));
        assertEquals("::1", HostLiterals.bare("::1"));
        assertEquals("[fe80::1%25eth0]", HostLiterals.forUrl("fe80::1%25eth0"));
    }

    @Test
    @DisplayName("hostnames and IPv4 addresses are the same either way")
    void hostnamesAndIpv4() {
        for (String host : new String[] {"localhost", "docker", "10.0.0.7", "build-host.internal"}) {
            assertFalse(HostLiterals.isBareIpv6(host), host);
            assertEquals(host, HostLiterals.forUrl(host));
            assertEquals(host, HostLiterals.bare(host));
        }
        assertFalse(HostLiterals.isBareIpv6("[::1]"));
        assertNull(HostLiterals.forUrl(null));
        assertNull(HostLiterals.bare(null));
    }
}
