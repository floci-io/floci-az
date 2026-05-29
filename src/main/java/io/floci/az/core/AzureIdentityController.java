package io.floci.az.core;

import io.floci.az.config.EmulatorConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
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

    @POST
    @Path("common/oauth2/v2.0/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response tokenCommon() {
        return tokenResponse();
    }

    // AzureRM Terraform provider calls /{tenantId}/oauth2/v2.0/token
    @POST
    @Path("{tenantId}/oauth2/v2.0/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response tokenByTenant(@PathParam("tenantId") String tenantId) {
        return tokenResponse();
    }

    // ARM environment discovery — called by go-azure-sdk (Azure Stack mode via metadata_host).
    // Format follows the 2022-09-01 metadata schema used by hashicorp/go-azure-sdk.
    // Suffixes are required so go-azure-sdk populates env.Storage/env.KeyVault with a
    // resource identifier; the actual data-plane URLs come from ARM primaryEndpoints/vaultUri.
    @GET
    @Path("metadata/endpoints")
    @Produces(MediaType.APPLICATION_JSON)
    public Response metadataEndpoints(@Context UriInfo uriInfo) {
        // Derive base URL from the incoming request so the returned endpoints are
        // always reachable by the caller (host-network, Docker bridge, or TLS).
        String baseUrl = uriInfo.getBaseUri().toString().replaceAll("/+$", "");
        return Response.ok(Map.of(
            "name",                     "floci-az",
            "resourceManager",          baseUrl + "/",
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

    private Response tokenResponse() {
        return Response.ok(Map.of(
            "token_type",     "Bearer",
            "expires_in",     3599,
            "ext_expires_in", 3599,
            "access_token",   "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.e30.t-46OS_I4B9mBqMzdN49xH16p02Y56K1YF_3-m2-y-U"
        )).build();
    }
}
