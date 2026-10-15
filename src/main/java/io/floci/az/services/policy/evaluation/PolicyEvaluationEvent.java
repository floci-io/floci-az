package io.floci.az.services.policy.evaluation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One policy effect that fired for one request: what Azure writes to the Activity Log as a
 * {@code Microsoft.Authorization/policies/{deny|audit|append}/action} event.
 *
 * @param eventDataId          unique id of the event
 * @param eventTimestamp       when the effect fired
 * @param operationName        the Activity Log operation, such as {@code Microsoft.Authorization/policies/deny/action}
 * @param level                {@code Error} for deny, {@code Warning} for audit, {@code Informational} for append
 * @param status               {@code Failed} for deny, {@code Succeeded} otherwise
 * @param httpMethod           the method of the evaluated request
 * @param apiVersion           the api-version of the evaluated request
 * @param resourceId           the ARM id of the evaluated resource
 * @param resourceType         its resource type
 * @param subscriptionId       the subscription of the resource, or null at tenant level
 * @param resourceGroupName    the resource group of the resource, or null
 * @param caller               the caller's object id, application id or user name, or null when the request bore no token
 * @param claims               the caller's token claims the engine kept
 * @param correlationId        the {@code x-ms-correlation-request-id} of the request, or a generated one
 * @param policyAssignmentId   the assignment whose definition fired
 * @param policyDefinitionId   the definition that fired
 * @param policySetDefinitionId the set definition the assignment targets, or null
 * @param policyDefinitionReferenceId the reference inside the set definition, or null
 * @param effect               the resolved effect
 * @param enforcementMode      the assignment's enforcement mode
 * @param nonComplianceMessage the assignment's message for the definition, or null
 * @param evaluatedExpressions the conditions evaluated, as Azure reports them
 */
public record PolicyEvaluationEvent(String eventDataId, Instant eventTimestamp, String operationName, String level,
                                    String status, String httpMethod, String apiVersion, String resourceId,
                                    String resourceType, String subscriptionId, String resourceGroupName,
                                    String caller, Map<String, Object> claims, String correlationId,
                                    String policyAssignmentId, String policyDefinitionId,
                                    String policySetDefinitionId, String policyDefinitionReferenceId,
                                    String effect, String enforcementMode, String nonComplianceMessage,
                                    List<Map<String, Object>> evaluatedExpressions) {

    /** The event as a JSON-ready map, keys in the order above. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventDataId", eventDataId);
        map.put("eventTimestamp", eventTimestamp.toString());
        map.put("operationName", operationName);
        map.put("level", level);
        map.put("status", status);
        map.put("httpMethod", httpMethod);
        map.put("apiVersion", apiVersion);
        map.put("resourceId", resourceId);
        map.put("resourceType", resourceType);
        map.put("subscriptionId", subscriptionId);
        map.put("resourceGroupName", resourceGroupName);
        map.put("caller", caller);
        map.put("claims", claims);
        map.put("correlationId", correlationId);
        map.put("policyAssignmentId", policyAssignmentId);
        map.put("policyDefinitionId", policyDefinitionId);
        map.put("policySetDefinitionId", policySetDefinitionId);
        map.put("policyDefinitionReferenceId", policyDefinitionReferenceId);
        map.put("effect", effect);
        map.put("enforcementMode", enforcementMode);
        map.put("nonComplianceMessage", nonComplianceMessage);
        map.put("evaluatedExpressions", evaluatedExpressions);
        return map;
    }
}
