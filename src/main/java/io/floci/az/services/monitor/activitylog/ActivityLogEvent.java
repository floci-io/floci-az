package io.floci.az.services.monitor.activitylog;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One Activity Log entry, in the {@code EventData} shape the Activity Logs REST API returns.
 *
 * @param eventDataId          unique id of the entry
 * @param operationId          id shared by the entries of one operation (its BeginRequest and EndRequest)
 * @param correlationId        the request's {@code x-ms-correlation-request-id}, or one made up for it
 * @param eventTimestamp       when the service generated the entry
 * @param submissionTimestamp  when the entry became queryable (the same instant here)
 * @param category             {@code Administrative} or {@code Policy}
 * @param eventName            {@code BeginRequest} or {@code EndRequest}
 * @param operationName        such as {@code Microsoft.Storage/storageAccounts/write}
 * @param level                {@code Informational}, {@code Warning}, {@code Error}
 * @param status               {@code Started}, {@code Succeeded}, {@code Failed}
 * @param subStatus            the HTTP status word of an EndRequest ({@code Created}, {@code NotFound}), else empty
 * @param caller               the caller's user name, else object id, else app id; null when the request bore no token
 * @param claims               the caller's token claims, every value a string
 * @param action               the RBAC action the request needed ({@code authorization.action})
 * @param scope                the resource id the action targeted ({@code authorization.scope})
 * @param httpMethod           the request method
 * @param httpUri              the request path and query
 * @param clientRequestId      the request's {@code x-ms-client-request-id}, or null
 * @param clientIpAddress      the transport peer address, or null
 * @param resourceId           the resource the entry is about
 * @param resourceType         its type ({@code Microsoft.Storage/storageAccounts})
 * @param resourceProviderName its provider namespace
 * @param resourceGroupName    its resource group, or null
 * @param subscriptionId       its subscription, or null at tenant level
 * @param tenantId             the tenant the subscription belongs to, or null
 * @param properties           the entry's key/value details ({@code statusCode}, policy details, ...)
 */
public record ActivityLogEvent(String eventDataId, String operationId, String correlationId, Instant eventTimestamp,
                               Instant submissionTimestamp, String category, String eventName, String operationName,
                               String level, String status, String subStatus, String caller, Map<String, String> claims,
                               String action, String scope, String httpMethod, String httpUri, String clientRequestId,
                               String clientIpAddress, String resourceId, String resourceType, String resourceProviderName,
                               String resourceGroupName, String subscriptionId, String tenantId,
                               Map<String, String> properties) {

    private static final long TICKS_AT_EPOCH = 621_355_968_000_000_000L;

    /** The entry's ARM id: the resource id, the event id and the timestamp in .NET ticks, as Azure forms it. */
    public String id() {
        long ticks = TICKS_AT_EPOCH + eventTimestamp.getEpochSecond() * 10_000_000L + eventTimestamp.getNano() / 100;
        return resourceId + "/events/" + eventDataId + "/ticks/" + ticks;
    }

    /** The entry as the REST API serialises it; {@code select} narrows it to the named properties when non-empty. */
    public Map<String, Object> toJson(java.util.Set<String> select) {
        Map<String, Object> json = new LinkedHashMap<>();
        put(json, select, "authorization", authorization());
        put(json, select, "caller", caller);
        put(json, select, "channels", "Operation");
        put(json, select, "claims", claims);
        put(json, select, "correlationId", correlationId);
        put(json, select, "description", "");
        put(json, select, "eventDataId", eventDataId);
        put(json, select, "eventName", localizable(eventName, "EndRequest".equals(eventName) ? "End request"
            : "BeginRequest".equals(eventName) ? "Begin request" : eventName));
        put(json, select, "category", localizable(category, category));
        put(json, select, "eventTimestamp", eventTimestamp.toString());
        put(json, select, "httpRequest", httpRequest());
        put(json, select, "id", id());
        put(json, select, "level", level);
        put(json, select, "operationId", operationId);
        put(json, select, "operationName", localizable(operationName, operationName));
        put(json, select, "properties", properties);
        put(json, select, "resourceGroupName", resourceGroupName);
        put(json, select, "resourceProviderName", localizable(resourceProviderName, resourceProviderName));
        put(json, select, "resourceId", resourceId);
        put(json, select, "resourceType", localizable(resourceType, resourceType));
        put(json, select, "status", localizable(status, status));
        put(json, select, "subStatus", localizable(subStatus, subStatusText(subStatus)));
        put(json, select, "submissionTimestamp", submissionTimestamp.toString());
        put(json, select, "subscriptionId", subscriptionId);
        put(json, select, "tenantId", tenantId);
        return json;
    }

    private Map<String, Object> authorization() {
        Map<String, Object> authorization = new LinkedHashMap<>();
        authorization.put("action", action);
        authorization.put("scope", scope);
        return authorization;
    }

    private Map<String, Object> httpRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        if (clientRequestId != null) {
            request.put("clientRequestId", clientRequestId);
        }
        if (clientIpAddress != null) {
            request.put("clientIpAddress", clientIpAddress);
        }
        request.put("method", httpMethod);
        if (httpUri != null) {
            request.put("uri", httpUri);
        }
        return request;
    }

    private static void put(Map<String, Object> json, java.util.Set<String> select, String name, Object value) {
        if (select.isEmpty() || select.contains(name.toLowerCase(Locale.ROOT))) {
            json.put(name, value);
        }
    }

    static Map<String, Object> localizable(String value, String localized) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", value == null ? "" : value);
        out.put("localizedValue", localized == null ? "" : localized);
        return out;
    }

    /** The sub-status word of an HTTP status, as the Activity Log spells it. */
    public static String subStatusOf(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "NoContent";
            case 400 -> "BadRequest";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "NotFound";
            case 405 -> "MethodNotAllowed";
            case 409 -> "Conflict";
            case 412 -> "PreconditionFailed";
            case 429 -> "TooManyRequests";
            case 500 -> "InternalServerError";
            case 501 -> "NotImplemented";
            case 503 -> "ServiceUnavailable";
            case 504 -> "GatewayTimeout";
            default -> String.valueOf(status);
        };
    }

    private static String subStatusText(String subStatus) {
        if (subStatus == null || subStatus.isEmpty()) {
            return "";
        }
        return switch (subStatus) {
            case "OK" -> "OK (HTTP Status Code: 200)";
            case "Created" -> "Created (HTTP Status Code: 201)";
            case "Accepted" -> "Accepted (HTTP Status Code: 202)";
            case "NoContent" -> "No Content (HTTP Status Code: 204)";
            case "BadRequest" -> "Bad Request (HTTP Status Code: 400)";
            case "Forbidden" -> "Forbidden (HTTP Status Code: 403)";
            case "NotFound" -> "Not Found (HTTP Status Code: 404)";
            case "Conflict" -> "Conflict (HTTP Status Code: 409)";
            case "InternalServerError" -> "Internal Server Error (HTTP Status Code: 500)";
            case "ServiceUnavailable" -> "Service Unavailable (HTTP Status Code: 503)";
            case "GatewayTimeout" -> "Gateway Timeout (HTTP Status Code: 504)";
            default -> subStatus;
        };
    }
}
