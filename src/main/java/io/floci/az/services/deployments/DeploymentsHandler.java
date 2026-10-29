package io.floci.az.services.deployments;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.RequestUrls;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ResourceGroupCleanup;
import io.floci.az.core.directory.ResourceGroups;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code Microsoft.Resources/deployments} at resource-group and subscription scope: ARM template deployments, run by the
 * {@link DeploymentEngine}.
 *
 * <p>Wire contract, Resources spec 2025-04-01 (the version the az CLI sends; azurerm sends 2020-06-01, whose
 * operations and shapes are the same):
 *
 * <ul>
 *   <li>{@code PUT} ({@code Deployments_CreateOrUpdate}) checks the template at once, a template it cannot run
 *       being {@code 400} with the template error, then answers {@code 201}, or {@code 200} for an existing name,
 *       with {@code provisioningState: Accepted} and an {@code Azure-AsyncOperation} header, as Azure records it,
 *       and runs the deployment in the background. {@code GET .../operationStatuses/{id}} answers
 *       {@code {"status": "Accepted"}} until it ends, then {@code Succeeded}, {@code Failed} with the error, or
 *       {@code Canceled}. A {@code PUT} or {@code DELETE} while it runs is {@code 409 DeploymentActive}.</li>
 *   <li>{@code POST cancel} ({@code 204}) stops a running deployment before its next resource and leaves what it
 *       created in place; it is {@code 409} for a deployment that is no longer running</li>
 *   <li>{@code GET}, {@code HEAD}, list with {@code $filter=provisioningState eq '...'} and {@code $top}</li>
 *   <li>{@code DELETE} removes the deployment record only, never its resources, as Azure does; it answers
 *       {@code 204} at once, where Azure answers {@code 202} and polls</li>
 *   <li>{@code POST validate} ({@code 200}, or {@code 400} with the template error) and
 *       {@code POST exportTemplate} ({@code {"template": ...}})</li>
 * </ul>
 *
 * <p>The body is read as JSONC, the way the az CLI sends it (see {@link #LENIENT}).
 *
 * <p>Emulator choices, with no Azure source to settle them: a {@code Retry-After} of 1 second on the {@code PUT}
 * (Azure sends none, and clients then fall back to a much longer poll interval), and the code of a refused cancel.
 * {@code whatIf}, {@code operations} and deployments at any other scope answer {@code 404} for now. Deployments
 * live in memory, as the rest of ARM state does.
 */
@ApplicationScoped
public class DeploymentsHandler implements AzureServiceHandler, Resettable, ResourceGroupCleanup {

    private static final Logger LOG = Logger.getLogger(DeploymentsHandler.class);

    /**
     * The az CLI's {@code JsonCTemplatePolicy} splices the template file's raw text into the body behind an
     * unquoted key ({@code ..., template:{...}}}), so the body is JSONC: unquoted field names, comments and
     * trailing commas.
     */
    private static final JsonMapper LENIENT = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
        .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
        .build();

    private static final String NAMESPACE = "Microsoft.Resources";

    /** The deployments marker, matched in any case: ARM resolves namespaces and types case-insensitively. */
    private static final String MARKER = ArmPaths.providerMarker(NAMESPACE) + "deployments";
    private static final String TEMPLATE_HASH_MARKER = ArmPaths.providerMarker(NAMESPACE) + "calculateTemplateHash";

    /** {@code providers/Microsoft.Resources/calculateTemplateHash}, at tenant level. */
    private static final Pattern TEMPLATE_HASH = Pattern.compile(
        "providers/microsoft\\.resources/calculatetemplatehash/?", Pattern.CASE_INSENSITIVE);

    /**
     * {@code [subscriptions/{sub}[/resourcegroups/{rg}]/]providers/Microsoft.Resources/deployments[/{name}[/{action}]]}:
     * resource-group, subscription or tenant scope.
     */
    private static final Pattern PATH = Pattern.compile(
        "(?:subscriptions/([^/]+)(?:/resourcegroups/([^/]+))?/)?providers/microsoft\\.resources/deployments"
            + "(?:/([^/?]+)(?:/([^/?]+))?)?/?",
        Pattern.CASE_INSENSITIVE);

    /**
     * {@code .../deployments/{name}/operations[/{id}]}, with or without the {@code providers/Microsoft.Resources}
     * segment: the spec's {@code DeploymentOperations_List} path has none, and the az CLI sends that form.
     */
    private static final Pattern OPERATIONS = Pattern.compile(
        "(?:subscriptions/([^/]+)(?:/resourcegroups/([^/]+))?/)?(?:providers/microsoft\\.resources/)?deployments/"
            + "([^/?]+)/operations(?:/([^/?]+))?/?", Pattern.CASE_INSENSITIVE);

    /** {@code subscriptions/{sub}/resourcegroups/{rg}/deployments/...}: the form with no provider segment. */
    private static final Pattern NO_PROVIDER_SEGMENT = Pattern.compile(
        "subscriptions/[^/]+/resourcegroups/[^/]+/deployments/.*", Pattern.CASE_INSENSITIVE);

    /** {@code .../deployments/{name}/operationStatuses/{id}}. */
    private static final Pattern OPERATION_STATUS = Pattern.compile(
        "(?:subscriptions/[^/]+(?:/resourcegroups/[^/]+)?/)?providers/microsoft\\.resources/deployments/[^/?]+"
            + "/operationstatuses/([^/?]+)/?", Pattern.CASE_INSENSITIVE);

    private static final Pattern STATE_FILTER =
        Pattern.compile("provisioningState\\s+eq\\s+'([^']*)'", Pattern.CASE_INSENSITIVE);

    /** Seconds a client waits between polls; short, because a local deployment finishes quickly. */
    static final String RETRY_AFTER_SECONDS = "1";

    private static final Set<String> ACTIVE_STATES = Set.of("accepted", "running");

    private final EmulatorConfig config;
    private final DeploymentEngine engine;
    private final ResourceGroups groups;
    private final LinkedTemplates links;

    /** Runs each deployment after its {@code PUT} has been answered; virtual threads, as runs mostly wait. */
    private final ExecutorService runner = Executors.newVirtualThreadPerTaskExecutor();

    /** Every deployment, keyed by {@link #key}. */
    private final Map<String, StoredDeployment> deployments = new ConcurrentHashMap<>();

    /** The status each deployment run answers at {@code operationStatuses/{id}}, by operation id. */
    private final Map<String, ObjectNode> operations = new ConcurrentHashMap<>();

    /**
     * One deployment as {@code GET} answers it, and the template {@code exportTemplate} returns.
     *
     * @param body        the {@code DeploymentExtended} body
     * @param template    the template as sent
     * @param operationId the run whose status {@code operationStatuses} answers
     * @param canceled    set by {@code cancel}, read by the run before each resource
     * @param operations  its deployment operations, empty until the run ends
     */
    record StoredDeployment(ObjectNode body, JsonNode template, String operationId, AtomicBoolean canceled,
                            ArrayNode operations) {

        String state() {
            return body.path("properties").path("provisioningState").asText();
        }

        boolean active() {
            return ACTIVE_STATES.contains(state().toLowerCase(Locale.ROOT));
        }
    }

    @Inject
    public DeploymentsHandler(EmulatorConfig config, DeploymentEngine engine, ResourceGroups groups,
                              LinkedTemplates links) {
        this.config = config;
        this.engine = engine;
        this.groups = groups;
        this.links = links;
    }

    @Override
    public String getServiceType() {
        return "deployments";
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().deployments().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
            .provider(NAMESPACE, DeploymentsHandler::isDeploymentPath)
            .build();
    }

    /**
     * True for a deployment path with no provider segment ({@code subscriptions/{sub}/resourcegroups/{rg}/
     * deployments/...}), which no provider route can match; {@code ArmHandler} hands it here.
     */
    public static boolean isProviderlessPath(String path) {
        int query = path.indexOf('?');
        return NO_PROVIDER_SEGMENT.matcher(query < 0 ? path : path.substring(0, query)).matches();
    }

    /**
     * A path under {@code /providers/Microsoft.Resources/deployments}, at any scope, or the tenant-level
     * {@code /providers/Microsoft.Resources/calculateTemplateHash}.
     */
    static boolean isDeploymentPath(String path) {
        return ArmPaths.indexOfIgnoreCase(path, MARKER) >= 0
            || ArmPaths.indexOfIgnoreCase(path, TEMPLATE_HASH_MARKER) >= 0;
    }

    @Override
    public Response handle(AzureRequest req) {
        String path = req.resourcePath();
        int query = path.indexOf('?');
        String bare = query < 0 ? path : path.substring(0, query);
        if (TEMPLATE_HASH.matcher(bare).matches()) {
            return "POST".equals(req.method().toUpperCase(Locale.ROOT)) ? calculateTemplateHash(req)
                : Response.status(405).build();
        }
        Matcher operationsPath = OPERATIONS.matcher(bare);
        if (operationsPath.matches()) {
            if (!"GET".equals(req.method().toUpperCase(Locale.ROOT))) {
                return Response.status(405).build();
            }
            return operations(req, bare, operationsPath.group(1), operationsPath.group(2), operationsPath.group(3),
                operationsPath.group(4));
        }
        Matcher operationStatus = OPERATION_STATUS.matcher(bare);
        if (operationStatus.matches()) {
            if (!"GET".equals(req.method().toUpperCase(Locale.ROOT))) {
                return Response.status(405).build();
            }
            return operationStatus(operationStatus.group(1));
        }
        Matcher matcher = PATH.matcher(query < 0 ? path : path.substring(0, query));
        if (!matcher.matches()) {
            return ArmErrors.notFound("floci-az serves deployments at resource-group, subscription and tenant scope "
                + "only: " + path);
        }
        String subscriptionId = matcher.group(1);
        String resourceGroup = matcher.group(2);
        String name = matcher.group(3);
        String action = matcher.group(4);
        String method = req.method().toUpperCase(Locale.ROOT);
        if (resourceGroup != null && !groups.exists(subscriptionId, resourceGroup)) {
            return ArmErrors.resourceGroupNotFound(resourceGroup);
        }
        if (name == null) {
            return "GET".equals(method) ? list(req, subscriptionId, resourceGroup) : Response.status(405).build();
        }
        if (action != null) {
            return "POST".equals(method) ? action(req, subscriptionId, resourceGroup, name, action)
                : ArmErrors.notFound("Deployment action not found: " + action);
        }
        return switch (method) {
            case "PUT" -> createOrUpdate(req, subscriptionId, resourceGroup, name);
            case "GET" -> get(req, subscriptionId, resourceGroup, name);
            case "HEAD" -> Response.status(deployments.containsKey(key(req, subscriptionId, resourceGroup, name))
                ? 204 : 404).build();
            case "DELETE" -> delete(req, subscriptionId, resourceGroup, name);
            default -> Response.status(405).build();
        };
    }

    private Response createOrUpdate(AzureRequest req, String subscriptionId, String resourceGroup, String name) {
        JsonNode body;
        try {
            body = deploymentRequest(req);
        } catch (TemplateException e) {
            return templateError(e);
        }
        if (body == null) {
            return invalidContent();
        }
        String key = key(req, subscriptionId, resourceGroup, name);
        StoredDeployment current = deployments.get(key);
        if (current != null && current.active()) {
            return deploymentActive(name, current);
        }
        Response location = checkLocation(subscriptionId, resourceGroup, name, body, current);
        if (location != null) {
            return location;
        }
        DeploymentEngine.Request request = new DeploymentEngine.Request(subscriptionId, resourceGroup, name,
            body.get("properties"), req.detached());
        DeploymentEngine.Prepared prepared;
        try {
            prepared = engine.prepare(request);
        } catch (TemplateException e) {
            LOG.infov("Deployment {0} refused: {1}", name, e.getMessage());
            return templateError(e);
        }
        String operationId = operationId();
        String correlationId = UUID.randomUUID().toString();
        ObjectNode accepted = acceptedBody(request, body, prepared, correlationId);
        StoredDeployment stored = new StoredDeployment(accepted, body.path("properties").get("template"),
            operationId, new AtomicBoolean(), JsonNodeFactory.instance.arrayNode());
        // The name is reserved in one atomic step: of two PUTs that both passed the check above, one stores its
        // run and the other finds it active, so a deployment never has two runs.
        AtomicReference<StoredDeployment> previous = new AtomicReference<>();
        StoredDeployment reserved = deployments.compute(key, (ignored, prior) -> {
            previous.set(prior);
            return prior != null && prior.active() ? prior : stored;
        });
        if (reserved != stored) {
            return deploymentActive(name, reserved);
        }
        StoredDeployment existing = previous.get();
        operations.put(operationId, status("Accepted", null));
        runner.submit(() -> run(key, stored, prepared, body, correlationId));
        String statusUrl = RequestUrls.resolveBaseUrl(req, config) + request.id() + "/operationStatuses/" + operationId
            + apiVersionQuery(req);
        return Response.status(existing == null ? 201 : 200).entity(accepted).type(MediaType.APPLICATION_JSON)
            .header("Azure-AsyncOperation", statusUrl)
            .header("Retry-After", RETRY_AFTER_SECONDS)
            .build();
    }

    /** Runs a deployment in the background and records how it ended, unless it was replaced or reset meanwhile. */
    private void run(String key, StoredDeployment stored, DeploymentEngine.Prepared prepared, JsonNode body,
                     String correlationId) {
        DeploymentEngine.Result result;
        try {
            replace(key, stored, withState(stored, "Running"));
            result = engine.run(prepared, stored.canceled()::get);
        } catch (RuntimeException e) {
            LOG.errorv(e, "Deployment {0} failed unexpectedly", prepared.request().name());
            ObjectNode error = JsonNodeFactory.instance.objectNode();
            error.put("code", "InternalServerError");
            error.put("message", "The deployment failed unexpectedly: " + e.getMessage());
            // The record ends too, or a PUT and DELETE of the name would answer DeploymentActive forever.
            StoredDeployment failed = withState(stored, "Failed");
            failed.body().withObjectProperty("properties").set("error", error);
            replace(key, stored, failed);
            operations.put(stored.operationId(), status("Failed", error));
            return;
        }
        ObjectNode finished = deploymentBody(prepared.request(), body, result, correlationId);
        replace(key, stored, new StoredDeployment(finished, stored.template(), stored.operationId(),
            stored.canceled(), operationBodies(prepared.request(), result)));
        operations.put(stored.operationId(), status(result.provisioningState(), result.error()));
    }

    /**
     * {@code GET .../operationStatuses/{id}}. A status that is not final carries {@code Retry-After}: the az CLI polls
     * once straight after the {@code PUT}, and without the header it waits its 30-second default before the next.
     */
    private Response operationStatus(String operationId) {
        ObjectNode answer = operations.get(operationId);
        if (answer == null) {
            return ArmErrors.notFound("Deployment operation status not found: " + operationId);
        }
        Response.ResponseBuilder response = Response.ok(answer).type(MediaType.APPLICATION_JSON);
        if (ACTIVE_STATES.contains(answer.path("status").asText().toLowerCase(Locale.ROOT))) {
            response.header("Retry-After", RETRY_AFTER_SECONDS);
        }
        return response.build();
    }

    /** Swaps the record of one run for its next state, leaving a newer run or a reset alone. */
    private void replace(String key, StoredDeployment run, StoredDeployment next) {
        deployments.computeIfPresent(key, (ignored, current) ->
            current.operationId().equals(run.operationId()) ? next : current);
    }

    private static StoredDeployment withState(StoredDeployment stored, String state) {
        ObjectNode body = stored.body().deepCopy();
        body.withObjectProperty("properties").put("provisioningState", state);
        return new StoredDeployment(body, stored.template(), stored.operationId(), stored.canceled(),
            stored.operations());
    }

    /**
     * {@code Deployments_CalculateTemplateHash}: the body is the template itself, and the answer its minified form and
     * its hash, the same {@link TemplateHash} a deployment of that template reports as {@code templateHash}.
     */
    private Response calculateTemplateHash(AzureRequest req) {
        JsonNode template = body(req);
        if (template == null) {
            return invalidContent();
        }
        ObjectNode answer = JsonNodeFactory.instance.objectNode();
        answer.put("minifiedTemplate", TemplateHash.minified(template));
        answer.put("templateHash", TemplateHash.of(template));
        return json(200, answer);
    }

    /** {@code DeploymentOperations_List} and {@code DeploymentOperations_Get}. */
    private Response operations(AzureRequest req, String path, String subscriptionId, String resourceGroup,
                                String name, String operationId) {
        StoredDeployment stored = deployments.get(key(req, subscriptionId, resourceGroup, name));
        if (stored == null) {
            return deploymentNotFound(name);
        }
        if (operationId == null) {
            List<JsonNode> items = new ArrayList<>();
            stored.operations().forEach(items::add);
            return json(200, page(req, path, items));
        }
        for (JsonNode operation : stored.operations()) {
            if (operation.path("operationId").asText().equalsIgnoreCase(operationId)) {
                return json(200, operation);
            }
        }
        return ArmErrors.error(404, "DeploymentOperationNotFound", "Deployment operation '" + operationId
            + "' of deployment '" + name + "' could not be found.");
    }

    /**
     * One page of {@code items}: from {@code $skiptoken} (an offset), at most {@code $top} of them, with a
     * {@code nextLink} to the rest when some are left. Without {@code $top} every item is on one page.
     */
    private ObjectNode page(AzureRequest req, String path, List<JsonNode> items) {
        int skip = intParam(req, "$skiptoken", 0);
        int top = intParam(req, "$top", Integer.MAX_VALUE);
        ArrayNode value = JsonNodeFactory.instance.arrayNode();
        for (int i = skip; i < items.size() && value.size() < top; i++) {
            value.add(items.get(i));
        }
        ObjectNode answer = JsonNodeFactory.instance.objectNode();
        answer.set("value", value);
        int next = skip + value.size();
        if (next < items.size()) {
            answer.put("nextLink", RequestUrls.resolveBaseUrl(req, config) + "/" + path + "?" + carriedQuery(req)
                + "$top=" + top + "&$skiptoken=" + next);
        }
        return answer;
    }

    /**
     * The query options a {@code nextLink} carries over, each followed by {@code &}: {@code api-version} and
     * {@code $filter}, so a later page reads the same result set as the first.
     */
    private static String carriedQuery(AzureRequest req) {
        StringBuilder query = new StringBuilder();
        if (req.queryParams() == null) {
            return "";
        }
        for (String option : List.of("api-version", "$filter")) {
            String value = req.queryParams().get(option);
            if (value != null) {
                query.append(option).append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")).append('&');
            }
        }
        return query.toString();
    }

    private static int intParam(AzureRequest req, String name, int fallback) {
        String value = req.queryParams() == null ? null : req.queryParams().get(name);
        return value != null && value.matches("\\d+") ? Integer.parseInt(value) : fallback;
    }

    /** The {@code DeploymentOperation} bodies of a finished run, in the order they ran. */
    private static ArrayNode operationBodies(DeploymentEngine.Request request, DeploymentEngine.Result result) {
        ArrayNode bodies = JsonNodeFactory.instance.arrayNode();
        for (DeploymentEngine.Operation operation : result.operations()) {
            ObjectNode body = bodies.addObject();
            body.put("id", request.id() + "/operations/" + operation.operationId());
            body.put("operationId", operation.operationId());
            ObjectNode properties = body.putObject("properties");
            properties.put("provisioningOperation", operation.provisioningOperation());
            properties.put("provisioningState", operation.provisioningState());
            properties.put("timestamp", operation.timestamp().toString());
            properties.put("duration", operation.duration().toString());
            properties.put("trackingId", UUID.randomUUID().toString());
            if (operation.statusCode() != null) {
                properties.put("statusCode", operation.statusCode());
            }
            if (operation.error() != null) {
                ObjectNode message = properties.putObject("statusMessage");
                message.put("status", operation.provisioningState());
                message.set("error", operation.error());
            }
            if (operation.targetId() != null) {
                ObjectNode target = properties.putObject("targetResource");
                target.put("id", operation.targetId());
                target.put("resourceType", operation.targetType());
                target.put("resourceName", operation.targetName());
                target.put("apiVersion", operation.apiVersion());
            }
        }
        return bodies;
    }

    private Response get(AzureRequest req, String subscriptionId, String resourceGroup, String name) {
        StoredDeployment stored = deployments.get(key(req, subscriptionId, resourceGroup, name));
        return stored == null ? deploymentNotFound(name) : json(200, stored.body());
    }

    private Response delete(AzureRequest req, String subscriptionId, String resourceGroup, String name) {
        StoredDeployment existing = deployments.get(key(req, subscriptionId, resourceGroup, name));
        if (existing != null && existing.active()) {
            return deploymentActive(name, existing);
        }
        deployments.remove(key(req, subscriptionId, resourceGroup, name));
        return Response.status(204).build();
    }

    /**
     * {@code Deployments_WhatIf}: the changes the deployment would make, answered {@code 200} at once, one of the two
     * answers the spec declares; Azure answers {@code 202} and lets the client poll a {@code Location}. A template it
     * cannot run is {@code 400} with the template error.
     */
    private Response whatIf(AzureRequest req, String subscriptionId, String resourceGroup, String name) {
        JsonNode body;
        try {
            body = deploymentRequest(req);
        } catch (TemplateException e) {
            return templateError(e);
        }
        if (body == null) {
            return invalidContent();
        }
        Response location = checkLocation(subscriptionId, resourceGroup, name, body, null);
        if (location != null) {
            return location;
        }
        try {
            return json(200, engine.whatIf(new DeploymentEngine.Request(subscriptionId, resourceGroup, name,
                body.get("properties"), req.detached())));
        } catch (TemplateException e) {
            return templateError(e);
        }
    }

    private Response cancel(AzureRequest req, String subscriptionId, String resourceGroup, String name) {
        StoredDeployment existing = deployments.get(key(req, subscriptionId, resourceGroup, name));
        if (existing == null) {
            return deploymentNotFound(name);
        }
        if (!existing.active()) {
            return ArmErrors.error(409, "DeploymentNotActive", "The deployment '" + name + "' cannot be canceled: "
                + "its provisioning state is '" + existing.state() + "'. Only an Accepted or Running deployment can "
                + "be canceled.");
        }
        existing.canceled().set(true);
        return Response.status(204).build();
    }

    private Response list(AzureRequest req, String subscriptionId, String resourceGroup) {
        String prefix = key(req, subscriptionId, resourceGroup, "");
        String state = null;
        String filter = req.queryParams() == null ? null : req.queryParams().get("$filter");
        if (filter != null) {
            Matcher matcher = STATE_FILTER.matcher(filter);
            state = matcher.find() ? matcher.group(1) : null;
        }
        List<Map.Entry<String, StoredDeployment>> entries = new ArrayList<>(deployments.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        List<JsonNode> matching = new ArrayList<>();
        for (Map.Entry<String, StoredDeployment> entry : entries) {
            ObjectNode body = entry.getValue().body();
            if (entry.getKey().startsWith(prefix) && (state == null
                    || body.path("properties").path("provisioningState").asText().equalsIgnoreCase(state))) {
                matching.add(body);
            }
        }
        String path = req.resourcePath();
        int query = path.indexOf('?');
        return json(200, page(req, query < 0 ? path : path.substring(0, query), matching));
    }

    private Response action(AzureRequest req, String subscriptionId, String resourceGroup, String name,
                            String action) {
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "validate" -> validate(req, subscriptionId, resourceGroup, name);
            case "cancel" -> cancel(req, subscriptionId, resourceGroup, name);
            case "whatif" -> whatIf(req, subscriptionId, resourceGroup, name);
            case "exporttemplate" -> {
                StoredDeployment stored = deployments.get(key(req, subscriptionId, resourceGroup, name));
                if (stored == null) {
                    yield deploymentNotFound(name);
                }
                ObjectNode answer = JsonNodeFactory.instance.objectNode();
                answer.set("template", stored.template() == null ? JsonNodeFactory.instance.objectNode()
                    : stored.template());
                yield json(200, answer);
            }
            default -> ArmErrors.notFound("Deployment action not supported by floci-az: " + action);
        };
    }

    private Response validate(AzureRequest req, String subscriptionId, String resourceGroup, String name) {
        JsonNode body;
        try {
            body = deploymentRequest(req);
        } catch (TemplateException e) {
            return templateError(e);
        }
        if (body == null) {
            return invalidContent();
        }
        Response location = checkLocation(subscriptionId, resourceGroup, name, body, null);
        if (location != null) {
            return location;
        }
        DeploymentEngine.Request request = new DeploymentEngine.Request(subscriptionId, resourceGroup, name,
            body.get("properties"), req);
        try {
            DeploymentEngine.Prepared prepared = engine.prepare(request);
            List<String> resources = prepared.resourceIds();
            ObjectNode answer = header(request, body);
            ObjectNode properties = answer.putObject("properties");
            properties.put("templateHash", prepared.templateHash());
            properties.set("parameters", prepared.echoedParameters());
            properties.put("mode", body.path("properties").path("mode").asText());
            properties.put("provisioningState", "Succeeded");
            properties.put("timestamp", Instant.now().toString());
            properties.put("duration", "PT0S");
            properties.put("correlationId", UUID.randomUUID().toString());
            ArrayNode validated = properties.putArray("validatedResources");
            resources.forEach(id -> validated.addObject().put("id", id));
            return json(200, answer);
        } catch (TemplateException e) {
            return templateError(e);
        }
    }

    /** The {@code DeploymentExtended} body of a finished deployment. */
    private static ObjectNode deploymentBody(DeploymentEngine.Request request, JsonNode body,
                                             DeploymentEngine.Result result, String correlationId) {
        ObjectNode answer = header(request, body);
        ObjectNode properties = answer.putObject("properties");
        properties.put("templateHash", result.templateHash());
        properties.set("parameters", result.parameters());
        properties.put("mode", body.path("properties").path("mode").asText());
        echoLinks(body, properties);
        properties.put("provisioningState", result.provisioningState());
        properties.put("timestamp", result.finished().toString());
        properties.put("duration", Duration.between(result.started(), result.finished()).toString());
        properties.put("correlationId", correlationId);
        properties.set("providers", result.providers());
        properties.set("dependencies", result.dependencies());
        properties.set("outputs", result.outputs());
        ArrayNode outputResources = properties.putArray("outputResources");
        result.outputResources().forEach(id -> outputResources.addObject().put("id", id));
        if (result.error() != null) {
            properties.set("error", result.error());
        }
        return answer;
    }

    /**
     * A subscription- or tenant-scope deployment's {@code location}, where its data is stored
     * ({@code deploy-to-subscription.md} and {@code deploy-to-tenant.md},
     * "Deployment location and name"): required, and fixed for a name once used. Null when it is acceptable; a
     * resource-group deployment takes its group's location and needs none.
     */
    private static Response checkLocation(String subscriptionId, String resourceGroup, String name, JsonNode body,
                                          StoredDeployment existing) {
        if (resourceGroup != null) {
            return null;
        }
        String location = body.path("location").asText("");
        if (location.isBlank()) {
            return ArmErrors.error(400, "LocationRequired", "The location property is required for the "
                + (subscriptionId == null ? "tenant" : "subscription") + "-scope deployment '" + name + "'.");
        }
        String previous = existing == null ? null : existing.body().path("location").asText(null);
        if (previous != null && !squash(previous).equals(squash(location))) {
            return ArmErrors.error(400, "InvalidDeploymentLocation", "Invalid deployment location '" + location
                + "'. The deployment '" + name + "' already exists in location '" + previous + "'.");
        }
        return null;
    }

    private static String squash(String location) {
        return location.toLowerCase(Locale.ROOT).replace(" ", "");
    }

    /** The {@code 201} body of an accepted deployment, as Azure records it: no parameters or outputs yet. */
    private static ObjectNode acceptedBody(DeploymentEngine.Request request, JsonNode body,
                                           DeploymentEngine.Prepared prepared, String correlationId) {
        ObjectNode answer = header(request, body);
        ObjectNode properties = answer.putObject("properties");
        properties.put("templateHash", prepared.templateHash());
        properties.put("mode", body.path("properties").path("mode").asText());
        echoLinks(body, properties);
        properties.put("provisioningState", "Accepted");
        properties.put("timestamp", Instant.now().toString());
        properties.put("duration", "PT0S");
        properties.put("correlationId", correlationId);
        properties.putArray("providers");
        properties.putArray("dependencies");
        return answer;
    }

    /** The {@code templateLink} and {@code parametersLink} a deployment was sent with, echoed as Azure does. */
    private static void echoLinks(JsonNode body, ObjectNode properties) {
        for (String link : new String[] {"templateLink", "parametersLink"}) {
            if (body.path("properties").path(link).isObject()) {
                properties.set(link, body.path("properties").get(link));
            }
        }
    }

    /** An {@code operationStatuses} answer: {@code {"status": ...}}, with the error when the run failed. */
    private static ObjectNode status(String state, ObjectNode error) {
        ObjectNode status = JsonNodeFactory.instance.objectNode();
        status.put("status", state);
        if (error != null) {
            status.set("error", error);
        }
        return status;
    }

    /** An operation id in Azure's form, a 20-digit decimal number. */
    private static String operationId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return String.format("%010d%010d", random.nextLong(10_000_000_000L), random.nextLong(10_000_000_000L));
    }

    private static String apiVersionQuery(AzureRequest req) {
        String apiVersion = req.queryParams() == null ? null : req.queryParams().get("api-version");
        return apiVersion == null ? "" : "?api-version=" + apiVersion;
    }

    private static Response deploymentActive(String name, StoredDeployment existing) {
        return ArmErrors.error(409, "DeploymentActive", "Unable to edit or replace deployment '" + name
            + "': the previous deployment is still active (current provisioning state is: '" + existing.state()
            + "').");
    }

    private static ObjectNode header(DeploymentEngine.Request request, JsonNode body) {
        ObjectNode answer = JsonNodeFactory.instance.objectNode();
        answer.put("id", request.id());
        answer.put("name", request.name());
        answer.put("type", DeploymentEngine.DEPLOYMENTS_TYPE);
        if (!request.inResourceGroup() && body.path("location").isTextual()) {
            answer.put("location", body.get("location").asText());
        }
        if (body.path("tags").isObject()) {
            answer.set("tags", body.get("tags"));
        }
        return answer;
    }

    /**
     * A deployment request body with a linked template and parameter file inlined ({@link LinkedTemplates}); null when
     * the body is not a JSON object. A link it cannot use throws {@link TemplateException}.
     */
    private JsonNode deploymentRequest(AzureRequest req) {
        JsonNode body = body(req);
        if (body == null || !body.has("properties")) {
            return body;
        }
        ObjectNode inlined = body.deepCopy();
        inlined.set("properties", links.inline(body.get("properties")));
        return inlined;
    }

    /** The request body, parsed leniently; null when it is not a JSON object. */
    private static JsonNode body(AzureRequest req) {
        try {
            JsonNode body = req.bodyStream() == null ? null : LENIENT.readTree(req.bodyStream());
            return body != null && body.isObject() ? body : null;
        } catch (IOException e) {
            LOG.debugv("Deployment body is not JSON: {0}", e.getMessage());
            return null;
        }
    }

    /**
     * A deployment's key: {@code {subscription}/{group}/{name}}, a subscription-scope deployment having an empty group
     * part. A tenant-scope deployment belongs to the caller's tenant, so its key is {@code tenant:{tenantId}//{name}}
     * and two tenants never read or replace each other's.
     */
    private String key(AzureRequest req, String subscriptionId, String resourceGroup, String name) {
        return key(subscriptionId == null ? "tenant:" + engine.callerTenant(req) : subscriptionId, resourceGroup,
            name);
    }

    private static String key(String owner, String resourceGroup, String name) {
        String group = resourceGroup == null ? "" : resourceGroup;
        return (owner + "/" + group + "/" + name).toLowerCase(Locale.ROOT);
    }

    private static Response templateError(TemplateException e) {
        return ArmErrors.error(400, e.code(), e.getMessage());
    }

    private static Response invalidContent() {
        return ArmErrors.error(400, "InvalidRequestContent", "The request content was invalid and could not be "
            + "deserialized.");
    }

    private static Response deploymentNotFound(String name) {
        return ArmErrors.error(404, "DeploymentNotFound", "Deployment '" + name + "' could not be found.");
    }

    private static Response json(int status, JsonNode body) {
        return Response.status(status).entity(body).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * A group's deployment history lives in the group, so it goes with it: a group recreated under the same name
     * starts with none. A run still going is canceled; it finds its record gone and records nothing.
     */
    @Override
    public void resourceGroupDeleted(String subscriptionId, String resourceGroup) {
        String prefix = key(subscriptionId, resourceGroup, "");
        deployments.entrySet().removeIf(entry -> {
            if (!entry.getKey().startsWith(prefix)) {
                return false;
            }
            entry.getValue().canceled().set(true);
            return true;
        });
    }

    @Override
    public void clear() {
        deployments.values().forEach(deployment -> deployment.canceled().set(true));
        deployments.clear();
        operations.clear();
    }

    @PreDestroy
    void stop() {
        runner.shutdownNow();
    }
}
