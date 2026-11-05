package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Pattern;

import static io.floci.az.services.deployments.TemplateValues.NODES;

/**
 * {@code parseCidr}, {@code cidrSubnet} and {@code cidrHost} ({@code template-functions-cidr.md}), on addresses held as
 * numbers. Addresses are parsed here and never through {@code InetAddress}, which would resolve a bad literal over DNS.
 *
 * <p>IPv4's first usable address is the one after the network address and its last the one before the broadcast; IPv6
 * has no reserved addresses, so its range is the whole network. The docs do not cover an IPv4 {@code /31} or
 * {@code /32}: floci-az treats every address in those as usable.
 */
final class CidrFunctions {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:]+");
    private static final BigInteger TWO = BigInteger.TWO;

    private CidrFunctions() {
    }

    /** One parsed range: its address family, network address and prefix length. */
    private record Network(boolean v4, BigInteger address, int prefix) {

        int bits() {
            return v4 ? 32 : 128;
        }

        BigInteger hostCount() {
            return TWO.pow(bits() - prefix);
        }

        BigInteger last() {
            return address.add(hostCount()).subtract(BigInteger.ONE);
        }

        BigInteger firstUsable() {
            return v4 && prefix < 31 ? address.add(BigInteger.ONE) : address;
        }

        BigInteger lastUsable() {
            return v4 && prefix < 31 ? last().subtract(BigInteger.ONE) : last();
        }

        BigInteger netmask() {
            return TWO.pow(bits()).subtract(hostCount());
        }

        String format(BigInteger value) {
            return v4 ? formatV4(value) : formatV6(value);
        }
    }

    static JsonNode parseCidr(String network) {
        Network parsed = parse("parseCidr", network);
        ObjectNode info = NODES.objectNode();
        info.put("network", parsed.format(parsed.address()));
        info.put("netmask", parsed.format(parsed.netmask()));
        if (parsed.v4()) {
            info.put("broadcast", parsed.format(parsed.last()));
        }
        info.put("firstUsable", parsed.format(parsed.firstUsable()));
        info.put("lastUsable", parsed.format(parsed.lastUsable()));
        info.put("cidr", parsed.prefix());
        return info;
    }

    static JsonNode cidrSubnet(String network, long newCidr, long subnetIndex) {
        Network parsed = parse("cidrSubnet", network);
        if (newCidr < parsed.prefix() || newCidr > parsed.bits()) {
            throw invalid("cidrSubnet", "the new CIDR " + newCidr + " must be between " + parsed.prefix() + " and "
                + parsed.bits() + ".");
        }
        BigInteger index = BigInteger.valueOf(subnetIndex);
        BigInteger subnets = TWO.pow((int) newCidr - parsed.prefix());
        if (index.signum() < 0 || index.compareTo(subnets) >= 0) {
            throw invalid("cidrSubnet", "the subnet index " + subnetIndex + " is out of range for '" + network + "'.");
        }
        BigInteger size = TWO.pow(parsed.bits() - (int) newCidr);
        return NODES.textNode(parsed.format(parsed.address().add(size.multiply(index))) + "/" + newCidr);
    }

    static JsonNode cidrHost(String network, long hostIndex) {
        Network parsed = parse("cidrHost", network);
        BigInteger host = parsed.firstUsable().add(BigInteger.valueOf(hostIndex));
        if (hostIndex < 0 || host.compareTo(parsed.lastUsable()) > 0) {
            throw invalid("cidrHost", "the host index " + hostIndex + " is out of range for '" + network + "'.");
        }
        return NODES.textNode(parsed.format(host));
    }

    private static Network parse(String function, String cidr) {
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            throw invalid(function, "'" + cidr + "' is not in CIDR notation.");
        }
        String address = cidr.substring(0, slash);
        boolean v4 = IPV4.matcher(address).matches();
        BigInteger value = v4 ? parseV4(function, address) : parseV6(function, address);
        int bits = v4 ? 32 : 128;
        int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw invalid(function, "'" + cidr + "' has an invalid prefix length.");
        }
        if (prefix < 0 || prefix > bits) {
            throw invalid(function, "'" + cidr + "' has an invalid prefix length.");
        }
        BigInteger hostBits = TWO.pow(bits - prefix);
        return new Network(v4, value.divide(hostBits).multiply(hostBits), prefix);
    }

    private static BigInteger parseV4(String function, String address) {
        BigInteger value = BigInteger.ZERO;
        for (String part : address.split("\\.")) {
            int octet = Integer.parseInt(part);
            if (octet > 255) {
                throw invalid(function, "'" + address + "' is not a valid IP address.");
            }
            value = value.shiftLeft(8).add(BigInteger.valueOf(octet));
        }
        return value;
    }

    private static BigInteger parseV6(String function, String address) {
        TemplateException bad = invalid(function, "'" + address + "' is not a valid IP address.");
        if (!IPV6.matcher(address).matches()) {
            throw bad;
        }
        int gap = address.indexOf("::");
        if (gap != address.lastIndexOf("::")) {
            throw bad;
        }
        List<String> head = groups(gap < 0 ? address : address.substring(0, gap));
        List<String> tail = gap < 0 ? List.of() : groups(address.substring(gap + 2));
        int missing = 8 - head.size() - tail.size();
        if (gap < 0 ? missing != 0 : missing < 1) {
            throw bad;
        }
        BigInteger value = BigInteger.ZERO;
        List<String> all = new ArrayList<>(head);
        for (int i = 0; i < (gap < 0 ? 0 : missing); i++) {
            all.add("0");
        }
        all.addAll(tail);
        for (String group : all) {
            if (group.isEmpty() || group.length() > 4) {
                throw bad;
            }
            value = value.shiftLeft(16).add(new BigInteger(group, 16));
        }
        return value;
    }

    private static List<String> groups(String part) {
        return part.isEmpty() ? List.of() : List.of(part.split(":", -1));
    }

    private static String formatV4(BigInteger value) {
        StringJoiner joined = new StringJoiner(".");
        for (int shift = 24; shift >= 0; shift -= 8) {
            joined.add(value.shiftRight(shift).and(BigInteger.valueOf(255)).toString());
        }
        return joined.toString();
    }

    /** The compressed form: lowercase, with the longest run of two or more zero groups, the first if tied, as {@code ::}. */
    private static String formatV6(BigInteger value) {
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = value.shiftRight(16 * (7 - i)).and(BigInteger.valueOf(0xffff)).intValue();
        }
        int bestStart = -1;
        int bestLength = 1;
        int i = 0;
        while (i < 8) {
            int run = 0;
            while (i + run < 8 && groups[i + run] == 0) {
                run++;
            }
            if (run > bestLength) {
                bestStart = i;
                bestLength = run;
            }
            i += Math.max(run, 1);
        }
        StringBuilder text = new StringBuilder();
        for (int group = 0; group < 8; group++) {
            if (group == bestStart) {
                text.append("::");
                group += bestLength - 1;
                continue;
            }
            if (!text.isEmpty() && text.charAt(text.length() - 1) != ':') {
                text.append(':');
            }
            text.append(Integer.toHexString(groups[group]));
        }
        return text.toString();
    }

    private static TemplateException invalid(String function, String detail) {
        return TemplateException.invalid("Unable to evaluate template language function '" + function + "': " + detail);
    }
}
