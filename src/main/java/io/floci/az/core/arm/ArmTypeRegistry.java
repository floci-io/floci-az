package io.floci.az.core.arm;

import io.floci.az.core.arm.ProviderCatalog.ProviderEntry;
import io.floci.az.core.arm.ProviderCatalog.ResourceTypeEntry;
import io.quarkus.arc.All;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Every ARM resource type floci-az serves, collected once from the {@link ArmSurfaceProvider} beans and paired
 * with Azure's api-versions from the {@link ProviderCatalog}.
 *
 * <p>Startup fails on a wiring or data defect: two surfaces claiming one namespace, a type declared twice, or a
 * declared type Azure does not know (a typo, or a stale {@code providers.json}). The registry is built at startup
 * ({@link Startup}), not on the first ARM request, so such a defect stops the emulator instead of failing requests
 * while it reports ready.
 *
 * <p>{@link #match} answers which declared type an ARM path addresses. Resource groups and the built-in
 * {@code subscriptions/{sub}/resourcegroups} collection match {@code Microsoft.Resources/resourceGroups}; an
 * action on a resource ({@code storageAccounts/a/listKeys}) matches the resource's type.
 */
@Startup
@ApplicationScoped
public class ArmTypeRegistry {

    private static final Logger LOG = Logger.getLogger(ArmTypeRegistry.class);

    static final String RESOURCES_NAMESPACE = "Microsoft.Resources";
    static final String RESOURCE_GROUPS = "resourceGroups";

    private final Map<String, ArmSurface> surfaces;
    private final Map<String, ArmResourceType> types;

    @Inject
    public ArmTypeRegistry(@All List<ArmSurfaceProvider> providers) {
        this(collect(providers));
    }

    ArmTypeRegistry(Map<String, ArmSurface> surfaces) {
        this.surfaces = surfaces;
        this.types = index(surfaces);
        LOG.debugv("ARM type registry: {0} namespaces, {1} resource types", surfaces.size(), types.size());
    }

    /** A registry with no types, for tests and callers built outside CDI. */
    public static ArmTypeRegistry empty() {
        return new ArmTypeRegistry(Map.of());
    }

    /** Every surface, in namespace order. */
    public List<ArmSurface> surfaces() {
        return List.copyOf(surfaces.values());
    }

    /** The surface for {@code namespace}, compared case-insensitively. */
    public Optional<ArmSurface> surface(String namespace) {
        return namespace == null ? Optional.empty()
            : Optional.ofNullable(surfaces.get(namespace.toLowerCase(Locale.ROOT)));
    }

    /** Every declared type. */
    public List<ArmResourceType> types() {
        return List.copyOf(types.values());
    }

    /** The declared type named {@code type} ({@code Microsoft.X/y}), compared case-insensitively. */
    public Optional<ArmResourceType> type(String type) {
        return type == null ? Optional.empty() : Optional.ofNullable(types.get(type.toLowerCase(Locale.ROOT)));
    }

    /** Azure's api-versions for a declared type, newest first. */
    public List<String> apiVersions(ArmResourceType type) {
        return ProviderCatalog.forNamespace(type.namespace())
            .flatMap(provider -> provider.resourceType(type.typePath()))
            .map(ResourceTypeEntry::apiVersions)
            .orElse(List.of());
    }

    /** The declared type {@code id} addresses, or null when it addresses none (or is null). */
    public ArmResourceType match(ArmResourceId id) {
        if (id == null) {
            return null;
        }
        if (id.providerNamespace() == null) {
            return builtIn(id);
        }
        ArmResourceType exact = types.get(key(id.providerNamespace(), id.types()));
        if (exact != null || !id.isCollection() || id.types().size() < 2) {
            return exact;
        }
        // A path ending on a type segment under a named resource is either a child collection or an action
        // on that resource; with no child type of that name, it is the action.
        return types.get(key(id.providerNamespace(), id.types().subList(0, id.types().size() - 1)));
    }

    private ArmResourceType builtIn(ArmResourceId id) {
        boolean group = id.resourceGroup() != null && id.types().isEmpty();
        boolean groups = id.resourceGroup() == null && id.subscriptionId() != null && id.types().size() == 1
            && id.types().getFirst().equalsIgnoreCase(RESOURCE_GROUPS);
        return group || groups ? types.get(key(RESOURCES_NAMESPACE, List.of(RESOURCE_GROUPS))) : null;
    }

    private static String key(String namespace, List<String> typeSegments) {
        return (namespace + "/" + String.join("/", typeSegments)).toLowerCase(Locale.ROOT);
    }

    private static Map<String, ArmSurface> collect(List<ArmSurfaceProvider> providers) {
        List<ArmSurface> all = new ArrayList<>();
        for (ArmSurfaceProvider provider : providers) {
            all.addAll(provider.armSurfaces());
        }
        all.sort((a, b) -> a.namespace().compareToIgnoreCase(b.namespace()));
        Map<String, ArmSurface> byNamespace = new LinkedHashMap<>();
        for (ArmSurface surface : all) {
            ArmSurface previous = byNamespace.putIfAbsent(surface.namespace().toLowerCase(Locale.ROOT), surface);
            if (previous != null) {
                throw new IllegalStateException("Two ARM surfaces claim " + surface.namespace());
            }
        }
        return byNamespace;
    }

    private static Map<String, ArmResourceType> index(Map<String, ArmSurface> surfaces) {
        Map<String, ArmResourceType> byType = new LinkedHashMap<>();
        for (ArmSurface surface : surfaces.values()) {
            ProviderEntry provider = ProviderCatalog.forNamespace(surface.namespace()).orElseThrow(() ->
                new IllegalStateException(surface.namespace() + " is not in " + ProviderCatalog.RESOURCE
                    + "; add it to tools/arm/gen_providers.py and run 'make arm-data-sync'"));
            for (ArmResourceType type : surface.types()) {
                if (provider.resourceType(type.typePath()).isEmpty()) {
                    throw new IllegalStateException(type.type() + " is not an Azure resource type ("
                        + ProviderCatalog.RESOURCE + " does not list it)");
                }
                if (byType.putIfAbsent(type.key(), type) != null) {
                    throw new IllegalStateException(type.type() + " is declared twice");
                }
            }
        }
        return byType;
    }
}
