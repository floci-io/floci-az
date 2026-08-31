package io.floci.az.core;

import io.floci.az.config.EmulatorConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
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

    // ARM environment discovery — called by go-azure-sdk (Azure Stack mode via metadata_host).
    // Format follows the 2022-09-01 metadata schema used by hashicorp/go-azure-sdk.
    // Suffixes are required so go-azure-sdk populates env.Storage/env.KeyVault with a
    // resource identifier; the actual data-plane URLs come from ARM primaryEndpoints/vaultUri.
    @GET
    @Path("metadata/endpoints")
    @Produces(MediaType.APPLICATION_JSON)
    public Response metadataEndpoints(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
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
}
