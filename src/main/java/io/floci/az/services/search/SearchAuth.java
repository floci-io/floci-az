package io.floci.az.services.search;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.services.search.SearchModels.SearchServiceResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Optional;

/**
 * Who may call the data plane of a search service.
 *
 * <p>By default anyone may, with any credential or none, and a service ARM never declared is served like a
 * declared one, as Key Vault and Email serve an account with no ARM resource. With
 * {@code floci-az.parity-overrides.strict-search-keys} (on from {@code parity: strict}) the service must exist
 * and the caller must send one of its admin keys in {@code api-key}, or a Bearer token where the service takes
 * one. A service takes a Bearer token when its {@code authOptions} is {@code aadOrApiKey}, or when it disables
 * local authentication ({@code disableLocalAuth: true}), which also refuses every key. The default
 * {@code apiKeyOnly} means "only the API key can be used". A Bearer token is not validated: {@code strict-tokens}
 * applies to ARM requests only.
 */
@ApplicationScoped
public class SearchAuth {

    private static final String API_KEY_HEADER = "api-key";
    private static final String BEARER_PREFIX = "bearer ";

    private final EmulatorConfig config;

    @Inject
    public SearchAuth(EmulatorConfig config) {
        this.config = config;
    }

    /** Whether the strict key check runs, so the caller must look the service up. */
    public boolean enforced() {
        return config.strictSearchKeys();
    }

    /**
     * The refusal for a management request (indexes, statistics) on {@code service}, or empty when it may go on.
     * A query key reads documents only, so it is refused here.
     */
    public Optional<Response> refuseManagement(AzureRequest req, Optional<SearchServiceResource> service) {
        if (!config.strictSearchKeys()) {
            return Optional.empty();
        }
        if (service.isEmpty()) {
            return Optional.of(SearchErrors.forbidden());
        }
        String authorization = req.headers().getHeaderString("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return takesTokens(service.get()) ? Optional.empty() : Optional.of(SearchErrors.forbidden());
        }
        if (localAuthDisabled(service.get())) {
            return Optional.of(SearchErrors.forbidden());
        }
        String key = req.headers().getHeaderString(API_KEY_HEADER);
        boolean admin = key != null
                && (key.equals(service.get().primaryKey()) || key.equals(service.get().secondaryKey()));
        return admin ? Optional.empty() : Optional.of(SearchErrors.forbidden());
    }

    /** Whether the service takes Microsoft Entra tokens: {@code aadOrApiKey}, or local authentication disabled. */
    private static boolean takesTokens(SearchServiceResource service) {
        return localAuthDisabled(service)
                || ArmJson.cast(service.properties().get("authOptions")).get("aadOrApiKey") != null;
    }

    /** Whether the service takes Microsoft Entra tokens only, so no API key is accepted. */
    private static boolean localAuthDisabled(SearchServiceResource service) {
        return Boolean.TRUE.equals(service.properties().get("disableLocalAuth"));
    }
}
