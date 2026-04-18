package io.floci.az.core;

import jakarta.ws.rs.core.HttpHeaders;
import java.io.InputStream;
import java.util.Map;

public record AzureRequest(
    String method,
    String accountName,
    String serviceType,      // "blob", "queue", "table" — resolved at dispatch
    String resourcePath,     // everything after /{accountName}/
    HttpHeaders headers,
    InputStream bodyStream,
    Map<String, String> queryParams,
    AuthContext authContext
) {}
