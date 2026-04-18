package io.floci.az.core;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/common/oauth2/v2.0/token")
public class AzureIdentityController {

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response token() {
        // Return a static JWT mock
        return Response.ok(Map.of(
            "token_type", "Bearer",
            "expires_in", 3599,
            "ext_expires_in", 3599,
            "access_token", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.e30.t-46OS_I4B9mBqMzdN49xH16p02Y56K1YF_3-m2-y-U"
        )).build();
    }
}
