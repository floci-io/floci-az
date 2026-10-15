package io.floci.az.services.policy.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.policy.evaluation.PolicyEngine.Match;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code 403 RequestDisallowedByPolicy} answer, in the shape ARM returns it: the message names the
 * resource and either the assignment's non-compliance message or the policy identifiers, and
 * {@code additionalInfo} carries one {@code PolicyViolation} per denying definition with the evaluated
 * expressions.
 */
final class PolicyDenial {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PolicyDenial() {
    }

    static Response response(String resourceName, List<Match> denials) {
        List<Map<String, Object>> identifiers = new ArrayList<>();
        List<Map<String, Object>> violations = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (Match denial : denials) {
            Map<String, Object> identifier = new LinkedHashMap<>();
            identifier.put("policyAssignment", named(denial.assignment(), false));
            identifier.put("policyDefinition", named(denial.definition(), true));
            if (denial.setDefinition() != null) {
                identifier.put("policySetDefinition", named(denial.setDefinition(), true));
            }
            identifiers.add(identifier);
            violations.add(violation(denial));
            if (denial.nonComplianceMessage() != null && !denial.nonComplianceMessage().isBlank()) {
                reasons.add(denial.nonComplianceMessage());
            }
        }
        String message;
        if (!reasons.isEmpty()) {
            message = "Resource '" + resourceName + "' was disallowed by policy. Reasons: '"
                + String.join("', '", reasons) + "'. See error details for policy resource IDs.";
        } else {
            message = "Resource '" + resourceName + "' was disallowed by policy. Policy identifiers: '"
                + json(identifiers) + "'.";
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "RequestDisallowedByPolicy");
        error.put("target", resourceName);
        error.put("message", message);
        error.put("additionalInfo", violations);
        return Response.status(403).entity(Map.of("error", error)).build();
    }

    private static Map<String, Object> named(Map<String, Object> resource, boolean withVersion) {
        Map<String, Object> properties = PolicyEngine.properties(resource);
        Map<String, Object> named = new LinkedHashMap<>();
        Object displayName = properties.get("displayName");
        named.put("name", displayName instanceof String s && !s.isBlank() ? s : String.valueOf(resource.get("name")));
        named.put("id", resource.get("id"));
        if (withVersion && properties.get("version") instanceof String version) {
            named.put("version", version);
        }
        return named;
    }

    private static Map<String, Object> violation(Match denial) {
        Map<String, Object> assignmentProperties = PolicyEngine.properties(denial.assignment());
        Map<String, Object> definitionProperties = PolicyEngine.properties(denial.definition());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("evaluatedExpressions", denial.trace());
        if (denial.nonComplianceMessage() != null) {
            details.put("reason", denial.nonComplianceMessage());
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("evaluationDetails", details);
        info.put("policyDefinitionId", denial.definition().get("id"));
        info.put("policyDefinitionName", denial.definition().get("name"));
        info.put("policyDefinitionDisplayName", definitionProperties.get("displayName"));
        info.put("policyDefinitionVersion", definitionProperties.get("version"));
        info.put("policyDefinitionEffect", denial.effect());
        info.put("policyAssignmentId", denial.assignment().get("id"));
        info.put("policyAssignmentName", denial.assignment().get("name"));
        info.put("policyAssignmentDisplayName", assignmentProperties.get("displayName"));
        info.put("policyAssignmentScope", assignmentProperties.get("scope"));
        info.put("policyAssignmentParameters", PolicyEngine.parameterValues(assignmentProperties.get("parameters")));
        if (denial.setDefinition() != null) {
            info.put("policySetDefinitionId", denial.setDefinition().get("id"));
            info.put("policySetDefinitionName", denial.setDefinition().get("name"));
            info.put("policySetDefinitionDisplayName", PolicyEngine.properties(denial.setDefinition()).get("displayName"));
            info.put("policyDefinitionReferenceId", denial.referenceId());
        }
        info.put("policyExemptionIds", List.of());
        info.put("policyEnrollmentIds", List.of());
        Map<String, Object> violation = new LinkedHashMap<>();
        violation.put("type", "PolicyViolation");
        violation.put("info", info);
        return violation;
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
