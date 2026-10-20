package io.floci.az.services.entra;

import io.floci.az.core.cloud.ScopeResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /common/userrealm/{username}?api-version=1.0}: home realm discovery.
 *
 * <p>MSAL4J calls it before every username-password grant against an AAD authority and fails on
 * anything but a 200 JSON body ({@code UserDiscoveryRequest.execute}); MSAL Python calls it too but
 * falls back to plain ROPC on a 404. Every account the emulator knows is a cloud-only (managed)
 * account, so the answer is always {@code account_type: Managed}, which sends MSAL straight to the
 * token endpoint's password grant ({@link PasswordGrant}) with no WS-Trust federation step.
 */
@ApplicationScoped
public class UserRealmEndpoint implements EntraEndpoint {

    private static final String CLOUD_INSTANCE_NAME = "microsoftonline.com";
    private static final String CLOUD_AUDIENCE_URN = "urn:federation:MicrosoftOnline";

    private final EntraResponses responses;

    @Inject
    public UserRealmEndpoint(EntraResponses responses) {
        this.responses = responses;
    }

    @Override
    public boolean matches(String path) {
        return ScopeResolver.isUserRealmPath(path);
    }

    @Override
    public Response handle(EntraRequest request) {
        String username = request.path().substring(request.path().lastIndexOf('/') + 1);
        Map<String, Object> body = new LinkedHashMap<>();
        // A string, not a number: MSAL4J reads it with getString() and parses the float itself.
        body.put("ver", "1.0");
        body.put("account_type", "Managed");
        body.put("domain_name", domainOf(username));
        body.put("cloud_instance_name", CLOUD_INSTANCE_NAME);
        body.put("cloud_audience_urn", CLOUD_AUDIENCE_URN);
        return responses.json(body);
    }

    private static String domainOf(String username) {
        int at = username.lastIndexOf('@');
        return at < 0 ? username : username.substring(at + 1);
    }
}
