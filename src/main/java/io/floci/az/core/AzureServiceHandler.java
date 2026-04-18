package io.floci.az.core;

import jakarta.ws.rs.core.Response;

public interface AzureServiceHandler {
    String getServiceType();                     // "blob", "queue", "table"
    boolean canHandle(AzureRequest request);     // fine-grained match if needed
    Response handle(AzureRequest request);
}
