package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.services.entra.DirectoryReader.DirectoryUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Microsoft Graph's OIDC UserInfo route, advertised by Entra discovery. */
@ApplicationScoped
public class UserInfoEndpoint implements EntraEndpoint {

    private static final Logger LOG = Logger.getLogger(UserInfoEndpoint.class);
    private static final String PATH = "oidc/userinfo";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GRAPH_APP_ID = "00000003-0000-0000-c000-000000000000";

    private final SigningKeyProvider keys;
    private final DirectoryReader directory;
    private final TenantResolver tenants;
    private final IssuerFactory issuers;
    private final EntraResponses responses;

    @Inject
    public UserInfoEndpoint(SigningKeyProvider keys, DirectoryReader directory, TenantResolver tenants,
                            IssuerFactory issuers, EntraResponses responses) {
        this.keys = keys;
        this.directory = directory;
        this.tenants = tenants;
        this.issuers = issuers;
        this.responses = responses;
    }

    @Override
    public boolean matches(String path) {
        return PATH.equals(path);
    }

    @Override
    public void advertise(EntraRequest request, Map<String, Object> document) {
        document.put("userinfo_endpoint", request.baseUrl() + "/" + PATH);
    }

    @Override
    public Response handle(EntraRequest request) {
        String method = request.request().method();
        if (!"GET".equals(method) && !"POST".equals(method)) {
            return Response.status(405).header("Allow", "GET, POST").build();
        }
        JsonNode claims = verifiedClaims(request);
        if (claims == null) {
            return bearerError(401, "invalid_token");
        }
        Set<String> scopes = Arrays.stream(claims.path("scp").asText("").split("\\s+"))
                .collect(Collectors.toSet());
        if (!scopes.contains("openid")) {
            return bearerError(403, "insufficient_scope");
        }
        TenantRef tenant = tenants.resolve(claims.path("tid").asText(), null);
        DirectoryUser user = directory.user(tenant.directoryTenantId(), claims.path("oid").asText()).orElse(null);
        if (user == null || !user.id().equals(claims.path("sub").asText())) {
            return bearerError(401, "invalid_token");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sub", claims.path("sub").asText());
        if (scopes.contains("profile")) {
            putIfPresent(body, "name", user.displayName());
        }
        if (scopes.contains("email")) {
            putIfPresent(body, "email", user.mail());
        }
        return responses.json(body);
    }

    private JsonNode verifiedClaims(EntraRequest request) {
        HttpHeaders headers = request.request().headers();
        String authorization = headers == null ? null : headers.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String[] parts = authorization.substring(7).trim().split("\\.", -1);
        if (parts.length != 3) {
            return null;
        }
        try {
            JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!"RS256".equals(header.path("alg").asText()) || !keys.kid().equals(header.path("kid").asText())) {
                return null;
            }
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(keys.publicKey());
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                return null;
            }
            JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            long now = Instant.now().getEpochSecond();
            if (!claims.path("exp").isIntegralNumber() || claims.path("exp").asLong() <= now
                    || (claims.has("nbf") && (!claims.path("nbf").isIntegralNumber()
                    || claims.path("nbf").asLong() > now)) || "app".equals(claims.path("idtyp").asText())) {
                return null;
            }
            String tid = claims.path("tid").asText("");
            String client = claims.path("azp").asText(claims.path("appid").asText(""));
            String audience = claims.path("aud").asText("");
            CloudProfile cloud = request.request().scope() == null
                    ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD) : request.request().scope().cloud();
            boolean graphAudience = GRAPH_APP_ID.equals(audience) || cloud.endpoints().microsoftGraph().equals(audience);
            // Existing local v2 delegated tokens name their client as audience. Also accept Graph's audiences.
            if (tid.isBlank() || client.isBlank() || (!client.equals(audience) && !graphAudience)) {
                return null;
            }
            String issuer = issuers.issuer(request.baseUrl(), tid, "2.0".equals(claims.path("ver").asText()));
            return issuer.equals(claims.path("iss").asText()) ? claims : null;
        } catch (Exception e) {
            LOG.debugv(e, "Entra UserInfo rejected an unreadable or invalid token");
            return null;
        }
    }

    private static void putIfPresent(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isBlank()) {
            body.put(key, value);
        }
    }

    private static Response bearerError(int status, String error) {
        return Response.status(status).type(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"" + error + "\"")
                .entity(Map.of("error", error)).build();
    }
}
