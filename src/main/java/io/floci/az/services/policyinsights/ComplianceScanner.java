package io.floci.az.services.policyinsights;

import io.floci.az.core.arm.ArmCall;
import io.floci.az.core.arm.ArmRequestBody;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResources;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.ResourceGroup;
import io.floci.az.core.directory.ResourceGroups;
import io.floci.az.core.directory.Subscription;
import io.floci.az.services.arm.ArmHandler;
import io.floci.az.services.policy.evaluation.PolicyEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An on-demand compliance scan, what {@code triggerEvaluation} starts in Azure: every resource the ARM
 * index lists in the scope (and every resource group) is evaluated against the assignments that apply,
 * and the results replace the stored states. Runs synchronously, so the operation it starts is already
 * complete when its status is polled.
 */
@ApplicationScoped
public class ComplianceScanner {

    private static final Logger LOG = Logger.getLogger(ComplianceScanner.class);

    private final ArmHandler armHandler;
    private final AzureDirectory directory;
    private final ResourceGroups resourceGroups;
    private final PolicyEngine engine;
    private final ComplianceStore store;

    @Inject
    public ComplianceScanner(ArmHandler armHandler, AzureDirectory directory, ResourceGroups resourceGroups,
                             PolicyEngine engine, ComplianceStore store) {
        this.armHandler = armHandler;
        this.directory = directory;
        this.resourceGroups = resourceGroups;
        this.engine = engine;
        this.store = store;
    }

    /** Scans one subscription, or one of its resource groups when {@code resourceGroup} is given; returns the resources evaluated. */
    public int scan(String subscriptionId, String resourceGroup) {
        int evaluated = 0;
        for (ResourceGroup group : resourceGroups.list(subscriptionId)) {
            if (resourceGroup != null && !group.name().equalsIgnoreCase(resourceGroup)) {
                continue;
            }
            Map<String, Object> body = new LinkedHashMap<>(group.body());
            evaluate("/subscriptions/" + subscriptionId + "/resourceGroups/" + group.name(), body);
            evaluated++;
        }
        for (Map<String, Object> resource : armHandler.indexedResources(subscriptionId, resourceGroup, true)) {
            Map<String, Object> body = new LinkedHashMap<>(ArmResources.stripInternal(resource));
            if (!(body.get("id") instanceof String id)) {
                continue;
            }
            evaluate(id, body);
            evaluated++;
        }
        LOG.infof("Policy compliance scan of %s%s evaluated %d resources", subscriptionId,
            resourceGroup == null ? "" : "/" + resourceGroup, evaluated);
        return evaluated;
    }

    /** Scans every subscription the directory knows. */
    public int scanAll() {
        int evaluated = 0;
        for (Subscription subscription : directory.subscriptions()) {
            evaluated += scan(subscription.id(), null);
        }
        return evaluated;
    }

    private void evaluate(String id, Map<String, Object> body) {
        ArmResourceId resourceId = ArmResourceId.parse(id).orElse(null);
        if (resourceId == null) {
            return;
        }
        ArmCall call = new ArmCall(null, resourceId, null, "PUT", null, ArmRequestBody.of(body), null);
        if (!PolicyEngine.evaluates(call)) {
            return;
        }
        PolicyEngine.Outcome outcome = engine.evaluate(new PolicyEngine.Request(call, body, null, Map.of()));
        store.forgetResource(id);
        store.record(resourceId, body, outcome.evaluations());
    }
}
