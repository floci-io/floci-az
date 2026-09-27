package io.floci.az.core.docker;

/**
 * Formatting for hosts that may be IPv6 literals. A URL authority needs an IPv6 literal in
 * brackets ({@code [::1]:5432}); a field that holds an address, a socket connect or a certificate
 * SAN needs it bare ({@code ::1}). Hostnames and IPv4 addresses are the same either way.
 */
public final class HostLiterals {

    private HostLiterals() {
    }

    /** True for a bare IPv6 literal such as {@code ::1}: not a hostname, IPv4 address or bracketed form. */
    public static boolean isBareIpv6(String host) {
        return host != null && host.indexOf(':') >= 0 && !host.startsWith("[");
    }

    /** The host as it must appear in a URL authority: an IPv6 literal in brackets, anything else unchanged. */
    public static String forUrl(String host) {
        return isBareIpv6(host) ? "[" + host + "]" : host;
    }

    /** The host as an address: an IPv6 literal without URL brackets, anything else unchanged. */
    public static String bare(String host) {
        return host != null && host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
    }
}
