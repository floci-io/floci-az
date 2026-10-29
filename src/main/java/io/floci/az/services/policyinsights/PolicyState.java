package io.floci.az.services.policyinsights;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One compliance record: a resource evaluated against one policy definition of one assignment, in the
 * shape {@code Microsoft.PolicyInsights/policyStates} publishes.
 *
 * @param complianceState {@code Compliant}, {@code NonCompliant} or {@code Exempt}
 */
public record PolicyState(Instant timestamp, String resourceId, String resourceType, String resourceLocation,
                          String resourceGroup, String subscriptionId, Map<String, Object> resourceTags,
                          String policyAssignmentId, String policyAssignmentName, String policyAssignmentScope,
                          Map<String, Object> policyAssignmentParameters, String policyDefinitionId,
                          String policyDefinitionName, String policyDefinitionAction, String policyDefinitionCategory,
                          String policySetDefinitionId, String policySetDefinitionName, String policyDefinitionReferenceId,
                          Map<String, Object> effectiveParameters, String complianceState) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The key one record replaces: the same resource under the same definition of the same assignment. */
    public String key() {
        return (resourceId + "|" + policyAssignmentId + "|" + policyDefinitionId + "|"
            + (policyDefinitionReferenceId == null ? "" : policyDefinitionReferenceId)).toLowerCase(Locale.ROOT);
    }

    public boolean isCompliant() {
        return "Compliant".equals(complianceState);
    }

    /** The record as the query results carry it: object-valued fields serialised as JSON strings, as Azure does. */
    public Map<String, Object> toJson(String context) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("@odata.id", null);
        json.put("@odata.context", context + "$metadata#PolicyStates/$entity");
        json.put("timestamp", timestamp.toString());
        json.put("resourceId", resourceId);
        json.put("policyAssignmentId", policyAssignmentId);
        json.put("policyDefinitionId", policyDefinitionId);
        json.put("effectiveParameters", text(effectiveParameters));
        json.put("isCompliant", isCompliant());
        json.put("subscriptionId", subscriptionId);
        json.put("resourceType", resourceType);
        json.put("resourceLocation", resourceLocation);
        json.put("resourceGroup", resourceGroup);
        json.put("resourceTags", text(resourceTags));
        json.put("policyAssignmentName", policyAssignmentName);
        json.put("policyAssignmentOwner", "tbd");
        json.put("policyAssignmentParameters", text(policyAssignmentParameters));
        json.put("policyAssignmentScope", policyAssignmentScope);
        json.put("policyDefinitionName", policyDefinitionName);
        json.put("policyDefinitionAction", policyDefinitionAction);
        json.put("policyDefinitionCategory", policyDefinitionCategory == null ? "tbd" : policyDefinitionCategory);
        json.put("policySetDefinitionId", policySetDefinitionId == null ? "" : policySetDefinitionId);
        json.put("policySetDefinitionName", policySetDefinitionName == null ? "" : policySetDefinitionName);
        json.put("policySetDefinitionOwner", "");
        json.put("policySetDefinitionCategory", "");
        json.put("policySetDefinitionParameters", "");
        json.put("managementGroupIds", "");
        json.put("policyDefinitionReferenceId", policyDefinitionReferenceId == null ? "" : policyDefinitionReferenceId);
        json.put("complianceState", complianceState);
        json.put("policyDefinitionGroupNames", List.of());
        json.put("policyDefinitionVersion", "1.0.0");
        json.put("policySetDefinitionVersion", policySetDefinitionId == null ? "" : "1.0.0");
        json.put("policyAssignmentVersion", "1.0.0");
        return json;
    }

    private static String text(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return "{}";
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
