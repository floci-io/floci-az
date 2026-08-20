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
            @JsonInclude(JsonInclude.Include.NON_NULL) String linuxFxVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> environment,
            Instant createdAt
    ) {}

    @RegisterForReflection
    public record FunctionDefinition(
            String appName,
            String funcName,
            String accountName,
            String runtime,
            @JsonInclude(JsonInclude.Include.NON_NULL) String linuxFxVersion,
            String handler,
            int timeoutSeconds,
            @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> environment,
            @JsonInclude(JsonInclude.Include.NON_NULL) String codeLocalPath,
            Instant createdAt,
            boolean packageRootLayout,
            @JsonInclude(JsonInclude.Include.NON_NULL) String routePrefix
    ) {
        /** Pool key — all functions in one app share one container. */
        public String appKey() {
            return accountName + "/" + appName;
        }

        public String functionKey() {
            return accountName + "/" + appName + "/" + funcName;
        }

        public String effectiveRoutePrefix() {
            return routePrefix == null ? "/api" : routePrefix;
        }
    }

    // ── Request bodies ────────────────────────────────────────────────────────

    @RegisterForReflection
    public record CreateAppRequest(
            String runtime,
            @JsonInclude(JsonInclude.Include.NON_NULL) String linuxFxVersion,
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
            @JsonInclude(JsonInclude.Include.NON_NULL) String linuxFxVersion,
            String status,
            Instant createdAt
    ) {}

    @RegisterForReflection
    public record FunctionResponse(
            String name,
            String appName,
            String runtime,
            @JsonInclude(JsonInclude.Include.NON_NULL) String linuxFxVersion,
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
