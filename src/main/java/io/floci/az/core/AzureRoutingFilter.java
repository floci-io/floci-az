package io.floci.az.core;

import io.floci.az.core.auth.AuthPipeline;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class AzureRoutingFilter {

    private static final Logger LOGGER = Logger.getLogger(AzureRoutingFilter.class);

    @Inject
    AuthPipeline authPipeline;

    @Inject
    AzureServiceRegistry serviceRegistry;

    @Context
    UriInfo uriInfo;

    @Context
    HttpHeaders httpHeaders;

    @ServerRequestFilter(preMatching = true)
    public Uni<Response> filter(ContainerRequestContext requestContext) {
        return Uni.createFrom().item(() -> {
            String path = requestContext.getUriInfo().getPath();
            LOGGER.infof("Incoming request: %s %s", requestContext.getMethod(), path);

            if (path.startsWith("/")) {
                path = path.substring(1);
            }

            // Health and admin endpoints bypass
            if (path.equals("health") || path.equals("ready") || path.startsWith("_admin")) {
                return null;
            }
            
            // Identity bypass
            if (path.contains("oauth2/v2.0/token")) {
                return null;
            }

            String[] parts = path.split("/", 2);
            if (parts.length < 1 || parts[0].isEmpty()) {
                return null; 
            }

            String accountName = parts[0];
            String resourcePath = parts.length > 1 ? parts[1] : "";

            String serviceType = "blob";
            if (accountName.endsWith("-queue")) {
                serviceType = "queue";
                accountName = accountName.substring(0, accountName.length() - 6);
            } else if (accountName.endsWith("-table")) {
                serviceType = "table";
                accountName = accountName.substring(0, accountName.length() - 6);
            } else if (accountName.endsWith("-functions")) {
                serviceType = "functions";
                accountName = accountName.substring(0, accountName.length() - 10);
            } else {
                serviceType = resolveServiceType(requestContext, resourcePath);
            }
            LOGGER.infof("Resolved accountName: %s, serviceType: %s, resourcePath: %s", accountName, serviceType, resourcePath);
            Map<String, String> queryParams = new HashMap<>();
            requestContext.getUriInfo().getQueryParameters().forEach((k, v) -> queryParams.put(k, v.get(0)));

            AzureRequest azureRequest = new AzureRequest(
                requestContext.getMethod(),
                accountName,
                serviceType,
                resourcePath,
                httpHeaders,
                requestContext.getEntityStream(),
                queryParams,
                null
            );

            AuthContext authContext = authPipeline.resolve(azureRequest);
            azureRequest = new AzureRequest(
                requestContext.getMethod(),
                accountName,
                serviceType,
                resourcePath,
                httpHeaders,
                requestContext.getEntityStream(),
                queryParams,
                authContext
            );

            if (serviceRegistry.isKnown(serviceType) && !serviceRegistry.isEnabled(serviceType)) {
                LOGGER.warnf("Service disabled: %s", serviceType);
                return new AzureErrorResponse("ServiceDisabled",
                        "The " + serviceType + " service is disabled on this emulator.")
                        .toXmlResponse(Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
            }

            Optional<AzureServiceHandler> handler = serviceRegistry.resolve(serviceType);
            if (handler.isPresent()) {
                LOGGER.infof("Dispatching to handler: %s", handler.get().getClass().getSimpleName());
                return handler.get().handle(azureRequest);
            }

            LOGGER.warnf("No handler found for serviceType: %s", serviceType);
            return new AzureErrorResponse("ServiceNotImplemented", "The specified service is not implemented.")
                    .toXmlResponse(Response.Status.NOT_IMPLEMENTED.getStatusCode());
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private String resolveServiceType(ContainerRequestContext requestContext, String resourcePath) {
        String blobType = requestContext.getHeaderString("x-ms-blob-type");
        if (blobType != null) return "blob";

        if (resourcePath.contains("/messages") || resourcePath.endsWith("/messages")) {
            return "queue";
        }

        Map<String, List<String>> queryParams = requestContext.getUriInfo().getQueryParameters();
        List<String> restype = queryParams.get("restype");
        if (restype != null && restype.contains("queue")) {
            return "queue";
        }

        String queueMessageCount = requestContext.getHeaderString("x-ms-queue-message-count");
        if (queueMessageCount != null) return "queue";
        
        return "blob";
    }
}
