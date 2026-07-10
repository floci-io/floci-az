package io.floci.az.core;

import java.util.List;
import java.util.function.Predicate;

/**
 * The routes a service handler claims. Handlers describe themselves; {@link AzureRoutingFilter}
 * assembles its dispatch tables from every registered handler at startup, so adding a service no
 * longer means editing hand-maintained tables in the filter.
 *
 * @param hostSuffixes    host suffixes routed to this handler, e.g. {@code .blob.core.windows.net}.
 *                        The resolved service type is always the handler's own {@code getServiceType()} —
 *                        {@code BlobServiceHandler} claims both {@code .blob.} and {@code .dfs.} and both
 *                        resolve to {@code blob}.
 * @param accountSuffixes account-name suffixes, each with an explicit service type. Explicit because
 *                        {@code CosmosEngineHandler} maps six suffixes to six different service types
 *                        ({@code -cosmos-mongo → cosmos-mongo}), which its own type cannot express.
 * @param providers       ARM provider namespaces served by this handler.
 */
public record ServiceRoutes(
    List<String> hostSuffixes,
    List<SuffixRoute> accountSuffixes,
    List<ProviderRoute> providers
) {

    public static final ServiceRoutes NONE = new ServiceRoutes(List.of(), List.of(), List.of());

    public ServiceRoutes {
        hostSuffixes = List.copyOf(hostSuffixes);
        accountSuffixes = List.copyOf(accountSuffixes);
        providers = List.copyOf(providers);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A host-name or account-name suffix that identifies a service. */
    public record SuffixRoute(String suffix, String serviceType) {}

    /**
     * An ARM management-plane provider namespace served by a handler.
     *
     * @param namespace the provider namespace, e.g. {@code Microsoft.Cache}
     * @param guard     an extra condition on the full request path beyond the namespace being present.
     *                  Managed Identity uses this to require that its {@code /providers/} segment is the
     *                  last one, so identity-scoped children (role assignments, locks) fall through to
     *                  the generic ARM handler instead of being captured.
     */
    public record ProviderRoute(String namespace, Predicate<String> guard) {
        public static final Predicate<String> ALWAYS = path -> true;

        public ProviderRoute(String namespace) {
            this(namespace, ALWAYS);
        }

        /** The pre-built {@code /providers/<namespace>/} path fragment, matched against request paths. */
        public String marker() {
            return "/providers/" + namespace + "/";
        }
    }

    public static final class Builder {
        private final List<String> hostSuffixes = new java.util.ArrayList<>();
        private final List<SuffixRoute> accountSuffixes = new java.util.ArrayList<>();
        private final List<ProviderRoute> providers = new java.util.ArrayList<>();

        public Builder host(String suffix) {
            hostSuffixes.add(suffix);
            return this;
        }

        /** Account suffix resolving to the handler's own service type. */
        public Builder account(String suffix, String serviceType) {
            accountSuffixes.add(new SuffixRoute(suffix, serviceType));
            return this;
        }

        public Builder provider(String namespace) {
            providers.add(new ProviderRoute(namespace));
            return this;
        }

        public Builder provider(String namespace, Predicate<String> guard) {
            providers.add(new ProviderRoute(namespace, guard));
            return this;
        }

        public ServiceRoutes build() {
            return new ServiceRoutes(hostSuffixes, accountSuffixes, providers);
        }
    }
}
