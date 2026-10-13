package io.floci.az.services.entra;

import io.floci.az.services.entra.EntraModels.AuthorizationCode;
import io.floci.az.services.entra.EntraModels.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code GET /{tenant}/oauth2/v2.0/authorize}: the first leg of the authorization code flow, local
 * dev shaped. There is no consent screen: the request is approved for a seeded user (selected by
 * {@code login_hint}, the dev user otherwise) and redirected straight back with a code that
 * {@link AuthorizationCodeGrant} redeems.
 */
@ApplicationScoped
public class AuthorizeEndpoint implements EntraEndpoint {

    private static final String PATH = "oauth2/v2.0/authorize";
    private static final List<String> RESPONSE_TYPES = List.of("code");
    /** {@code form_post} is not served: the code always comes back on the redirect URL. */
    private static final List<String> RESPONSE_MODES = List.of("query", "fragment");

    private final EntraStore store;
    private final EntraResponses responses;

    @Inject
    public AuthorizeEndpoint(EntraStore store, EntraResponses responses) {
        this.store = store;
        this.responses = responses;
    }

    @Override
    public boolean matches(String path) {
        return path.endsWith(PATH);
    }

    @Override
    public void advertise(EntraRequest request, Map<String, Object> document) {
        document.put("authorization_endpoint", request.tenantBase() + "/" + PATH);
        document.put("response_types_supported", RESPONSE_TYPES);
        document.put("response_modes_supported", RESPONSE_MODES);
    }

    @Override
    public Response handle(EntraRequest request) {
        Map<String, String> params = request.request().queryParams();
        String redirectUri = params.get("redirect_uri");
        String responseType = params.getOrDefault("response_type", "code");
        String responseMode = params.getOrDefault("response_mode", "query");
        String loginHint = params.get("login_hint");
        String clientId = params.get("client_id");

        if (redirectUri == null || redirectUri.isBlank()) {
            return responses.error(AadstsError.MISSING_REDIRECT_URI);
        }
        if (!RESPONSE_TYPES.contains(responseType)) {
            return responses.error(AadstsError.UNSUPPORTED_RESPONSE_TYPE, responseType);
        }
        if (clientId == null || clientId.isBlank()) {
            return responses.error(AadstsError.MISSING_CLIENT_ID);
        }

        User user = (loginHint == null ? Optional.<User>empty() : store.findUserByUpn(loginHint))
                .or(() -> store.getUser(EntraStore.DEV_USER_OBJECT_ID))
                .orElse(null);
        if (user == null) {
            return responses.error(AadstsError.NO_DEV_USER);
        }

        String code = UUID.randomUUID().toString();
        store.putAuthorizationCode(new AuthorizationCode(
                code, request.tenantId(), clientId, redirectUri, params.get("code_challenge"),
                params.getOrDefault("code_challenge_method", "plain"), params.get("scope"),
                user.objectId(), params.get("nonce"), params.get("state"),
                Instant.now().plusSeconds(300)));

        return Response.status(Response.Status.FOUND)
                .location(URI.create(buildRedirect(redirectUri, responseMode, code, params.get("state"))))
                .build();
    }

    /** Appends {@code code} and {@code state} as a query string ({@code response_mode=query}, the default) or fragment. */
    private static String buildRedirect(String redirectUri, String responseMode, String code, String state) {
        StringBuilder location = new StringBuilder(redirectUri);
        boolean fragment = "fragment".equals(responseMode);
        location.append(fragment ? '#' : (redirectUri.contains("?") ? '&' : '?'));
        location.append("code=").append(urlEncode(code));
        if (state != null) {
            location.append("&state=").append(urlEncode(state));
        }
        return location.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
