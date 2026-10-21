package io.floci.az.core.arm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Azure's resource types and api-versions for every ARM namespace floci-az serves, read once from the bundled
 * {@code azure/providers.json} that {@code tools/arm/gen_providers.py} generates from bicep-types-az.
 *
 * <p>This is what Azure knows, not what floci-az serves: a namespace lists every type Azure has, and the
 * {@link ArmTypeRegistry} pairs it with the types the services declare. Static and parsed with Jackson's
 * tree model, like {@code CloudCatalog}, so nothing needs reflection registration for the native image.
 * A malformed bundled file is a build defect and fails the first call.
 */
public final class ProviderCatalog {

    public static final String RESOURCE = "azure/providers.json";

    /** One resource type of a namespace, with its api-versions newest first. */
    public record ResourceTypeEntry(String resourceType, List<String> apiVersions) {
        public ResourceTypeEntry {
            apiVersions = List.copyOf(apiVersions);
        }
    }

    /** One namespace and every resource type Azure declares in it. */
    public record ProviderEntry(String namespace, List<ResourceTypeEntry> resourceTypes) {
        public ProviderEntry {
            resourceTypes = List.copyOf(resourceTypes);
        }

        /** The entry for {@code typePath} (below the namespace), compared case-insensitively. */
        public Optional<ResourceTypeEntry> resourceType(String typePath) {
            for (ResourceTypeEntry entry : resourceTypes) {
                if (entry.resourceType().equalsIgnoreCase(typePath)) {
                    return Optional.of(entry);
                }
            }
            return Optional.empty();
        }
    }

    private ProviderCatalog() {
    }

    /** Every namespace, in file order. */
    public static List<ProviderEntry> all() {
        return List.copyOf(Bundled.PROVIDERS.values());
    }

    /** The namespace named {@code namespace}, compared case-insensitively. */
    public static Optional<ProviderEntry> forNamespace(String namespace) {
        return namespace == null ? Optional.empty()
            : Optional.ofNullable(Bundled.PROVIDERS.get(namespace.toLowerCase(Locale.ROOT)));
    }

    static Map<String, ProviderEntry> parse(InputStream in) throws IOException {
        JsonNode providers = new ObjectMapper().readTree(in).path("providers");
        if (!providers.isArray() || providers.isEmpty()) {
            throw new IllegalStateException(RESOURCE + ": 'providers' must be a non-empty list");
        }
        Map<String, ProviderEntry> byNamespace = new LinkedHashMap<>();
        for (JsonNode provider : providers) {
            String namespace = provider.path("namespace").asText("");
            List<ResourceTypeEntry> types = new ArrayList<>();
            for (JsonNode type : provider.path("resourceTypes")) {
                List<String> versions = new ArrayList<>();
                type.path("apiVersions").forEach(version -> versions.add(version.asText()));
                String resourceType = type.path("resourceType").asText("");
                if (resourceType.isEmpty() || versions.isEmpty()) {
                    throw new IllegalStateException(RESOURCE + ": malformed resource type in " + namespace);
                }
                types.add(new ResourceTypeEntry(resourceType, versions));
            }
            if (namespace.isEmpty() || types.isEmpty()) {
                throw new IllegalStateException(RESOURCE + ": malformed provider entry " + namespace);
            }
            byNamespace.put(namespace.toLowerCase(Locale.ROOT), new ProviderEntry(namespace, types));
        }
        return byNamespace;
    }

    /** Lazy holder: the bundled file is read on first use, once. */
    private static final class Bundled {

        static final Map<String, ProviderEntry> PROVIDERS = load();

        private static Map<String, ProviderEntry> load() {
            try (InputStream in = ProviderCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
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
