package io.floci.az.core;

import io.floci.az.services.appconfig.AppConfigHandler;
import io.floci.az.services.acr.AcrHandler;
import io.floci.az.services.aks.AksHandler;
import io.floci.az.services.blob.BlobServiceHandler;
import io.floci.az.services.cosmos.CosmosHandler;
import io.floci.az.services.eventgrid.EventGridHandler;
import io.floci.az.services.eventhub.EventHubHandler;
import io.floci.az.services.functions.FunctionsServiceHandler;
import io.floci.az.services.keyvault.KeyVaultHandler;
import io.floci.az.services.network.NetworkHandler;
import io.floci.az.services.queue.QueueServiceHandler;
import io.floci.az.services.redis.RedisHandler;
import io.floci.az.services.servicebus.ServiceBusHandler;
import io.floci.az.services.sql.SqlHandler;
import io.floci.az.services.postgres.PostgresHandler;
import io.floci.az.services.mysql.MySqlHandler;
import io.floci.az.services.mariadb.MariaDbHandler;
import io.floci.az.services.table.TableServiceHandler;
import io.floci.az.services.vm.VmHandler;
import io.floci.az.services.monitor.MonitorHandler;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

@Path("/_admin")
@Produces(MediaType.APPLICATION_JSON)
public class AdminController {

    @Inject AppConfigHandler        appConfigHandler;
    @Inject AcrHandler              acrHandler;
    @Inject AksHandler              aksHandler;
    @Inject BlobServiceHandler      blobHandler;
    @Inject CosmosHandler           cosmosHandler;
    @Inject EventHubHandler         eventHubHandler;
    @Inject FunctionsServiceHandler functionsHandler;
    @Inject KeyVaultHandler         keyVaultHandler;
    @Inject NetworkHandler          networkHandler;
    @Inject QueueServiceHandler     queueHandler;
    @Inject ServiceBusHandler       serviceBusHandler;
    @Inject SqlHandler              sqlHandler;
    @Inject PostgresHandler         postgresHandler;
    @Inject MySqlHandler            mysqlHandler;
    @Inject MariaDbHandler          mariadbHandler;
    @Inject TableServiceHandler     tableHandler;
    @Inject VmHandler               vmHandler;
    @Inject RedisHandler            redisHandler;
    @Inject MonitorHandler          monitorHandler;
    @Inject EventGridHandler        eventGridHandler;

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

    /**
     * Wipes all emulator state across every service.
     * Stops any running sidecar containers (SQL Server, Artemis, Redpanda) before clearing.
     * Intended for test isolation — call at the start of each test suite.
     */
    @POST
    @Path("/reset")
    public Response reset() {
        // Services backed by StorageBackend — just clear the store
        appConfigHandler.clearAll();
        acrHandler.clearAll();
        aksHandler.clearAll();
        blobHandler.clearAll();
        cosmosHandler.clearAll();
        functionsHandler.clearAll();
        keyVaultHandler.clearAll();
        networkHandler.clearAll();
        queueHandler.clearAll();
        serviceBusHandler.clearAll();
        tableHandler.clearAll();
        vmHandler.clearAll();
        redisHandler.clearAll();
        monitorHandler.clearAll();
        eventGridHandler.clearAll();

        // Docker-backed services — stop containers first, then clear state
        sqlHandler.clearAll();
        postgresHandler.clearAll();
        mysqlHandler.clearAll();
        mariadbHandler.clearAll();
        eventHubHandler.clearAll();

        return Response.noContent().build();
    }
}
