package io.floci.az.core;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

@Path("/v1.0")
public class MicrosoftGraphController {

    // azurerm provider calls GET /v1.0/servicePrincipals?$filter=appId eq '{clientId}'
    // to discover the service principal object ID during provider initialization.
    @GET
    @Path("servicePrincipals")
    @Produces(MediaType.APPLICATION_JSON)
    public Response servicePrincipals(@QueryParam("$filter") String filter) {
        String appId = extractAppId(filter);
        return Response.ok(Map.of(
            "value", List.of(Map.of(
                "id",    "00000000-0000-0000-0000-000000000010",
                "appId", appId
            ))
        )).build();
    }

    private String extractAppId(String filter) {
        if (filter != null) {
            String[] parts = filter.split("'");
            if (parts.length >= 2) {
                return parts[1];
            }
        }
        return "00000000-0000-0000-0000-000000000003";
    }
}
