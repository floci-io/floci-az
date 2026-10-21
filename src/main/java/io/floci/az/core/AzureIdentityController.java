package io.floci.az.core;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudMetadata;
import io.floci.az.core.cloud.CloudProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.List;
import java.util.Map;

@Path("/")
public class AzureIdentityController {

    private final EmulatorConfig config;

    @Inject
    public AzureIdentityController(EmulatorConfig config) {
        this.config = config;
    }

    // OAuth2 token endpoints (common/{tenant}/oauth2[/v2.0]/token), JWKS and OpenID discovery
    // are served by EntraServiceHandler via AzureRoutingFilter's pre-matching dispatch.

    /**
     * ARM environment discovery, in the shape the requested api-version defines:
     *
     * <ul>
     *   <li>{@value CloudMetadata#CLOUD_LIST_VERSION}: a list of clouds, for the az CLI's
     *       {@code ARM_CLOUD_METADATA_URL} ({@link CloudMetadata#cloudList})</li>
     *   <li>{@value CloudMetadata#CLOUD_ENDPOINT_VERSION}: the {@code cloudEndpoint} document azurerm reads for
     *       location validation ({@link CloudMetadata#cloudEndpoints})</li>
     *   <li>any other version, {@code 2022-09-01} among them, or none: the single object go-azure-sdk (azurerm's
     *       {@code metadata_host}) and {@code az cloud register} read</li>
     * </ul>
     *
     * Every endpoint a client calls is the emulator's own base URL. The first two shapes come from the default
     * cloud's catalog payloads.
     */
    @GET
    @Path("metadata/endpoints")
    @Produces(MediaType.APPLICATION_JSON)
    public Response metadataEndpoints(@Context UriInfo uriInfo, @Context HttpHeaders headers,
                                      @QueryParam("api-version") String apiVersion) {
        String baseUrl = baseUrl(uriInfo, headers);
        if (CloudMetadata.CLOUD_LIST_VERSION.equals(apiVersion)) {
            return Response.ok(CloudMetadata.cloudList(defaultCloud(), baseUrl)).build();
        }
        if (CloudMetadata.CLOUD_ENDPOINT_VERSION.equals(apiVersion)) {
            return Response.ok(CloudMetadata.cloudEndpoints(defaultCloud(), baseUrl)).build();
        }
        // The 2022-09-01 schema used by hashicorp/go-azure-sdk. Suffixes are required so go-azure-sdk populates
        // env.Storage/env.KeyVault with a resource identifier; the actual data-plane URLs come from ARM
        // primaryEndpoints/vaultUri.
        return Response.ok(Map.of(
            "name",                     "floci-az",
            // No trailing slash: in Azure Stack mode the go-azure-sdk concatenates this
            // endpoint with leading-slash resource paths, so a trailing slash yields
            // "//subscriptions/..." — a network-path reference that breaks the SDK's LRO
            // poller URL resolution (it drops the resource name and polls the collection
            // forever, e.g. hanging `terraform destroy` of a virtual machine).
            "resourceManager",          baseUrl,
            "microsoftGraphResourceId", baseUrl + "/",
            "portal",                   baseUrl + "/",
            "gallery",                  baseUrl + "/",
            "authentication", Map.of(
                "loginEndpoint",    baseUrl + "/",
                "audiences",        List.of(baseUrl + "/"),
                "identityProvider", "AAD",
                "tenant",           "common"
            ),
            "suffixes", Map.of(
                "storage",     "core.windows.net",
                "keyVaultDns", "vault.azure.net"
            )
        )).build();
    }

    private CloudProfile defaultCloud() {
        return CloudCatalog.forNameOrPublic(config.cloud().defaultCloud());
    }

    private static String baseUrl(UriInfo uriInfo, HttpHeaders headers) {
        // Derive base URL from the incoming request so the returned endpoints are
        // always reachable by the caller (host-network, Docker bridge, or TLS).
        String baseUrl = uriInfo.getBaseUri().toString().replaceAll("/+$", "");
        // Honor X-Forwarded-Proto when a TLS-terminating reverse proxy fronts the
        // emulator: the proxy->emulator hop is plaintext, so UriInfo sees "http",
        // but the caller reached the proxy over "https" and the advertised URLs
        // must reflect the CLIENT's scheme. Otherwise go-azure-sdk / terraform's
        // azurerm custom-environment bootstrap takes loginEndpoint at face value
        // and sends a plaintext token request to the TLS-only listener, which
        // rejects it and the apply never reaches a resource. Only the scheme is
        // overridden; the Host-derived authority from UriInfo is kept as-is.
        // Mirrors the X-Forwarded-Proto handling in the Cosmos/Postgres/Sql handlers.
        String forwardedProto = headers.getHeaderString("X-Forwarded-Proto");
        if (forwardedProto != null && !forwardedProto.isBlank()) {
            // A proxy chain may send a comma-separated list; the first value is
            // the scheme the original client used.
            String scheme = forwardedProto.split(",")[0].trim().toLowerCase();
            if (scheme.equals("http") || scheme.equals("https")) {
                baseUrl = baseUrl.replaceFirst("^https?://", scheme + "://");
            }
        }
        return baseUrl;
    }
}
