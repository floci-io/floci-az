package io.floci.az.core;

import io.floci.az.core.storage.StorageBackend;
import io.floci.az.services.appconfig.AppConfigHandler;
import io.floci.az.services.blob.BlobServiceHandler;
import io.floci.az.services.functions.FunctionsServiceHandler;
import io.floci.az.services.queue.QueueServiceHandler;
import io.floci.az.services.table.TableServiceHandler;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

@Path("/_admin")
@Produces(MediaType.APPLICATION_JSON)
public class AdminController {

    @Inject AppConfigHandler         appConfigHandler;
    @Inject BlobServiceHandler       blobHandler;
    @Inject QueueServiceHandler      queueHandler;
    @Inject TableServiceHandler      tableHandler;
    @Inject FunctionsServiceHandler  functionsHandler;

    @GET
    @Path("/accounts")
    public List<String> listAccounts() {
        return List.of("devstoreaccount1");
    }

    @DELETE
    @Path("/accounts/{name}")
    public Response deleteAccount(@PathParam("name") String name) {
        return Response.noContent().build();
    }

    @POST
    @Path("/reset")
    public Response reset() {
        appConfigHandler.clearAll();
        blobHandler.clearAll();
        queueHandler.clearAll();
        tableHandler.clearAll();
        functionsHandler.clearAll();
        return Response.noContent().build();
    }
}
