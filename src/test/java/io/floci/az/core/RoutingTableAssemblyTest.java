package io.floci.az.core;

import io.quarkus.test.junit.QuarkusTest;
import io.floci.az.core.ServiceRoutes.SuffixRoute;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Equivalence net for A5: the tables {@link AzureRoutingFilter} now assembles from each handler's
 * {@link ServiceRoutes} must match, entry for entry, the hand-maintained tables that lived inside the
 * filter after A4. The A4 tables are reproduced here verbatim as the golden — this is the whole safety
 * argument for deleting them from the filter.
 *
 * <p>Ordering is asserted as an <em>invariant</em> rather than a literal sequence, because handler
 * discovery order is arbitrary: what matters is that account suffixes match longest-first
 * ({@code -cosmos-table} must beat {@code -table}) and that the guarded Managed Identity provider is
 * tried before the broader providers.</p>
 */
@QuarkusTest
@DisplayName("AzureRoutingFilter — tables assembled from handlers match A4's static tables")
class RoutingTableAssemblyTest {

    @Inject
    AzureRoutingFilter filter;

    /** A4's HOST_ROUTES, verbatim. */
    private static final Set<Map.Entry<String, String>> GOLDEN_HOST_ROUTES = Set.of(
        Map.entry(".vault.azure.net", "keyvault"),
        Map.entry(".communication.azure.com", "email"),
        Map.entry(".blob.core.windows.net", "blob"),
        Map.entry(".dfs.core.windows.net", "blob"),
        Map.entry(".queue.core.windows.net", "queue"),
        Map.entry(".servicebus.windows.net", "servicebus")
    );

    /** A4's ACCOUNT_SUFFIX_ROUTES, verbatim (pre-sort). */
    private static final Set<Map.Entry<String, String>> GOLDEN_ACCOUNT_ROUTES = Set.of(
        Map.entry("-cosmos-mongo", "cosmos-mongo"),
        Map.entry("-cosmos-table", "cosmos-table"),
        Map.entry("-cosmos-cassandra", "cosmos-cassandra"),
        Map.entry("-cosmos-gremlin", "cosmos-gremlin"),
        Map.entry("-cosmos-postgresql", "cosmos-postgresql"),
        Map.entry("-cosmos-nosql", "cosmos-nosql"),
        Map.entry("-cosmos", "cosmos"),
        Map.entry("-queue", "queue"),
        Map.entry("-table", "table"),
        Map.entry("-functions", "functions"),
        Map.entry("-appconfig", "appconfig"),
        Map.entry("-keyvault", "keyvault"),
        Map.entry("-eventgrid", "eventgrid"),
        Map.entry("-eventhub", "eventhub"),
        Map.entry("-sql", "sql"),
        Map.entry("-postgres", "postgres"),
        Map.entry("-servicebus", "servicebus"),
        Map.entry("-apim", "apim"),
        Map.entry("-email", "email")
    );

    /**
     * A4's PROVIDER_ROUTES minus {@code Microsoft.EventGrid}: A6 moved Event Grid's control plane out
     * of the filter's provider lane into the ArmHandler lane (it implements {@code ArmProviderService}),
     * so it is no longer a filter provider route. Every other entry is unchanged from A4.
     */
    private static final Set<Map.Entry<String, String>> GOLDEN_PROVIDER_ROUTES = Set.of(
        Map.entry("/providers/Microsoft.ManagedIdentity/", "managedidentity"),
        Map.entry("/providers/Microsoft.ContainerService/", "aks"),
        Map.entry("/providers/Microsoft.ContainerRegistry/", "acr"),
        Map.entry("/providers/Microsoft.Sql/", "sql"),
        Map.entry("/providers/Microsoft.DBforPostgreSQL/", "postgres"),
        Map.entry("/providers/Microsoft.Compute/", "vm"),
        Map.entry("/providers/Microsoft.Cache/", "redis"),
        Map.entry("/providers/Microsoft.Communication/", "email")
    );

    private static Set<Map.Entry<String, String>> asEntries(List<SuffixRoute> routes) {
        return routes.stream()
                .map(route -> Map.entry(route.suffix(), route.serviceType()))
                .collect(Collectors.toSet());
    }

    @Test
    void hostRoutesMatchA4() {
        assertEquals(GOLDEN_HOST_ROUTES, asEntries(filter.hostRoutes()));
        assertEquals(6, filter.hostRoutes().size(), "no duplicate host suffixes");
    }

    @Test
    void accountSuffixRoutesMatchA4() {
        assertEquals(GOLDEN_ACCOUNT_ROUTES, asEntries(filter.accountSuffixRoutes()));
        assertEquals(19, filter.accountSuffixRoutes().size(), "no duplicate account suffixes");
    }

    @Test
    void providerRoutesMatchA4() {
        assertEquals(GOLDEN_PROVIDER_ROUTES, Set.copyOf(filter.providerRoutesInMatchOrder()));
    }

    /**
     * The load-bearing ordering invariant. A shorter suffix must never be tried before a longer one, or
     * {@code devstoreaccount1-cosmos-table} would resolve to {@code table} instead of {@code cosmos-table}.
     */
    @Test
    void accountSuffixesAreMatchedLongestFirst() {
        List<SuffixRoute> routes = filter.accountSuffixRoutes();
        for (int i = 1; i < routes.size(); i++) {
            int previous = routes.get(i - 1).suffix().length();
            int current = routes.get(i).suffix().length();
            assertTrue(previous >= current,
                "account suffixes must be longest-first, but " + routes.get(i - 1).suffix()
                    + " (" + previous + ") precedes " + routes.get(i).suffix() + " (" + current + ")");
        }
        int cosmosTable = indexOfSuffix(routes, "-cosmos-table");
        int table = indexOfSuffix(routes, "-table");
        assertTrue(cosmosTable < table, "-cosmos-table must be tried before -table");
    }

    /**
     * Managed Identity's guarded route must be tried before the broader providers: a path may carry both
     * a Compute/ContainerService segment and a trailing ManagedIdentity segment. Handler discovery order
     * is arbitrary, so the filter's guarded-first sort is what guarantees this.
     */
    @Test
    void guardedManagedIdentityProviderIsTriedFirst() {
        List<Map.Entry<String, String>> providers = filter.providerRoutesInMatchOrder();
        assertEquals("managedidentity", providers.get(0).getValue(),
            "the guarded Managed Identity route must sort first, got " + providers);
    }

    /**
     * Two handlers claiming the same suffix would otherwise resolve by CDI discovery order — a
     * nondeterministic wiring bug. Startup must fail loudly instead.
     */
    @Test
    void duplicateSuffixClaimedByTwoHandlersFailsFast() {
        List<SuffixRoute> clashing = List.of(
            new SuffixRoute("-queue", "queue"),
            new SuffixRoute("-queue", "someOtherService")
        );
        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> AzureRoutingFilter.rejectDuplicateSuffixes("account", clashing));
        assertTrue(failure.getMessage().contains("-queue"), failure.getMessage());
        assertTrue(failure.getMessage().contains("someOtherService"), failure.getMessage());
    }

    /** The happy path must not throw — otherwise the guard above would be vacuous. */
    @Test
    void distinctSuffixesPassTheDuplicateCheck() {
        AzureRoutingFilter.rejectDuplicateSuffixes("account",
            List.of(new SuffixRoute("-queue", "queue"), new SuffixRoute("-table", "table")));
    }

    /**
     * The provider counterpart of the suffix guard. Two handlers claiming the same {@code Microsoft.X}
     * namespace must fail loudly at startup rather than resolve silently by sort order — otherwise the
     * ARM provider table reintroduces the exact nondeterminism the suffix guards remove.
     */
    @Test
    void duplicateProviderNamespaceFailsFast() {
        var always = ServiceRoutes.ProviderRoute.ALWAYS;
        List<AzureRoutingFilter.ResolvedProvider> clashing = List.of(
            new AzureRoutingFilter.ResolvedProvider("/providers/Microsoft.Cache/", "redis", always),
            new AzureRoutingFilter.ResolvedProvider("/providers/Microsoft.Cache/", "someOtherService", always)
        );
        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> AzureRoutingFilter.rejectDuplicateProviders(clashing));
        assertTrue(failure.getMessage().contains("Microsoft.Cache"), failure.getMessage());
        assertTrue(failure.getMessage().contains("someOtherService"), failure.getMessage());
    }

    /** Distinct provider namespaces must pass — otherwise the guard above would be vacuous. */
    @Test
    void distinctProvidersPassTheDuplicateCheck() {
        var always = ServiceRoutes.ProviderRoute.ALWAYS;
        AzureRoutingFilter.rejectDuplicateProviders(List.of(
            new AzureRoutingFilter.ResolvedProvider("/providers/Microsoft.Cache/", "redis", always),
            new AzureRoutingFilter.ResolvedProvider("/providers/Microsoft.Sql/", "sql", always)));
    }

    private static int indexOfSuffix(List<SuffixRoute> routes, String suffix) {
        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i).suffix().equals(suffix)) {
                return i;
            }
        }
        throw new AssertionError("suffix not present in assembled table: " + suffix);
    }
}
