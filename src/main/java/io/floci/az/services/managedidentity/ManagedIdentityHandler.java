package io.floci.az.services.managedidentity;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.RequestUrls;
import io.floci.az.core.Resettable;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmResources;
import io.floci.az.services.entra.TokenIssuer;
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
 * </ul>
 */
@ApplicationScoped
public class ManagedIdentityHandler implements AzureServiceHandler, Resettable {

    private static final Logger LOG = Logger.getLogger(ManagedIdentityHandler.class);

    private static final String PROVIDER_MARKER = "/providers/Microsoft.ManagedIdentity/";
    private static final String IDENTITY_TYPE = "Microsoft.ManagedIdentity/userAssignedIdentities";
    private static final String FIC_TYPE = IDENTITY_TYPE + "/federatedIdentityCredentials";

    private final EmulatorConfig config;
    private final ManagedIdentityStore store;
    private final TokenIssuer tokenIssuer;

    @Inject
    public ManagedIdentityHandler(EmulatorConfig config, ManagedIdentityStore store,
                                  TokenIssuer tokenIssuer) {
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
        int marker = path.indexOf(PROVIDER_MARKER);
        return marker >= 0 && path.indexOf("/providers/", marker + 1) < 0;
    }

    @Override
    public boolean canHandle(AzureRequest request) {
        return "managedidentity".equals(request.serviceType());
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

        // Children of another provider scoped to an identity (role assignments, locks, ...)
        // are not ManagedIdentity resources — 404 like ArmHandler would.
        int marker = path.indexOf(PROVIDER_MARKER);
        if (marker >= 0 && path.indexOf("/providers/", marker + 1) >= 0) {
            return ArmErrors.error(404, "ResourceNotFound", "Unsupported Managed Identity path: " + path);
        }

        try {
            if (path.contains(PROVIDER_MARKER + "identities/default")) {
                return getSystemAssignedIdentity(req, path, method);
            }
            if (path.contains("/federatedIdentityCredentials")) {
                return handleFederatedCredential(req, path, method);
            }
            if (path.contains(PROVIDER_MARKER + "userAssignedIdentities")) {
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
    public void clearAll() {
        store.clearAll();
    }

    /** ARM-shaped identities in a resource group, for ArmHandler's RG resource listing. */
    public List<Map<String, Object>> listResources(String sub, String rg) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> resource : store.listIdentities()) {
            if (sub.equalsIgnoreCase(String.valueOf(resource.get("_sub")))
                    && rg.equalsIgnoreCase(String.valueOf(resource.get("_rg")))) {
                result.add(ArmResources.stripInternal(resource));
            }
        }
        return result;
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
        String key = ManagedIdentityStore.identityKey(sub, rg, name);

        return switch (method) {
            case "PUT" -> putIdentity(req, sub, rg, name, key);
            case "PATCH" -> patchIdentity(req, key);
            case "GET" -> {
                Map<String, Object> existing = store.getIdentity(key);
                yield existing == null
                        ? ArmErrors.error(404, "ResourceNotFound", "Resource not found: userAssignedIdentities/" + name)
                        : Response.ok(ArmResources.stripInternal(existing)).build();
            }
            case "DELETE" -> {
                Map<String, Object> removed = store.removeIdentity(key);
                yield Response.status(removed == null ? 204 : 200).build();
            }
            default -> ArmErrors.error(405, "MethodNotAllowed", "Method not allowed: " + method);
        };
    }

    private Response listIdentities(String path) {
        String sub = ArmPaths.subscription(path, "unknown");
        String rg = path.toLowerCase().contains("/resourcegroups/") ? ArmPaths.resourceGroup(path, "unknown") : null;
        List<Map<String, Object>> value = new ArrayList<>();
        for (Map<String, Object> resource : store.listIdentities()) {
            if (!sub.equalsIgnoreCase(String.valueOf(resource.get("_sub")))) {
                continue;
            }
            if (rg != null && !rg.equalsIgnoreCase(String.valueOf(resource.get("_rg")))) {
                continue;
            }
            value.add(ArmResources.stripInternal(resource));
        }
        return Response.ok(Map.of("value", value)).build();
    }

    private Response putIdentity(AzureRequest req, String sub, String rg, String name, String key) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Map<String, Object> existing = store.getIdentity(key);

        Map<String, Object> properties = new LinkedHashMap<>();
        if (existing != null && existing.get("properties") instanceof Map<?, ?> existingProps) {
            properties.put("tenantId", existingProps.get("tenantId"));
            properties.put("principalId", existingProps.get("principalId"));
            properties.put("clientId", existingProps.get("clientId"));
        } else {
            properties.put("tenantId", config.services().entra().defaultTenantId());
            properties.put("principalId", UUID.randomUUID().toString());
            properties.put("clientId", UUID.randomUUID().toString());
        }

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", identityId(sub, rg, name));
        resource.put("name", name);
        resource.put("type", IDENTITY_TYPE);
        resource.put("location", ArmJson.string(body, "location",
                existing != null ? ArmJson.string(existing, "location", "eastus") : "eastus"));
        // ARM PUT is full-replace for mutable properties: omitted tags clear existing ones
        // (location is create-only per TrackedResource x-ms-mutability and stays).
        resource.put("tags", body.getOrDefault("tags", Map.of()));
        resource.put("properties", properties);
        resource.put("_sub", sub);
        resource.put("_rg", rg);

        store.putIdentity(key, resource);
        return Response.status(existing == null ? 201 : 200).entity(ArmResources.stripInternal(resource)).build();
    }

    private Response patchIdentity(AzureRequest req, String key) {
        Map<String, Object> existing = store.getIdentity(key);
        if (existing == null) {
            return ArmErrors.error(404, "ResourceNotFound", "Resource not found");
        }
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        // Copy-then-replace: never mutate the live stored map in place — the store's
        // ConcurrentHashMap only guards its structure, not the LinkedHashMap values, so a
        // concurrent PATCH/GET on the same identity could race on that map.
        Map<String, Object> updated = new LinkedHashMap<>(existing);
        if (body.get("tags") instanceof Map<?, ?> tags) {
            updated.put("tags", tags);
        }
        store.putIdentity(key, updated);
        return Response.ok(ArmResources.stripInternal(updated)).build();
    }

    // ── ARM: federatedIdentityCredentials ───────────────────────────────────────

    private Response handleFederatedCredential(AzureRequest req, String path, String method) {
        String sub = ArmPaths.subscription(path, "unknown");
        String rg = ArmPaths.resourceGroup(path, "unknown");
        String identityName = ArmPaths.resourceName(path, "userAssignedIdentities").orElse(null);
        String identityKey = ManagedIdentityStore.identityKey(sub, rg, identityName == null ? "" : identityName);
        if (identityName == null || !store.identityExists(identityKey)) {
            return ArmErrors.error(404, "ResourceNotFound",
                    "Resource not found: userAssignedIdentities/" + identityName);
        }

        String ficName = ArmPaths.resourceName(path, "federatedIdentityCredentials").orElse(null);
        if (ficName == null) {
            if (!"GET".equals(method)) {
                return ArmErrors.error(405, "MethodNotAllowed", "Method not allowed on credential collection");
            }
            return Response.ok(Map.of("value", store.listFederatedCredentials(identityKey))).build();
        }

        String key = ManagedIdentityStore.ficKey(identityKey, ficName);
        return switch (method) {
            case "PUT" -> putFederatedCredential(req, sub, rg, identityName, ficName, key);
            case "GET" -> {
                Map<String, Object> existing = store.getFederatedCredential(key);
                yield existing == null
                        ? ArmErrors.error(404, "ResourceNotFound",
                                "Resource not found: federatedIdentityCredentials/" + ficName)
                        : Response.ok(existing).build();
            }
            case "DELETE" -> {
                Map<String, Object> removed = store.removeFederatedCredential(key);
                yield Response.status(removed == null ? 204 : 200).build();
            }
            default -> ArmErrors.error(405, "MethodNotAllowed", "Method not allowed: " + method);
        };
    }

    private Response putFederatedCredential(AzureRequest req, String sub, String rg,
                                            String identityName, String ficName, String key) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Map<String, Object> bodyProps = ArmJson.cast(body.get("properties"));
        String issuer = ArmJson.string(bodyProps, "issuer", null);
        String subject = ArmJson.string(bodyProps, "subject", null);
        Object audiences = bodyProps.get("audiences");
        if (issuer == null || subject == null || !(audiences instanceof List<?>)) {
            return ArmErrors.error(400, "BadRequest",
                    "federatedIdentityCredential requires properties.issuer, properties.subject and properties.audiences");
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("issuer", issuer);
        properties.put("subject", subject);
        properties.put("audiences", audiences);

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", identityId(sub, rg, identityName) + "/federatedIdentityCredentials/" + ficName);
        resource.put("name", ficName);
        resource.put("type", FIC_TYPE);
        resource.put("properties", properties);

        boolean existed = store.putFederatedCredential(key, resource);
        return Response.status(existed ? 200 : 201).entity(resource).build();
    }

    // ── ARM: system-assigned identities/default ─────────────────────────────────

    private Response getSystemAssignedIdentity(AzureRequest req, String path, String method) {
        if (!"GET".equals(method)) {
            return ArmErrors.error(405, "MethodNotAllowed", "Method not allowed: " + method);
        }
        String scope = path.substring(0, path.indexOf(PROVIDER_MARKER));
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
        int selectors = (clientId != null ? 1 : 0) + (objectId != null ? 1 : 0) + (msiResId != null ? 1 : 0);
        if (selectors > 1) {
            return imdsError(400, "invalid_request",
                    "client_id, object_id and msi_res_id are mutually exclusive");
        }

        String principalId;
        String appId;
        if (clientId != null || objectId != null || msiResId != null) {
            Optional<Map<String, Object>> selected;
            if (clientId != null) {
                selected = store.findByClientId(clientId);
            } else if (objectId != null) {
                selected = store.findByPrincipalId(objectId);
            } else {
                selected = store.findByResourceId(msiResId);
            }
            Map<String, Object> identity = selected.orElse(null);
            if (identity == null) {
                return imdsError(400, "invalid_request", "Identity not found");
            }
            principalId = identityProperty(identity, "principalId");
            appId = identityProperty(identity, "clientId");
        } else {
            // System-assigned: same deterministic GUIDs as identities/default for the
            // configured scope. The emulator is not attached to a real resource, so the
            // "own" identity scope is a config knob rather than the caller's VM.
            String scope = config.services().managedIdentity().systemAssignedScope();
            principalId = systemPrincipalId(scope);
            appId = systemClientId(scope);
        }

        String tenantId = config.services().entra().defaultTenantId();
        String issuer = config.services().entra().issuer()
                .orElse(resolveBaseUrl(req) + "/" + tenantId + "/");
        long lifetime = config.services().entra().tokenLifetimeSeconds();
        long now = Instant.now().getEpochSecond();

        String token = tokenIssuer.issue(new TokenIssuer.TokenSpec(
                tenantId, issuer, resource, principalId, principalId, appId, null,
                "1.0", "app", lifetime));

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

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private String resolveBaseUrl(AzureRequest request) {
        return RequestUrls.resolveBaseUrl(request, config);
    }

    private static String identityId(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg
                + PROVIDER_MARKER + "userAssignedIdentities/" + name;
    }

    private static String identityProperty(Map<String, Object> identity, String key) {
        return String.valueOf(ArmJson.cast(identity.get("properties")).get(key));
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
