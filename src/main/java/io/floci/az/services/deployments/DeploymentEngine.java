package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.core.ArmRouter;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmScopeKind;
import io.floci.az.core.arm.ArmTypeRegistry;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.ResourceGroups;
import io.floci.az.core.directory.Subscription;
import io.floci.az.core.directory.Tenant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * Runs a resource-group template deployment: reads and checks the template, resolves its parameters, orders its
 * resources by their dependencies, and creates each one with a {@code PUT} sent through
 * {@link ArmRouter#dispatchInternal}, so every resource reaches the same routing, gate and handler a client's
 * request would. {@code reference()} and {@code list*()} are internal {@code GET} and {@code POST} requests the
 * same way.
 *
 * <p>{@link #prepare} does everything that can fail before a resource is created, so the caller can answer a
 * template error at once; {@link #run} then creates the resources, and may run after the request that asked for
 * it has been answered. {@link #deploy} is both, in one call. {@link #run} returns once every resource reached a
 * terminal state, or once a cancellation is seen between two resources. A resource
 * whose handler answers 202, or a non-terminal {@code provisioningState}, is polled with {@code GET} for a bounded
 * time. The first resource that fails stops the deployment, which then fails with Azure's
 * {@code DeploymentFailed} error carrying that resource's error as its detail; Azure would go on with the
 * resources that do not depend on it, an emulator simplification.
 */
@ApplicationScoped
public class DeploymentEngine {

    private static final Logger LOG = Logger.getLogger(DeploymentEngine.class);

    static final String DEPLOYMENTS_TYPE = "Microsoft.Resources/deployments";

    /** Azure's message for a failed deployment (recorded, azure-cli test recordings). */
    static final String DEPLOYMENT_FAILED_MESSAGE = "At least one resource deployment operation failed. Please list "
        + "deployment operations for details. Please see https://aka.ms/arm-deployment-operations for usage details.";

    /** Keys of a template resource that describe it to the engine rather than to its provider. */
    private static final Set<String> TEMPLATE_ONLY_KEYS =
        Set.of("type", "apiversion", "name", "dependson", "condition", "comments", "copy", "resources", "scope");

    private static final Set<String> TERMINAL_STATES = Set.of("succeeded", "failed", "canceled");

    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
    private static final int POLL_ATTEMPTS = 120;

    private final Instance<ArmRouter> router;
    private final ResourceGroups groups;
    private final AzureDirectory directory;
    private final ArmTypeRegistry types;
    private final ObjectMapper mapper;

    /**
     * One deployment request, at resource-group scope, or at subscription scope when {@code resourceGroup} is null.
     *
     * @param subscriptionId the subscription, as the path spells it
     * @param resourceGroup  the resource group, as the path spells it; null for a subscription-scope deployment
     * @param name           the deployment name
     * @param properties     the request body's {@code properties}
     * @param origin         the client request, whose caller the internal requests act for
     */
    public record Request(String subscriptionId, String resourceGroup, String name, JsonNode properties,
                          AzureRequest origin) {

        /** The deployment's ARM id. */
        public String id() {
            return scopeId() + "/providers/" + DEPLOYMENTS_TYPE + "/" + name;
        }

        /** True for a deployment at subscription scope. */
        public boolean atSubscription() {
            return resourceGroup == null;
        }

        /** What the deployment's resources sit under: the resource group, or the subscription. */
        String scopeId() {
            String subscription = "/subscriptions/" + subscriptionId;
            return atSubscription() ? subscription : subscription + "/resourceGroups/" + resourceGroup;
        }
    }

    /**
     * What a deployment ended with.
     *
     * @param provisioningState {@code Succeeded} or {@code Failed}
     * @param parameters        the echoed parameters, secure values left out
     * @param outputs           the evaluated outputs, each {@code {"type", "value"}}; empty when it failed
     * @param outputResources   the ids of the resources it created or updated, in creation order
     * @param providers         the namespaces and types it deployed, Azure's {@code providers} shape
     * @param dependencies      the explicit and implicit dependencies, Azure's {@code dependencies} shape
     * @param error             the {@code DeploymentFailed} error, or null when it succeeded
     * @param templateHash      a stable hash of the template
     * @param started           when it started
     * @param finished          when it finished
     */
    public record Result(String provisioningState, ObjectNode parameters, ObjectNode outputs,
                         List<String> outputResources, ArrayNode providers, ArrayNode dependencies, ObjectNode error,
                         String templateHash, Instant started, Instant finished, List<Operation> operations) {

        public Result {
            operations = List.copyOf(operations);
        }
    }

    /**
     * One deployment operation, as {@code DeploymentOperations_List} reports it: the creation of one resource, or
     * the evaluation of the outputs ({@code EvaluateDeploymentOutput}, which has no target resource).
     *
     * @param operationId           16 uppercase hex characters, the form Azure records
     * @param provisioningOperation {@code Create} or {@code EvaluateDeploymentOutput}
     * @param provisioningState     {@code Succeeded} or {@code Failed}
     * @param timestamp             when it ended
     * @param duration              how long it took
     * @param statusCode            the provider's answer as an HTTP status name ({@code OK}, {@code Created}), or
     *                              null when no request was sent
     * @param error                 the error, when it failed
     * @param targetId              the resource's id, or null for the outputs
     * @param targetType            the resource's type, or null for the outputs
     * @param targetName            the resource's name, or null for the outputs
     * @param apiVersion            the api-version it was sent with, or null for the outputs
     */
    public record Operation(String operationId, String provisioningOperation, String provisioningState,
                            Instant timestamp, Duration duration, String statusCode, ObjectNode error,
                            String targetId, String targetType, String targetName, String apiVersion) {
    }

    /** A deployment read, checked and ordered, ready to run. */
    record Plan(DeploymentTemplate template, DeploymentScope scope, Map<String, JsonNode> parameters,
                        List<DependencyGraph.Node> order, Map<String, TemplateScope> instanceScopes) {

        /** The scope a resource is evaluated in: its copy iteration's, or the deployment's. */
        TemplateScope scopeOf(DependencyGraph.Node node) {
            return instanceScopes.getOrDefault(node.id().toLowerCase(Locale.ROOT), scope);
        }
    }

    @Inject
    public DeploymentEngine(Instance<ArmRouter> router, ResourceGroups groups, AzureDirectory directory,
                            ArmTypeRegistry types, ObjectMapper mapper) {
        this.router = router;
        this.groups = groups;
        this.directory = directory;
        this.types = types;
        this.mapper = mapper;
    }

    /**
     * {@code Deployments_Validate}: everything a deployment does before it creates anything. Returns the ids of
     * the resources it would deploy, in creation order; a template it cannot run throws {@link TemplateException}.
     */
    public List<String> validate(Request request) {
        return prepare(request).resourceIds();
    }

    /**
     * A deployment read, checked and ordered by {@link #prepare}, ready for {@link #run}.
     *
     * @param request the deployment request
     * @param plan    its template, scope, resolved parameters and creation order
     */
    public record Prepared(Request request, Plan plan) {

        /** The ids of the resources it will deploy, in creation order. */
        public List<String> resourceIds() {
            return plan.order().stream().map(DependencyGraph.Node::id).toList();
        }

        /** Its parameters as a deployment answer echoes them. */
        public ObjectNode echoedParameters() {
            return ParameterValues.echo(plan.template(), plan.parameters());
        }

        /** A stable hash of its template. */
        public String templateHash() {
            return DeploymentEngine.templateHash(plan.template());
        }
    }

    /**
     * Reads, checks and orders a deployment without creating anything. A template it cannot run throws
     * {@link TemplateException}.
     */
    public Prepared prepare(Request request) {
        return new Prepared(request, plan(request));
    }


    /**
     * {@code Deployments_CreateOrUpdate}, run to the end. A template it cannot run throws
     * {@link TemplateException} before anything is created; a resource that fails ends it {@code Failed}.
     */
    public Result deploy(Request request) {
        return run(prepare(request), () -> false);
    }

    /**
     * Creates the resources of a prepared deployment, in order, and evaluates its outputs. {@code canceled} is read
     * before each resource: once it answers true, nothing more is created and the deployment ends {@code Canceled},
     * leaving what was already created in place, as Azure does.
     */
    public Result run(Prepared prepared, BooleanSupplier canceled) {
        Request request = prepared.request();
        Plan plan = prepared.plan();
        Instant started = Instant.now();
        ObjectNode parameters = ParameterValues.echo(plan.template(), plan.parameters());
        String templateHash = templateHash(plan.template());
        List<String> deployed = new ArrayList<>();
        List<DependencyGraph.Node> deployedNodes = new ArrayList<>();
        List<Operation> operations = new ArrayList<>();
        for (DependencyGraph.Node node : plan.order()) {
            if (canceled.getAsBoolean()) {
                LOG.infov("Deployment {0} canceled after {1} resource(s)", request.name(), deployed.size());
                return new Result("Canceled", parameters, JsonNodeFactory.instance.objectNode(), deployed,
                    providers(deployedNodes), dependencies(plan), null, templateHash, started, Instant.now(),
                    operations);
            }
            ObjectNode definition = plan.template().resources().get(node.index()).definition();
            if (definition.has("condition") && !truthy(evaluate(definition.get("condition"), plan.scopeOf(node)))) {
                LOG.debugv("Deployment {0}: {1} skipped, its condition is false", request.name(), node.id());
                continue;
            }
            ObjectNode failure = create(request, plan, node, definition, operations);
            if (failure != null) {
                return new Result("Failed", parameters, JsonNodeFactory.instance.objectNode(), deployed,
                    providers(deployedNodes), dependencies(plan), deploymentFailed(request, failure), templateHash,
                    started, Instant.now(), operations);
            }
            deployed.add(node.id());
            deployedNodes.add(node);
        }
        ObjectNode outputs;
        Instant outputsStarted = Instant.now();
        try {
            outputs = outputs(plan);
        } catch (TemplateException e) {
            ObjectNode detail = detail(e.code(), request.id(), e.getMessage());
            operations.add(outputOperation("Failed", detail, outputsStarted));
            return new Result("Failed", parameters, JsonNodeFactory.instance.objectNode(), deployed,
                providers(deployedNodes), dependencies(plan), deploymentFailed(request, detail), templateHash, started,
                Instant.now(), operations);
        }
        operations.add(outputOperation("Succeeded", null, outputsStarted));
        LOG.infov("Deployment {0} succeeded: {1} resource(s)", request.name(), deployed.size());
        return new Result("Succeeded", parameters, outputs, deployed, providers(deployedNodes), dependencies(plan),
            null, templateHash, started, Instant.now(), operations);
    }

    // ── Preparing ──────────────────────────────────────────────────────────────────

    private Plan plan(Request request) {
        JsonNode properties = request.properties();
        if (properties == null || !properties.isObject()) {
            throw DeploymentTemplate.invalid("The deployment 'properties' are missing.");
        }
        String mode = properties.path("mode").asText("");
        if (mode.isEmpty()) {
            throw DeploymentTemplate.invalid("The deployment 'mode' is required.");
        }
        if (mode.equalsIgnoreCase("Complete")) {
            throw new TemplateException("DeploymentModeNotSupported", "The deployment mode 'Complete' is not "
                + "supported by floci-az; use 'Incremental'.");
        }
        if (properties.has("templateLink") || properties.has("parametersLink")) {
            throw DeploymentTemplate.invalid("Linked templates and parameter files (templateLink, parametersLink) "
                + "are not supported by floci-az; send the template and parameters inline.");
        }
        if (!properties.has("template")) {
            throw DeploymentTemplate.invalid("The deployment 'template' is missing.");
        }
        DeploymentTemplate template = DeploymentTemplate.parse(properties.get("template"));
        Map<String, JsonNode> supplied = ParameterValues.supplied(template, properties.get("parameters"));
        List<DependencyGraph.Node> nodes = new ArrayList<>();
        DeploymentScope scope = scope(request, template, supplied, nodes);
        Map<String, JsonNode> parameters = scope.resolvedParameters();
        Map<String, TemplateScope> instanceScopes = new LinkedHashMap<>();
        Set<String> emptyLoops = new LinkedHashSet<>();
        for (DeploymentTemplate.Resource resource : template.resources()) {
            JsonNode copy = resource.definition().get("copy");
            if (copy == null) {
                nodes.add(node(request, resource, scope, null));
                continue;
            }
            // A copy loop is one resource per iteration, each evaluated with copyIndex() at its iteration.
            String loop = copy.get("name").asText();
            int count = CopyLoops.count(copy.get("count"), scope, loop);
            if (count == 0) {
                emptyLoops.add(loop.toLowerCase(Locale.ROOT));
            }
            for (int i = 0; i < count; i++) {
                CopyScope iteration = new CopyScope(scope, loop, i, true);
                DependencyGraph.Node node = node(request, resource, iteration, loop);
                instanceScopes.put(node.id().toLowerCase(Locale.ROOT), iteration);
                nodes.add(node);
            }
        }
        List<DependencyGraph.Node> withImplicit = new ArrayList<>();
        for (DependencyGraph.Node node : nodes) {
            TemplateScope nodeScope = instanceScopes.getOrDefault(node.id().toLowerCase(Locale.ROOT), scope);
            withImplicit.add(withImplicitReferences(node, template, nodeScope, emptyLoops));
        }
        return new Plan(template, scope, parameters, DependencyGraph.order(withImplicit), instanceScopes);
    }

    private DependencyGraph.Node node(Request request, DeploymentTemplate.Resource resource, TemplateScope scope,
                                      String loop) {
        ObjectNode definition = resource.definition();
        String type = text(evaluate(definition.get("type"), scope), "type");
        String name = text(evaluate(definition.get("name"), scope), "name");
        List<String> dependsOn = new ArrayList<>();
        JsonNode declared = definition.path("dependsOn");
        if (declared.isArray()) {
            for (JsonNode entry : declared) {
                dependsOn.add(text(evaluate(entry, scope), "dependsOn"));
            }
        }
        if (request.atSubscription()) {
            types.type(ArmResourcePath.normalizedType(type))
                .filter(served -> served.scope() == ArmScopeKind.RESOURCE_GROUP)
                .ifPresent(served -> {
                    throw DeploymentTemplate.invalid("The resource '" + name + "' of type '" + type + "' belongs "
                        + "in a resource group and cannot be deployed at subscription scope; deploy it through a "
                        + "nested deployment that names the resource group.");
                });
        }
        return new DependencyGraph.Node(resource.index(), ArmResourcePath.id(request.scopeId(), type, name),
            type, name, dependsOn, loop);
    }

    /**
     * {@code node} with the resources its {@code reference()} and {@code list*()} calls name added, and without
     * the {@code dependsOn} entries that name a copy loop of no iterations, which depend on nothing.
     */
    private DependencyGraph.Node withImplicitReferences(DependencyGraph.Node node, DeploymentTemplate template,
                                                       TemplateScope scope, Set<String> emptyLoops) {
        Set<String> dependsOn = new LinkedHashSet<>();
        for (String entry : node.dependsOn()) {
            if (!emptyLoops.contains(entry.toLowerCase(Locale.ROOT))) {
                dependsOn.add(entry);
            }
        }
        collectReferences(template.resources().get(node.index()).definition(), scope, dependsOn);
        return new DependencyGraph.Node(node.index(), node.id(), node.type(), node.name(), List.copyOf(dependsOn),
            node.loop());
    }

    /**
     * Adds to {@code dependsOn} the resources {@code value}'s {@code reference()} and {@code list*()} calls name.
     * A property copy loop's input is read once per iteration, in that iteration's scope, so a reference that
     * reads {@code copyIndex('loop')} evaluates as the run will evaluate it.
     */
    private static void collectReferences(JsonNode value, TemplateScope scope, Set<String> dependsOn) {
        if (value instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                if (field.getKey().equals("copy") && field.getValue().isArray()) {
                    for (JsonNode loop : field.getValue()) {
                        collectLoopReferences(loop, scope, dependsOn);
                    }
                } else {
                    collectReferences(field.getValue(), scope, dependsOn);
                }
            }
            return;
        }
        if (value instanceof ArrayNode array) {
            array.forEach(item -> collectReferences(item, scope, dependsOn));
            return;
        }
        for (Expression argument : DependencyGraph.implicitReferences(value)) {
            JsonNode evaluated = ExpressionEvaluator.evaluate(argument, scope);
            if (evaluated.isTextual() && DependencyGraph.isName(evaluated.asText())) {
                dependsOn.add(evaluated.asText());
            }
        }
    }

    private static void collectLoopReferences(JsonNode loop, TemplateScope scope, Set<String> dependsOn) {
        String name = CopyLoops.name(loop);
        JsonNode input = loop.get("input");
        if (DependencyGraph.implicitReferences(input).isEmpty()) {
            return;
        }
        int count = CopyLoops.count(loop.get("count"), scope, name);
        scope.expand(count);
        for (int i = 0; i < count; i++) {
            collectReferences(input, new CopyScope(scope, name, i, false), dependsOn);
        }
    }

    private DeploymentScope scope(Request request, DeploymentTemplate template, Map<String, JsonNode> supplied,
                                  List<DependencyGraph.Node> nodes) {
        String tenantId = directory.tenantOfSubscription(request.subscriptionId());
        CloudProfile cloud = request.origin() != null && request.origin().scope() != null
            ? request.origin().scope().cloud() : directory.cloudOfTenant(tenantId);
        DeploymentScope.LiveCalls live = new DeploymentScope.LiveCalls() {
            @Override
            public JsonNode reference(String resourceNameOrId, String apiVersion, boolean full) {
                return DeploymentEngine.this.reference(request, template, nodes, resourceNameOrId, apiVersion, full);
            }

            @Override
            public JsonNode list(String resourceId, String action, String apiVersion, JsonNode body) {
                return DeploymentEngine.this.list(request, resourceId, action, apiVersion, body);
            }
        };
        return new DeploymentScope(template, supplied,
            request.atSubscription() ? null : resourceGroupObject(request, cloud),
            subscriptionObject(request, tenantId), tenantObject(tenantId), environmentObject(cloud),
            self -> deploymentObject(request, template), live);
    }

    // ── Creating ───────────────────────────────────────────────────────────────────

    /** Creates one resource; null when it ended {@code Succeeded}, else the error detail for the deployment. */
    private ObjectNode create(Request request, Plan plan, DependencyGraph.Node node, ObjectNode definition,
                              List<Operation> operations) {
        Instant started = Instant.now();
        ObjectNode evaluated;
        String apiVersion;
        try {
            // The resource's own copy loop is how it was expanded, not a property to send.
            ObjectNode withoutLoop = definition.deepCopy();
            withoutLoop.remove("copy");
            evaluated = (ObjectNode) CopyLoops.evaluate(withoutLoop, plan.scopeOf(node));
            apiVersion = text(evaluated.get("apiVersion"), "apiVersion");
        } catch (TemplateException e) {
            ObjectNode failure = detail(e.code(), node.id(), e.getMessage());
            operations.add(resourceOperation(node, null, "Failed", null, failure, started));
            return failure;
        }
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, JsonNode> field : evaluated.properties()) {
            if (!TEMPLATE_ONLY_KEYS.contains(field.getKey().toLowerCase(Locale.ROOT))) {
                body.set(field.getKey(), field.getValue());
            }
        }
        Response response = router.get().dispatchInternal("PUT", node.id(), Map.of("api-version", apiVersion),
            Map.of(), bytes(body), request.origin());
        JsonNode answer = entity(response);
        String statusCode = statusName(response.getStatus());
        if (response.getStatus() / 100 != 2) {
            ObjectNode failure = handlerError(node.id(), response, answer);
            operations.add(resourceOperation(node, apiVersion, "Failed", statusCode, failure, started));
            return failure;
        }
        String state = answer.path("properties").path("provisioningState").asText("Succeeded");
        if (response.getStatus() == 202 || !TERMINAL_STATES.contains(state.toLowerCase(Locale.ROOT))) {
            state = poll(request, node.id(), apiVersion);
        }
        if (!state.equalsIgnoreCase("Succeeded")) {
            ObjectNode failure = detail("ResourceDeploymentFailure", node.id(), "The resource write operation failed "
                + "to complete successfully, because it reached terminal provisioning state '" + state + "'.");
            operations.add(resourceOperation(node, apiVersion, "Failed", statusCode, failure, started));
            return failure;
        }
        operations.add(resourceOperation(node, apiVersion, "Succeeded", statusCode, null, started));
        return null;
    }

    private static Operation resourceOperation(DependencyGraph.Node node, String apiVersion, String state,
                                               String statusCode, ObjectNode error, Instant started) {
        Instant now = Instant.now();
        return new Operation(operationId(), "Create", state, now, Duration.between(started, now), statusCode, error,
            node.id(), ArmResourcePath.normalizedType(node.type()), node.name(), apiVersion);
    }

    private static Operation outputOperation(String state, ObjectNode error, Instant started) {
        Instant now = Instant.now();
        return new Operation(operationId(), "EvaluateDeploymentOutput", state, now, Duration.between(started, now),
            "OK", error, null, null, null, null);
    }

    /** An operation id in the form Azure records: 16 uppercase hex characters. */
    private static String operationId() {
        return String.format("%016X", ThreadLocalRandom.current().nextLong());
    }

    /** The HTTP status as the name Azure's {@code statusCode} uses ({@code OK}, {@code Created}, ...). */
    static String statusName(int status) {
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
            case 503 -> "ServiceUnavailable";
            default -> Integer.toString(status);
        };
    }

    /** Polls a resource with {@code GET} until its {@code provisioningState} is terminal, for a bounded time. */
    private String poll(Request request, String id, String apiVersion) {
        for (int attempt = 0; attempt < POLL_ATTEMPTS; attempt++) {
            Response response = router.get().dispatchInternal("GET", id, Map.of("api-version", apiVersion),
                request.origin());
            JsonNode answer = entity(response);
            String state = answer.path("properties").path("provisioningState").asText("Succeeded");
            if (response.getStatus() == 200 && TERMINAL_STATES.contains(state.toLowerCase(Locale.ROOT))) {
                return state;
            }
            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Canceled";
            }
        }
        LOG.warnv("Deployment {0}: {1} did not reach a terminal state in time", request.name(), id);
        return "Failed";
    }

    // ── Live template functions ────────────────────────────────────────────────────

    private JsonNode reference(Request request, DeploymentTemplate template, List<DependencyGraph.Node> nodes,
                               String nameOrId, String apiVersion, boolean full) {
        DependencyGraph.Node declared = nodes.stream()
            .filter(node -> DependencyGraph.isName(nameOrId) ? node.name().equalsIgnoreCase(nameOrId)
                : ArmResourcePath.canonical(node.id()).equalsIgnoreCase(ArmResourcePath.canonical(nameOrId)))
            .findFirst()
            .orElse(null);
        if (declared == null && DependencyGraph.isName(nameOrId)) {
            throw DeploymentTemplate.invalid("The template reference '" + nameOrId
                + "' is not valid: no resource in the template has this name.");
        }
        String id = declared == null ? ArmResourcePath.canonical(nameOrId) : declared.id();
        String version = apiVersion;
        if (version == null && declared != null) {
            JsonNode templateVersion = template.resources().get(declared.index()).definition().path("apiVersion");
            if (templateVersion.isTextual() && !ExpressionParser.isExpression(templateVersion.asText())) {
                version = templateVersion.asText();
            }
        }
        if (version == null) {
            version = newestApiVersion(id);
        }
        Response response = router.get().dispatchInternal("GET", id, Map.of("api-version", version),
            request.origin());
        JsonNode answer = entity(response);
        if (response.getStatus() != 200) {
            throw new TemplateException(errorCode(answer, "ResourceNotFound"),
                "The referenced resource '" + id + "' could not be read: " + errorMessage(answer));
        }
        if (!full) {
            return answer.path("properties").isMissingNode() ? JsonNodeFactory.instance.objectNode()
                : answer.get("properties");
        }
        ObjectNode whole = answer.deepCopy();
        whole.put("apiVersion", version);
        return whole;
    }

    private JsonNode list(Request request, String resourceId, String action, String apiVersion, JsonNode body) {
        String version = apiVersion != null ? apiVersion : newestApiVersion(resourceId);
        Response response = router.get().dispatchInternal("POST", ArmResourcePath.canonical(resourceId) + "/" + action,
            Map.of("api-version", version), Map.of(), body == null ? null : bytes(body), request.origin());
        JsonNode answer = entity(response);
        if (response.getStatus() / 100 != 2) {
            throw new TemplateException(errorCode(answer, "BadRequest"),
                "The list operation '" + action + "' on '" + resourceId + "' failed: " + errorMessage(answer));
        }
        return answer;
    }

    /** Azure's newest api-version for the type {@code id} addresses, for a call that names none. */
    private String newestApiVersion(String id) {
        ArmResourceId parsed = ArmResourceId.parse(id).orElseThrow(() ->
            DeploymentTemplate.invalid("'" + id + "' is not a resource id."));
        ArmResourceType type = types.match(parsed);
        if (type != null && !types.apiVersions(type).isEmpty()) {
            return types.apiVersions(type).getFirst();
        }
        throw DeploymentTemplate.invalid("An api-version is required to reference '" + id
            + "', which floci-az does not serve.");
    }

    // ── Outputs and the deployment's answer ────────────────────────────────────────

    private ObjectNode outputs(Plan plan) {
        ObjectNode outputs = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, JsonNode> field : plan.template().outputs().properties()) {
            JsonNode declared = field.getValue();
            if (declared.has("condition") && !truthy(evaluate(declared.get("condition"), plan.scope()))) {
                continue;
            }
            ObjectNode output = outputs.putObject(field.getKey());
            output.put("type", declared.path("type").asText("String"));
            output.set("value", declared.has("copy") ? outputLoop(field.getKey(), declared.get("copy"), plan.scope())
                : evaluate(declared.get("value"), plan.scope()));
        }
        return outputs;
    }

    /** An output copy loop ({@code copy-outputs.md}): {@code count} evaluations of {@code input}, unnamed. */
    private static ArrayNode outputLoop(String output, JsonNode copy, TemplateScope scope) {
        int count = CopyLoops.count(copy.get("count"), scope, output);
        if (!copy.has("input")) {
            throw DeploymentTemplate.invalid("The copy loop of output '" + output + "' has no 'input'.");
        }
        scope.expand(count);
        ArrayNode values = JsonNodeFactory.instance.arrayNode();
        for (int i = 0; i < count; i++) {
            values.add(CopyLoops.evaluate(copy.get("input"), new CopyScope(scope, null, i, true)));
        }
        return values;
    }

    private ArrayNode providers(List<DependencyGraph.Node> deployed) {
        Map<String, Map<String, Set<String>>> byNamespace = new LinkedHashMap<>();
        for (DependencyGraph.Node node : deployed) {
            List<String> segments = ArmResourcePath.segments(node.type());
            byNamespace.computeIfAbsent(segments.getFirst(), ignored -> new LinkedHashMap<>())
                .computeIfAbsent(String.join("/", segments.subList(1, segments.size())),
                    ignored -> new LinkedHashSet<>());
        }
        ArrayNode providers = JsonNodeFactory.instance.arrayNode();
        byNamespace.forEach((namespace, resourceTypes) -> {
            ObjectNode provider = providers.addObject();
            provider.put("namespace", namespace);
            ArrayNode typesNode = provider.putArray("resourceTypes");
            resourceTypes.keySet().forEach(resourceType -> typesNode.addObject().put("resourceType", resourceType));
        });
        return providers;
    }

    private ArrayNode dependencies(Plan plan) {
        ArrayNode dependencies = JsonNodeFactory.instance.arrayNode();
        for (DependencyGraph.Node node : plan.order()) {
            if (node.dependsOn().isEmpty()) {
                continue;
            }
            ObjectNode dependency = dependencies.addObject();
            dependency.put("id", node.id());
            dependency.put("resourceType", ArmResourcePath.normalizedType(node.type()));
            dependency.put("resourceName", node.name());
            ArrayNode on = dependency.putArray("dependsOn");
            for (String reference : node.dependsOn()) {
                plan.order().stream()
                    .filter(target -> target.id().equalsIgnoreCase(reference) || target.name().equalsIgnoreCase(reference)
                        || (ArmResourcePath.normalizedType(target.type()) + "/" + target.name())
                            .equalsIgnoreCase(ArmResourcePath.normalizedType(reference)))
                    .findFirst()
                    .ifPresent(target -> on.addObject()
                        .put("id", target.id())
                        .put("resourceType", ArmResourcePath.normalizedType(target.type()))
                        .put("resourceName", target.name()));
            }
        }
        return dependencies;
    }

    private ObjectNode deploymentFailed(Request request, ObjectNode detail) {
        ObjectNode error = JsonNodeFactory.instance.objectNode();
        error.put("code", "DeploymentFailed");
        error.put("target", request.id());
        error.put("message", DEPLOYMENT_FAILED_MESSAGE);
        error.putArray("details").add(detail);
        return error;
    }

    // ── Scope objects ──────────────────────────────────────────────────────────────

    private ObjectNode resourceGroupObject(Request request, CloudProfile cloud) {
        return groups.get(request.subscriptionId(), request.resourceGroup())
            .map(group -> (ObjectNode) mapper.valueToTree(group.body()))
            .orElseGet(() -> {
                ObjectNode group = JsonNodeFactory.instance.objectNode();
                group.put("id", request.scopeId());
                group.put("name", request.resourceGroup());
                group.put("type", "Microsoft.Resources/resourceGroups");
                group.put("location", cloud.effectiveDefaultLocation());
                group.putObject("properties").put("provisioningState", "Succeeded");
                return group;
            });
    }

    private ObjectNode subscriptionObject(Request request, String tenantId) {
        ObjectNode subscription = JsonNodeFactory.instance.objectNode();
        subscription.put("id", "/subscriptions/" + request.subscriptionId());
        subscription.put("subscriptionId", request.subscriptionId());
        subscription.put("tenantId", tenantId);
        subscription.put("displayName", directory.subscription(request.subscriptionId())
            .map(Subscription::displayName).orElse(request.subscriptionId()));
        return subscription;
    }

    private ObjectNode tenantObject(String tenantId) {
        ObjectNode tenant = JsonNodeFactory.instance.objectNode();
        tenant.put("id", "/tenants/" + tenantId);
        tenant.put("tenantId", tenantId);
        directory.tenant(tenantId).map(Tenant::displayName).ifPresent(name -> tenant.put("displayName", name));
        return tenant;
    }

    /** {@code environment()}: the cloud's own metadata document, Azure's endpoints and suffixes, named for it. */
    private ObjectNode environmentObject(CloudProfile cloud) {
        ObjectNode environment = cloud.metadataPayload("2022-09-01")
            .filter(JsonNode::isObject)
            .map(payload -> (ObjectNode) payload)
            .orElseGet(JsonNodeFactory.instance::objectNode);
        environment.put("name", cloud.name());
        return environment;
    }

    private ObjectNode deploymentObject(Request request, DeploymentTemplate template) {
        ObjectNode deployment = JsonNodeFactory.instance.objectNode();
        deployment.put("name", request.name());
        ObjectNode properties = deployment.putObject("properties");
        properties.set("template", template.template().deepCopy());
        properties.put("templateHash", templateHash(template));
        JsonNode parameters = request.properties().get("parameters");
        properties.set("parameters", parameters == null ? JsonNodeFactory.instance.objectNode() : parameters.deepCopy());
        properties.put("mode", request.properties().path("mode").asText());
        properties.put("provisioningState", "Accepted");
        return deployment;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────

    /** A stable decimal hash of the template, in the form Azure gives {@code templateHash}. */
    static String templateHash(DeploymentTemplate template) {
        long hash = 1125899906842597L;
        for (byte b : template.template().toString().getBytes(StandardCharsets.UTF_8)) {
            hash = 31 * hash + b;
        }
        return Long.toUnsignedString(hash);
    }

    private static JsonNode evaluate(JsonNode value, TemplateScope scope) {
        return ExpressionEvaluator.evaluateValue(value, scope);
    }

    private static boolean truthy(JsonNode value) {
        return value.isBoolean() ? value.asBoolean() : value.asText("").equalsIgnoreCase("true");
    }

    private static String text(JsonNode value, String property) {
        if (value == null || !value.isTextual()) {
            throw DeploymentTemplate.invalid("The template resource '" + property + "' must evaluate to a string.");
        }
        return value.asText();
    }

    private byte[] bytes(JsonNode body) {
        try {
            return mapper.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot serialize a template resource body", e);
        }
    }

    /** A handler response's body as JSON, whatever form the handler built it in; an empty object for none. */
    private JsonNode entity(Response response) {
        Object entity = response.getEntity();
        try {
            if (entity == null) {
                return JsonNodeFactory.instance.objectNode();
            }
            if (entity instanceof JsonNode node) {
                return node;
            }
            if (entity instanceof String text) {
                return text.isBlank() ? JsonNodeFactory.instance.objectNode() : mapper.readTree(text);
            }
            if (entity instanceof byte[] raw) {
                return raw.length == 0 ? JsonNodeFactory.instance.objectNode() : mapper.readTree(raw);
            }
            return mapper.valueToTree(entity);
        } catch (IOException | IllegalArgumentException e) {
            LOG.debugv("Deployment engine: a handler answered a body that is not JSON ({0})", e.getMessage());
            return JsonNodeFactory.instance.objectNode();
        }
    }

    private static ObjectNode handlerError(String id, Response response, JsonNode answer) {
        JsonNode error = answer.path("error");
        if (error.isObject()) {
            ObjectNode detail = error.deepCopy();
            if (!detail.has("target")) {
                detail.put("target", id);
            }
            return detail;
        }
        return detail("ResourceDeploymentFailure", id, "The resource write operation failed with status "
            + response.getStatus() + ".");
    }

    private static ObjectNode detail(String code, String target, String message) {
        ObjectNode detail = JsonNodeFactory.instance.objectNode();
        detail.put("code", code);
        detail.put("target", target);
        detail.put("message", message);
        return detail;
    }

    private static String errorCode(JsonNode answer, String fallback) {
        return answer.path("error").path("code").asText(fallback);
    }

    private static String errorMessage(JsonNode answer) {
        return answer.path("error").path("message").asText("no error message");
    }
}
