package io.floci.az.core.arm;

import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.ServiceRoutes.ProviderRoute;
import io.quarkus.arc.All;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Every ARM namespace floci-az routes has exactly one {@link ArmSurface}, in the lane that actually serves it,
 * and the generator's namespace list ({@code tools/arm/gen_providers.py}) is the same set. A new service that
 * routes a namespace without declaring its surface, or declares one in the wrong lane, fails here.
 */
@QuarkusTest
class ArmSurfaceInventoryTest {

    /** Namespaces {@code ArmHandler} serves itself, with no handler or provider service behind them. */
    private static final Set<String> ARM_HANDLER_INLINE = Set.of(
        "Microsoft.KeyVault", "Microsoft.Network", "Microsoft.Resources", "Microsoft.Storage", "Microsoft.Web");

    private static final Path GENERATOR = Path.of("tools/arm/gen_providers.py");
    private static final Pattern NAMESPACES = Pattern.compile("NAMESPACES = \\[(.*?)]", Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("\"(Microsoft\\.[A-Za-z]+)\"");

    @Inject
    ArmTypeRegistry registry;

    @Inject
    @All
    List<AzureServiceHandler> handlers;

    @Inject
    @All
    List<ArmProviderService> providerServices;

    @Test
    void everyRoutedNamespaceHasASurfaceInTheLaneThatServesIt() {
        Map<String, ArmLane> routed = new TreeMap<>();
        for (AzureServiceHandler handler : handlers) {
            for (ProviderRoute route : handler.routes().providers()) {
                routed.put(route.namespace(), ArmLane.FILTER);
            }
        }
        for (ArmProviderService service : providerServices) {
            for (String namespace : service.providerNamespaces()) {
                routed.put(namespace, ArmLane.PROVIDER_SERVICE);
            }
        }
        for (String namespace : ARM_HANDLER_INLINE) {
            routed.put(namespace, ArmLane.ARM_HANDLER);
        }
        Map<String, ArmLane> declared = registry.surfaces().stream()
            .collect(Collectors.toMap(ArmSurface::namespace, ArmSurface::lane, (a, b) -> a, TreeMap::new));
        assertEquals(routed, declared);
    }

    @Test
    void theGeneratorListsExactlyTheDeclaredNamespaces() throws IOException {
        Matcher list = NAMESPACES.matcher(Files.readString(GENERATOR));
        if (!list.find()) {
            throw new AssertionError("no NAMESPACES list in " + GENERATOR);
        }
        Set<String> generated = new TreeSet<>();
        Matcher quoted = QUOTED.matcher(list.group(1));
        while (quoted.find()) {
            generated.add(quoted.group(1));
        }
        Set<String> declared = registry.surfaces().stream().map(ArmSurface::namespace)
            .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(declared, generated);
    }

    @Test
    void everySurfaceDeclaresAtLeastOneType() {
        for (ArmSurface surface : registry.surfaces()) {
            assertFalse(surface.types().isEmpty(), surface.namespace() + " declares no type");
        }
    }
}
