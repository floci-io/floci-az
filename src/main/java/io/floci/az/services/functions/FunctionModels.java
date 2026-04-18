package io.floci.az.services.functions;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class FunctionModels {

    // ── Stored entities ──────────────────────────────────────────────────────

    @RegisterForReflection
    public record FunctionApp(
            String appName,
            String accountName,
            String runtime,
            @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> environment,
            Instant createdAt
    ) {}

    @RegisterForReflection
    public record FunctionDefinition(
            String appName,
            String funcName,
            String accountName,
            String runtime,
            String handler,
            int timeoutSeconds,
            @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> environment,
            @JsonInclude(JsonInclude.Include.NON_NULL) String codeLocalPath,
            Instant createdAt
    ) {
        public String functionKey() {
            return accountName + "/" + appName + "/" + funcName;
        }
    }

    // ── Request bodies ────────────────────────────────────────────────────────

    @RegisterForReflection
    public record CreateAppRequest(
            String runtime,
            @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> environment
    ) {}

    @RegisterForReflection
    public record DeployFunctionRequest(
            String handler,
            int timeoutSeconds,
            @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> environment,
            String zipBase64
    ) {}

    // ── Response bodies ───────────────────────────────────────────────────────

    @RegisterForReflection
    public record AppResponse(
            String name,
            String runtime,
            String status,
            Instant createdAt
    ) {}

    @RegisterForReflection
    public record FunctionResponse(
            String name,
            String appName,
            String runtime,
            String handler,
            int timeoutSeconds,
            String invokeUrl,
            String status,
            Instant createdAt
    ) {}

    @RegisterForReflection
    public record AppListResponse(List<AppResponse> value) {}

    @RegisterForReflection
    public record FunctionListResponse(List<FunctionResponse> value) {}
}
