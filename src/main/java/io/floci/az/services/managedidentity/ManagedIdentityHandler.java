package io.floci.az.services.managedidentity;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.RequestUrls;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.cloud.CloudDefaults;
import io.floci.az.services.entra.TokenIssuer;
import io.floci.az.services.managedidentity.ManagedIdentityModels.FederatedIdentityCredential;
import io.floci.az.services.managedidentity.ManagedIdentityModels.UserAssignedIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Microsoft.ManagedIdentity handler. Serves both planes for {@code serviceType="managedidentity"}:
 * <ul>
 *   <li>Control plane — ARM CRUD for {@code userAssignedIdentities} (+ child
 *       {@code federatedIdentityCredentials}) and the system-assigned
 *       {@code {scope}/providers/Microsoft.ManagedIdentity/identities/default} read.
 *       Shapes per msi spec 2024-11-30.</li>
 *   <li>Data plane — IMDS token endpoint {@code GET metadata/identity/oauth2/token}
 *       (imds spec 2023-07-01), reached by azure-identity {@code ManagedIdentityCredential}
 *       when {@code AZURE_POD_IDENTITY_AUTHORITY_HOST} points at the emulator. Tokens are
 *       minted with the Entra signing key, so they verify against the emulator JWKS.</li>
 *   <li>Data plane — App Service Managed Identity token endpoint {@code GET {appServicePath}}
 *       (header {@code X-IDENTITY-HEADER}), reached by azidentity/azure_identity credential
 *       chains in App Service mode via {@code IDENTITY_ENDPOINT}/{@code IDENTITY_HEADER}.</li>
 * </ul>
 */
@ApplicationScoped
public class ManagedIdentityHandler implements AzureServiceHandler, Resettable {

    private static final Logger LOG = Logger.getLogger(ManagedIdentityHandler.class);

    private static final String PROVIDER_MARKER = "/providers/Microsoft.ManagedIdentity/";
    private static final String IDENTITY_TYPE = ManagedIdentityStore.IDENTITY_TYPE;
    private static final String FIC_TYPE = ManagedIdentityStore.CREDENTIAL_TYPE;

    private final EmulatorConfig config;
    private final ManagedIdentityStore store;
    private final TokenIssuer tokenIssuer;

    private final CloudDefaults defaults;

    @Inject
    public ManagedIdentityHandler(EmulatorConfig config, ManagedIdentityStore store,
                                  TokenIssuer tokenIssuer,
                                  CloudDefaults defaults) {
        this.defaults = defaults;
        this.config = config;
        this.store = store;
        this.tokenIssuer = tokenIssuer;
    }

    @Override
    public String getServiceType() {
        return "managedidentity";
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().managedIdentity().enabled();
    }

    /**
     * Managed Identity is the only guarded provider route. An {@code identities/default} scope may
     * itself sit under a Compute/ContainerService resource, and this provider's segment is always the
     * last {@code /providers/} segment. Nested children scoped to an identity (role assignments, locks)
     * carry a LATER {@code /providers/} segment and must fall through to the generic ARM handler rather
     * than be captured here. The guard also makes this route win over broader providers in the same
     * path — the filter orders guarded routes ahead of unguarded ones.
     */
    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .provider("Microsoft.ManagedIdentity", ManagedIdentityHandler::isLeafProviderSegment)
                .build();
    }

    private static boolean isLeafProviderSegment(String path) {
        int marker = ArmPaths.indexOfIgnoreCase(path, PROVIDER_MARKER);
        return marker >= 0 && path.indexOf("/providers/", marker + 1) < 0;
    }

    @Override
    public Response handle(AzureRequest req) {
        String method = req.method().toUpperCase();
        String path = stripQuery(req.resourcePath());

        if (path.startsWith("metadata/identity/oauth2/token")) {
            if (!"GET".equals(method)) {
                // Per imds spec 2023-07-01 the token endpoint is GET-only.
                return imdsError(405, "method_not_allowed", "Method not allowed: " + method);
            }
            return handleImdsToken(req);
        }

        if (path.equalsIgnoreCase(config.services().managedIdentity().appServicePath())) {
            if (!"GET".equals(method)) {
                return imdsError(405, "method_not_allowed", "Method not allowed: " + method);
            }
            return handleAppServiceToken(req);
        }

        // Children of another provider scoped to an identity (role assignments, locks, ...)
        // are not ManagedIdentity resources — 404 like ArmHandler would.
        int marker = ArmPaths.indexOfIgnoreCase(path, PROVIDER_MARKER);
        if (marker >= 0 && path.indexOf("/providers/", marker + 1) >= 0) {
            return ArmErrors.error(404, "ResourceNotFound", "Unsupported Managed Identity path: " + path);
        }

        try {
            if (ArmPaths.indexOfIgnoreCase(path, PROVIDER_MARKER + "identities/default") >= 0) {
                return getSystemAssignedIdentity(req, path, method);
            }
            if (path.contains("/federatedIdentityCredentials")) {
                return handleFederatedCredential(req, path, method);
            }
            if (ArmPaths.indexOfIgnoreCase(path, PROVIDER_MARKER + "userAssignedIdentities") >= 0) {
                return handleIdentity(req, path, method);
            }
        } catch (ArmJson.InvalidBodyException e) {
            return ArmErrors.error(400, "InvalidRequestContent",
                    "The request content was invalid and could not be deserialized.");
        }
        LOG.debugf("ManagedIdentity: unhandled %s /%s", method, path);
        return ArmErrors.error(404, "ResourceNotFound", "Unsupported Managed Identity path: " + path);
    }

    @Override
    public void clear() {
        store.clear();
    }

    /** ARM-shaped identities in a resource group, for ArmHandler's RG resource listing. */
    public List<Map<String, Object>> listResources(String sub, String rg) {
        return store.identities(new ArmScope(sub, rg)).stream().map(ManagedIdentityHandler::identityResponse).toList();
    }

    public List<Map<String, Object>> listResources(String sub) {
        return store.identities(new ArmScope(sub, null)).stream().map(ManagedIdentityHandler::identityResponse)
                .toList();
    }

    // ── ARM: userAssignedIdentities ─────────────────────────────────────────────

    private Response handleIdentity(AzureRequest req, String path, String method) {
        String name = ArmPaths.resourceName(path, "userAssignedIdentities").orElse(null);
        if (name == null) {
            if (!"GET".equals(method)) {
                return ArmErrors.error(405, "MethodNotAllowed", "Method not allowed on identity collection");
            }
            return listIdentities(path);
        }

        String sub = ArmPaths.subscription(path, "unknown");
        String rg = ArmPaths.resourceGroup(path, "unknown");
        ArmResourceId id = identityId(sub, rg, name);

        return switch (method) {
            case "PUT" -> putIdentity(req, id, name);
            case "PATCH" -> patchIdentity(req, id);
            case "GET" -> store.identity(id)
                    .map(identity -> Response.ok(identityResponse(identity)).build())
                    .orElseGet(() -> ArmErrors.error(404, "ResourceNotFound",
                            "Resource not found: userAssignedIdentities/" + name));
            case "DELETE" -> Response.status(store.deleteIdentity(id).isEmpty() ? 204 : 200).build();
            default -> ArmErrors.error(405, "MethodNotAllowed", "Method not allowed: " + method);
        };
    }

    private Response listIdentities(String path) {
        String sub = ArmPaths.subscription(path, "unknown");
        String rg = path.toLowerCase().contains("/resourcegroups/") ? ArmPaths.resourceGroup(path, "unknown") : null;
        List<Map<String, Object>> value = store.identities(new ArmScope(sub, rg)).stream()
                .map(ManagedIdentityHandler::identityResponse).toList();
        return Response.ok(Map.of("value", value)).build();
    }

    private Response putIdentity(AzureRequest req, ArmResourceId id, String name) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        // ARM PUT is full-replace for mutable properties: omitted tags clear existing ones
        // (location is create-only per TrackedResource x-ms-mutability and stays). The generated
        // ids and the id's casing are the creating request's.
        Object tags = body.getOrDefault("tags", Map.of());
        ArmResourceStore.Written<UserAssignedIdentity> written = store.createOrUpdateIdentity(id, existing ->
                existing.map(identity -> replaced(identity, body, tags, defaults.defaultLocation(req.scope()))).orElseGet(() -> new UserAssignedIdentity(
                        id.id(), name, ArmJson.string(body, "location", defaults.defaultLocation(req.scope())), tags,
                        config.services().entra().defaultTenantId(),
                        UUID.randomUUID().toString(), UUID.randomUUID().toString())));
        return Response.status(written.created() ? 201 : 200)
                .entity(identityResponse(written.stored().value())).build();
    }

    private static UserAssignedIdentity replaced(UserAssignedIdentity identity, Map<String, Object> body, Object tags,
                                                String defaultLocation) {
        String location = ArmJson.string(body, "location",
                Objects.requireNonNullElse(identity.location(), defaultLocation));
        return new UserAssignedIdentity(identity.id(), identity.name(), location, tags, identity.tenantId(),
                identity.principalId(), identity.clientId());
    }

    private Response patchIdentity(AzureRequest req, ArmResourceId id) {
        if (store.identity(id).isEmpty()) {
            return ArmErrors.error(404, "ResourceNotFound", "Resource not found");
        }
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Object tags = body.get("tags") instanceof Map<?, ?> patched ? patched : null;
        return store.updateIdentity(id, identity -> tags == null ? identity : identity.withTags(tags))
                .map(identity -> Response.ok(identityResponse(identity)).build())
                .orElseGet(() -> ArmErrors.error(404, "ResourceNotFound", "Resource not found"));
    }

    // ── ARM: federatedIdentityCredentials ───────────────────────────────────────

    private Response handleFederatedCredential(AzureRequest req, String path, String method) {
        String sub = ArmPaths.subscription(path, "unknown");
        String rg = ArmPaths.resourceGroup(path, "unknown");
        String identityName = ArmPaths.resourceName(path, "userAssignedIdentities").orElse(null);
        ArmResourceId identityId = identityName == null ? null : identityId(sub, rg, identityName);
        if (identityId == null || store.identity(identityId).isEmpty()) {
            return identityNotFound(identityName);
        }

        String ficName = ArmPaths.resourceName(path, "federatedIdentityCredentials").orElse(null);
        if (ficName == null) {
            if (!"GET".equals(method)) {
                return ArmErrors.error(405, "MethodNotAllowed", "Method not allowed on credential collection");
            }
            return Response.ok(Map.of("value", store.credentials(identityId).stream()
                    .map(ManagedIdentityHandler::credentialResponse).toList())).build();
        }

        ArmResourceId id = credentialId(identityId, ficName);
        return switch (method) {
            case "PUT" -> putFederatedCredential(req, identityId, id, identityName, ficName);
            case "GET" -> store.credential(id)
                    .map(credential -> Response.ok(credentialResponse(credential)).build())
                    .orElseGet(() -> ArmErrors.error(404, "ResourceNotFound",
                            "Resource not found: federatedIdentityCredentials/" + ficName));
            case "DELETE" -> Response.status(store.deleteCredential(id).isEmpty() ? 204 : 200).build();
            default -> ArmErrors.error(405, "MethodNotAllowed", "Method not allowed: " + method);
        };
    }

    private Response putFederatedCredential(AzureRequest req, ArmResourceId identityId, ArmResourceId id,
                                            String identityName, String ficName) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Map<String, Object> bodyProps = ArmJson.cast(body.get("properties"));
        String issuer = ArmJson.string(bodyProps, "issuer", null);
        String subject = ArmJson.string(bodyProps, "subject", null);
        if (issuer == null || subject == null || !(bodyProps.get("audiences") instanceof List<?> audiences)) {
            return ArmErrors.error(400, "BadRequest",
                    "federatedIdentityCredential requires properties.issuer, properties.subject and properties.audiences");
        }

        List<Object> audienceList = new ArrayList<>(audiences);
        return store.createOrUpdateCredential(identityId, id, existing -> new FederatedIdentityCredential(
                        existing.map(FederatedIdentityCredential::id).orElse(id.id()),
                        existing.map(FederatedIdentityCredential::name).orElse(ficName),
                        issuer, subject, audienceList))
                .map(written -> Response.status(written.created() ? 201 : 200)
                        .entity(credentialResponse(written.stored().value())).build())
                .orElseGet(() -> identityNotFound(identityName));
    }

    private static Response identityNotFound(String identityName) {
        return ArmErrors.error(404, "ResourceNotFound", "Resource not found: userAssignedIdentities/" + identityName);
    }

    // ── ARM: system-assigned identities/default ─────────────────────────────────

    private Response getSystemAssignedIdentity(AzureRequest req, String path, String method) {
        if (!"GET".equals(method)) {
            return ArmErrors.error(405, "MethodNotAllowed", "Method not allowed: " + method);
        }
        String scope = path.substring(0, ArmPaths.indexOfIgnoreCase(path, PROVIDER_MARKER));
        String id = "/" + scope + PROVIDER_MARKER + "identities/default";

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("tenantId", config.services().entra().defaultTenantId());
        properties.put("principalId", systemPrincipalId(scope));
        properties.put("clientId", systemClientId(scope));
        // Synthetic: real Azure returns a Key Vault-backed credential renewal URL here.
        properties.put("clientSecretUrl", resolveBaseUrl(req) + id + "/credentials");

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", id);
        resource.put("name", "default");
        resource.put("type", "Microsoft.ManagedIdentity/identities");
        resource.put("properties", properties);
        return Response.ok(resource).build();
    }

    // ── IMDS token endpoint ─────────────────────────────────────────────────────

    private Response handleImdsToken(AzureRequest req) {
        String metadata = req.headers() == null ? null : req.headers().getHeaderString("Metadata");
        if (!"true".equalsIgnoreCase(metadata)) {
            return imdsError(400, "invalid_request", "Required metadata header not specified");
        }

        String resource = param(req, "resource");
        if (resource == null) {
            return imdsError(400, "invalid_request", "Required query parameter 'resource' is missing");
        }

        String clientId = param(req, "client_id");
        String objectId = param(req, "object_id");
        String msiResId = param(req, "msi_res_id");

        PrincipalResolution resolution = resolvePrincipal(clientId, objectId, msiResId);
        if (resolution instanceof PrincipalResolution.Failure failure) {
            return failure == PrincipalResolution.Failure.MULTIPLE_SELECTORS
                    ? imdsError(400, "invalid_request",
                            "client_id, object_id and msi_res_id are mutually exclusive")
                    : imdsError(400, "invalid_request", "Identity not found");
        }
        PrincipalResolution.Found found = (PrincipalResolution.Found) resolution;
        String principalId = found.principalId();
        String appId = found.appId();

        String tenantId = config.services().entra().defaultTenantId();
        String issuer = config.services().entra().issuer()
                .orElse(resolveBaseUrl(req) + "/" + tenantId + "/");
        long lifetime = config.services().entra().tokenLifetimeSeconds();
        long now = Instant.now().getEpochSecond();

        String token = tokenIssuer.issue(new TokenIssuer.TokenSpec(
                tenantId, issuer, resource, principalId, principalId, appId, null,
                "1.0", "app", lifetime), Map.of("xms_mirid", found.resourceId()));

        // Per imds spec 2023-07-01 every value is a string.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", token);
        body.put("client_id", appId);
        body.put("expires_in", String.valueOf(lifetime));
        body.put("expires_on", String.valueOf(now + lifetime));
        body.put("ext_expires_in", String.valueOf(lifetime));
        body.put("not_before", String.valueOf(now));
        body.put("resource", resource);
        body.put("token_type", "Bearer");
        if (objectId != null) {
            body.put("object_id", objectId);
        }
        if (msiResId != null) {
            body.put("msi_res_id", msiResId);
        }
        return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
    }

    // ── App Service Managed Identity token endpoint ─────────────────────────────

    private Response handleAppServiceToken(AzureRequest req) {
        String header = req.headers() == null ? null : req.headers().getHeaderString("X-IDENTITY-HEADER");
        if (header == null || header.isBlank()
                || !config.services().managedIdentity().appServiceHeaderSecret().equals(header)) {
            return imdsError(401, "invalid_request", "Invalid or missing X-IDENTITY-HEADER");
        }

        String resource = param(req, "resource");
        if (resource == null) {
            return imdsError(400, "invalid_request", "Required query parameter 'resource' is missing");
        }

        String clientId = param(req, "client_id");
        String objectId = param(req, "object_id");
        // The ARM-resource-id selector has two spellings: Go/MSAL use mi_res_id, Rust uses msi_res_id.
        String miResId = param(req, "mi_res_id");
        String msiResId = param(req, "msi_res_id");
        String resId = miResId != null ? miResId : msiResId;

        PrincipalResolution resolution = resolvePrincipal(clientId, objectId, resId);
        if (resolution instanceof PrincipalResolution.Failure failure) {
            return failure == PrincipalResolution.Failure.MULTIPLE_SELECTORS
                    ? imdsError(400, "invalid_request", "Multiple identity selectors specified")
                    : imdsError(400, "invalid_request", "Identity not found");
        }
        PrincipalResolution.Found found = (PrincipalResolution.Found) resolution;

        String tenantId = config.services().entra().defaultTenantId();
        String issuer = config.services().entra().issuer()
                .orElse(resolveBaseUrl(req) + "/" + tenantId + "/");
        long lifetime = config.services().entra().tokenLifetimeSeconds();
        long now = Instant.now().getEpochSecond();

        String token = tokenIssuer.issue(new TokenIssuer.TokenSpec(
                tenantId, issuer, resource, found.principalId(), found.principalId(), found.appId(), null,
                "1.0", "app", lifetime), Map.of("xms_mirid", found.resourceId()));

        // App Service MI contract: every value is a string; expires_on is quoted epoch seconds.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", token);
        body.put("client_id", found.appId());
        body.put("expires_in", String.valueOf(lifetime));
        body.put("expires_on", String.valueOf(now + lifetime));
        body.put("resource", resource);
        body.put("token_type", "Bearer");
        return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private sealed interface PrincipalResolution {
        /** {@code resourceId} is the identity's ARM id, carried in tokens as {@code xms_mirid}. */
        record Found(String principalId, String appId, String resourceId) implements PrincipalResolution {}
        enum Failure implements PrincipalResolution { MULTIPLE_SELECTORS, NOT_FOUND }
    }

    /**
     * Resolves a token's principal/app ids from the selector set. A null/blank selector is "absent";
     * at most one may be present. No selector resolves to the system-assigned identity seeded from
     * {@code systemAssignedScope()}.
     */
    private PrincipalResolution resolvePrincipal(String clientId, String objectId, String resId) {
        int selectors = (clientId != null ? 1 : 0) + (objectId != null ? 1 : 0) + (resId != null ? 1 : 0);
        if (selectors > 1) {
            return PrincipalResolution.Failure.MULTIPLE_SELECTORS;
        }

        if (clientId != null || objectId != null || resId != null) {
            Optional<UserAssignedIdentity> selected;
            if (clientId != null) {
                selected = store.findByClientId(clientId);
            } else if (objectId != null) {
                selected = store.findByPrincipalId(objectId);
            } else {
                selected = store.findByResourceId(resId);
            }
            return selected
                    .<PrincipalResolution>map(identity -> new PrincipalResolution.Found(
                            String.valueOf(identity.principalId()), String.valueOf(identity.clientId()),
                            identity.id()))
                    .orElse(PrincipalResolution.Failure.NOT_FOUND);
        }

        // System-assigned: same deterministic GUIDs as identities/default for the configured scope.
        // The emulator is not attached to a real resource, so the "own" identity scope is a config
        // knob rather than the caller's VM.
        String scope = config.services().managedIdentity().systemAssignedScope();
        return new PrincipalResolution.Found(systemPrincipalId(scope), systemClientId(scope),
                scope.startsWith("/") ? scope : "/" + scope);
    }

    private String resolveBaseUrl(AzureRequest request) {
        return RequestUrls.resolveBaseUrl(request, config);
    }

    private static ArmResourceId identityId(String sub, String rg, String name) {
        return ArmResourceId.parse("/subscriptions/" + sub + "/resourceGroups/" + rg
                + PROVIDER_MARKER + "userAssignedIdentities/" + name).orElseThrow();
    }

    private static ArmResourceId credentialId(ArmResourceId identityId, String name) {
        return ArmResourceId.parse(identityId.id() + "/federatedIdentityCredentials/" + name).orElseThrow();
    }

    private static Map<String, Object> identityResponse(UserAssignedIdentity identity) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("tenantId", identity.tenantId());
        properties.put("principalId", identity.principalId());
        properties.put("clientId", identity.clientId());

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", identity.id());
        resource.put("name", identity.name());
        resource.put("type", IDENTITY_TYPE);
        resource.put("location", identity.location());
        resource.put("tags", identity.tags());
        resource.put("properties", properties);
        return resource;
    }

    private static Map<String, Object> credentialResponse(FederatedIdentityCredential credential) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("issuer", credential.issuer());
        properties.put("subject", credential.subject());
        properties.put("audiences", credential.audiences());

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", credential.id());
        resource.put("name", credential.name());
        resource.put("type", FIC_TYPE);
        resource.put("properties", properties);
        return resource;
    }

    private static String systemPrincipalId(String scope) {
        return TokenIssuer.deterministicGuid("msi-principal:" + scope.toLowerCase());
    }

    private static String systemClientId(String scope) {
        return TokenIssuer.deterministicGuid("msi-client:" + scope.toLowerCase());
    }

    private static String param(AzureRequest req, String name) {
        String value = req.queryParams() == null ? null : req.queryParams().get(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }

    private static Response imdsError(int status, String code, String description) {
        return Response.status(status)
                .header("Www-Authenticate", "Basic realm=\"managed-identity\"")
                .entity(Map.of("error", code, "error_description", description))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
