package io.floci.az.services.policy.evaluation;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * Inspection of the policy effects that fired: {@code GET /_admin/policy/evaluations} lists them, newest
 * last, optionally narrowed by {@code resourceId}, {@code assignmentId} or {@code effect};
 * {@code DELETE} empties the log.
 */
@Path("/_admin/policy")
@Produces(MediaType.APPLICATION_JSON)
public class PolicyEvaluationAdmin {

    private final PolicyEvaluationLog log;

    @Inject
    public PolicyEvaluationAdmin(PolicyEvaluationLog log) {
        this.log = log;
    }

    @GET
    @Path("/evaluations")
    public List<Map<String, Object>> evaluations(@QueryParam("resourceId") String resourceId,
                                                 @QueryParam("assignmentId") String assignmentId,
                                                 @QueryParam("effect") String effect) {
        return log.events(event ->
                (resourceId == null || resourceId.equalsIgnoreCase(event.resourceId()))
                && (assignmentId == null || assignmentId.equalsIgnoreCase(event.policyAssignmentId()))
                && (effect == null || effect.equalsIgnoreCase(event.effect())))
            .stream().map(PolicyEvaluationEvent::toMap).toList();
    }

    @DELETE
    @Path("/evaluations")
    public Response clear() {
        log.clear();
        return Response.noContent().build();
    }
}
