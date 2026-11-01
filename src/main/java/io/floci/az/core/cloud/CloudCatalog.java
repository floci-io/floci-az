package io.floci.az.core.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.cloud.CloudProfile.Endpoints;
import io.floci.az.core.cloud.CloudProfile.Location;
import io.floci.az.core.cloud.CloudProfile.Sourced;
import io.floci.az.core.cloud.CloudProfile.UnsettledField;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Azure clouds floci-az serves (public, US Government, China), read once from the bundled
 * {@code azure/clouds.json} that {@code tools/cloud/gen_clouds.py} generates from the Azure SDK, CLI and
 * docs sources.
 *
 * <p>Static and CDI-free on purpose: {@code TlsConfigSource} runs before CDI and will read the suffixes
 * from here. The file is parsed with Jackson's tree model, so the records need no reflection
 * registration for the native image; only the resource has to be included.
 *
 * <p>Fail-fast: a malformed bundled file is a build defect, so the first call throws an
 * {@link IllegalStateException} (wrapped in an {@link ExceptionInInitializerError}) naming the problem.
 */
public final class CloudCatalog {

    public static final String RESOURCE = "azure/clouds.json";
    public static final String PUBLIC_CLOUD = "AzureCloud";

    private CloudCatalog() {
    }

    /** Every cloud, public first. */
    public static List<CloudProfile> all() {
        return Bundled.CATALOG.clouds();
    }

    /** The cloud named {@code name}, compared case-insensitively, or empty for an unknown name. */
    public static Optional<CloudProfile> forName(String name) {
        return Bundled.CATALOG.forName(name);
    }

    /** The cloud named {@code name}, or the public cloud when the name is null or unknown. */
    public static CloudProfile forNameOrPublic(String name) {
        return Bundled.CATALOG.forName(name).orElseGet(() -> Bundled.CATALOG.forName(PUBLIC_CLOUD).orElseThrow());
    }

    /**
     * Matches {@code host} against every known suffix of every cloud, longest suffix first, so
     * {@code x.blob.core.usgovcloudapi.net} resolves to US Government's {@link CloudSuffix#BLOB}. A port and a
     * trailing dot are ignored and the comparison is case-insensitive. Empty when no suffix matches or
     * nothing precedes the suffix.
     */
    public static Optional<DnsSuffixMatch> stripKnownDnsSuffix(String host) {
        return Bundled.CATALOG.stripKnownDnsSuffix(host);
    }

    /**
     * Whether {@code host} belongs to a domain real Azure or Microsoft services answer on in any cloud, such as
     * {@code graph.microsoft.com}, {@code storage.azure.com} or {@code x.vault.usgovcloudapi.net}. The domains are
     * the last two labels of every endpoint, audience and suffix in the catalog. A port and a trailing dot are
     * ignored and the comparison is case-insensitive. False for a null host.
     */
    public static boolean isAzureHost(String host) {
        return Bundled.CATALOG.isAzureHost(host);
    }

    /**
     * The canonical form of a location the way Azure clients normalize it: lowercased, spaces removed,
     * and a display name ({@code "East US"}) mapped to its name ({@code eastus}). A value that is not a
     * location of {@code cloud} comes back normalized but otherwise unchanged; rejecting it is the
     * caller's decision. A null value stays null.
     */
    public static String normalizeLocation(CloudProfile cloud, String location) {
        if (location == null) {
            return null;
        }
        String normalized = squash(location);
        for (Location candidate : cloud.locations()) {
            if (candidate.name().equals(normalized) || squash(candidate.displayName()).equals(normalized)) {
                return candidate.name();
            }
        }
        return normalized;
    }

    private static String squash(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /** Parses a catalog document; package-private so tests can feed it malformed input. */
    static Catalog parse(InputStream in) {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(RESOURCE + " is not valid JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.path("clouds").isArray() || root.path("clouds").isEmpty()) {
            throw new IllegalStateException(RESOURCE + ": 'clouds' must be a non-empty array");
        }
        List<CloudProfile> clouds = new ArrayList<>();
        for (JsonNode node : root.path("clouds")) {
            clouds.add(parseCloud(node));
        }
        return new Catalog(clouds);
    }

    private static CloudProfile parseCloud(JsonNode node) {
        String name = text(node, "name", "cloud");
        String where = RESOURCE + " cloud " + name;
        JsonNode endpointsNode = object(node, "endpoints", where);
        Endpoints endpoints = new Endpoints(
                text(endpointsNode, "resourceManager", where),
                text(endpointsNode, "activeDirectory", where),
                strings(endpointsNode, "authorityHosts", where),
                text(endpointsNode, "microsoftGraph", where),
                text(endpointsNode, "portal", where));
        List<String> audiences = strings(node, "audiences", where);
        if (audiences.size() != 2) {
            throw new IllegalStateException(where + ": expected two audiences, got " + audiences);
        }
        List<Location> locations = new ArrayList<>();
        for (JsonNode location : array(node, "locations", where)) {
            locations.add(new Location(text(location, "name", where), text(location, "displayName", where),
                    sourcedStrings(object(location, "zones", where), where)));
        }
        if (locations.isEmpty()) {
            throw new IllegalStateException(where + ": no locations");
        }
        Map<String, JsonNode> metadata = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> version : object(node, "metadataEndpoints", where).properties()) {
            metadata.put(version.getKey(), version.getValue());
        }
        List<UnsettledField> unsettled = new ArrayList<>();
        for (JsonNode field : array(node, "metadataUnsettled", where)) {
            unsettled.add(new UnsettledField(text(field, "field", where), text(field, "source", where)));
        }
        return new CloudProfile(name, endpoints, audiences, suffixes(object(node, "suffixes", where), where),
                sourcedText(object(node, "defaultLocation", where), where), locations, metadata, unsettled);
    }

    /** Every {@link CloudSuffix} must be present: a suffix a cloud lacks is an explicit unsettled entry. */
    private static Map<CloudSuffix, Sourced<String>> suffixes(JsonNode node, String where) {
        Map<CloudSuffix, Sourced<String>> suffixes = new EnumMap<>(CloudSuffix.class);
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            String key = property.getKey();
            CloudSuffix suffix;
            try {
                suffix = CloudSuffix.valueOf(key);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(where + ": unknown suffix key " + key, e);
            }
            Sourced<String> value = sourcedText(property.getValue(), where + " suffix " + key);
            if (value.value() != null && value.value().startsWith(".")) {
                throw new IllegalStateException(where + ": suffix " + key + " must not start with a dot");
            }
            suffixes.put(suffix, value);
        }
        for (CloudSuffix suffix : CloudSuffix.values()) {
            if (!suffixes.containsKey(suffix)) {
                throw new IllegalStateException(where + ": suffix " + suffix + " is missing; mark it UNSETTLED instead");
            }
        }
        return suffixes;
    }

    private static Sourced<String> sourcedText(JsonNode node, String where) {
        String source = text(node, "source", where);
        JsonNode value = node.get("value");
        if (value == null || value.isNull()) {
            return unsettled(source, where);
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(where + ": value must be a non-blank string");
        }
        return settled(value.asText(), source, where);
    }

    private static Sourced<List<String>> sourcedStrings(JsonNode node, String where) {
        String source = text(node, "source", where);
        JsonNode value = node.get("value");
        if (value == null || value.isNull()) {
            return unsettled(source, where);
        }
        return settled(strings(node, "value", where), source, where);
    }

    private static <T> Sourced<T> unsettled(String source, String where) {
        if (!source.startsWith(Sourced.UNSETTLED_PREFIX)) {
            throw new IllegalStateException(where + ": a null value must carry an UNSETTLED source");
        }
        return new Sourced<>(null, source);
    }

    private static <T> Sourced<T> settled(T value, String source, String where) {
        if (source.startsWith(Sourced.UNSETTLED_PREFIX)) {
            throw new IllegalStateException(where + ": an UNSETTLED cell must not carry a value");
        }
        return new Sourced<>(value, source);
    }

    private static String text(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(where + ": '" + field + "' must be a non-blank string");
        }
        return value.asText();
    }

    private static JsonNode object(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalStateException(where + ": '" + field + "' must be an object");
        }
        return value;
    }

    private static JsonNode array(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalStateException(where + ": '" + field + "' must be an array");
        }
        return value;
    }

    private static List<String> strings(JsonNode node, String field, String where) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : array(node, field, where)) {
            if (!value.isTextual()) {
                throw new IllegalStateException(where + ": '" + field + "' must hold strings");
            }
            values.add(value.asText());
        }
        return List.copyOf(values);
    }

    /** The parsed catalog with its lookup tables. */
    record Catalog(List<CloudProfile> clouds, Map<String, CloudProfile> byName, List<DnsSuffixMatch> suffixes,
                   Set<String> azureDomains) {

        Catalog(List<CloudProfile> clouds) {
            this(List.copyOf(clouds), indexByName(clouds), indexSuffixes(clouds), indexAzureDomains(clouds));
        }

        Optional<CloudProfile> forName(String name) {
            return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
        }

        Optional<DnsSuffixMatch> stripKnownDnsSuffix(String host) {
            if (host == null) {
                return Optional.empty();
            }
            String bare = bareHost(host);
            for (DnsSuffixMatch candidate : suffixes) {
                String dotted = "." + candidate.suffix();
                if (bare.endsWith(dotted) && bare.length() > dotted.length()) {
                    String name = bare.substring(0, bare.length() - dotted.length());
                    return Optional.of(new DnsSuffixMatch(candidate.cloud(), candidate.service(), candidate.suffix(), name));
                }
            }
            return Optional.empty();
        }

        boolean isAzureHost(String host) {
            if (host == null) {
                return false;
            }
            String bare = bareHost(host);
            return azureDomains.stream().anyMatch(domain -> bare.equals(domain) || bare.endsWith("." + domain));
        }

        private static String bareHost(String host) {
            String bare = host.trim().toLowerCase(Locale.ROOT);
            int colon = bare.lastIndexOf(':');
            if (colon >= 0 && bare.indexOf(':') == colon) {
                bare = bare.substring(0, colon);
            }
            return bare.endsWith(".") ? bare.substring(0, bare.length() - 1) : bare;
        }

        private static Map<String, CloudProfile> indexByName(List<CloudProfile> clouds) {
            Map<String, CloudProfile> byName = new HashMap<>();
            for (CloudProfile cloud : clouds) {
                if (byName.put(cloud.name().toLowerCase(Locale.ROOT), cloud) != null) {
                    throw new IllegalStateException(RESOURCE + ": cloud " + cloud.name() + " is listed twice");
                }
            }
            if (!byName.containsKey(PUBLIC_CLOUD.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException(RESOURCE + ": the public cloud " + PUBLIC_CLOUD + " is missing");
            }
            return Map.copyOf(byName);
        }

        private static Set<String> indexAzureDomains(List<CloudProfile> clouds) {
            Set<String> domains = new HashSet<>();
            for (CloudProfile cloud : clouds) {
                Endpoints endpoints = cloud.endpoints();
                for (String url : List.of(endpoints.resourceManager(), endpoints.activeDirectory(),
                        endpoints.microsoftGraph(), endpoints.portal())) {
                    domains.add(domainOf(URI.create(url).getHost()));
                }
                for (String audience : cloud.audiences()) {
                    domains.add(domainOf(URI.create(audience).getHost()));
                }
                for (String authorityHost : endpoints.authorityHosts()) {
                    domains.add(domainOf(authorityHost));
                }
                for (Sourced<String> suffix : cloud.suffixes().values()) {
                    if (suffix.value() != null) {
                        domains.add(domainOf(suffix.value()));
                    }
                }
            }
            return Set.copyOf(domains);
        }

        /** The last two labels: every domain in the catalog is registered directly under its top-level domain. */
        private static String domainOf(String host) {
            String lower = host.toLowerCase(Locale.ROOT);
            int last = lower.lastIndexOf('.');
            return lower.substring(lower.lastIndexOf('.', last - 1) + 1);
        }

        /** Every settled suffix of every cloud, longest first, so the most specific suffix wins a match. */
        private static List<DnsSuffixMatch> indexSuffixes(List<CloudProfile> clouds) {
            Map<String, DnsSuffixMatch> bySuffix = new HashMap<>();
            for (CloudProfile cloud : clouds) {
                for (Map.Entry<CloudSuffix, Sourced<String>> entry : cloud.suffixes().entrySet()) {
                    String suffix = entry.getValue().value();
                    if (suffix == null) {
                        continue;
                    }
                    DnsSuffixMatch previous = bySuffix.put(suffix, new DnsSuffixMatch(cloud, entry.getKey(), suffix, ""));
                    if (previous != null) {
                        throw new IllegalStateException(RESOURCE + ": suffix " + suffix + " is claimed by "
                                + previous.cloud().name() + " " + previous.service() + " and "
                                + cloud.name() + " " + entry.getKey());
                    }
                }
            }
            List<DnsSuffixMatch> ordered = new ArrayList<>(bySuffix.values());
            ordered.sort(Comparator.comparingInt((DnsSuffixMatch match) -> match.suffix().length()).reversed()
                    .thenComparing(DnsSuffixMatch::suffix));
            return List.copyOf(ordered);
        }
    }

    /** Lazy holder: the bundled file is read on first use, once. */
    private static final class Bundled {

        static final Catalog CATALOG = load();

        private static Catalog load() {
            try (InputStream in = CloudCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException(RESOURCE + " is not on the classpath");
                }
                return parse(in);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + RESOURCE + ": " + e.getMessage(), e);
            }
        }
    }
}
