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
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.directory.ResourceGroups;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code Microsoft.Resources/deployments} at resource-group scope: ARM template deployments, run by the
 * {@link DeploymentEngine}.
 *
 * <p>Wire contract, Resources spec 2025-04-01 (the version the az CLI sends; azurerm sends 2020-06-01, whose
 * operations and shapes are the same):
 *
 * <ul>
 *   <li>{@code PUT} ({@code Deployments_CreateOrUpdate}) runs the deployment to the end and answers {@code 201},
 *       or {@code 200} for an existing name, with the final {@code provisioningState}: azurerm and the az CLI both
 *       read a 201 without async headers as complete when its body says so. Azure answers {@code Accepted} and
 *       polls; Phase 1 is synchronous. A template it cannot run is {@code 400} with the template error.</li>
 *   <li>{@code GET}, {@code HEAD}, list with {@code $filter=provisioningState eq '...'} and {@code $top}</li>
 *   <li>{@code DELETE} removes the deployment record only, never its resources, as Azure does; it answers
 *       {@code 204} at once, where Azure answers {@code 202} and polls</li>
 *   <li>{@code POST validate} ({@code 200}, or {@code 400} with the template error) and
 *       {@code POST exportTemplate} ({@code {"template": ...}})</li>
 * </ul>
 *
 * <p>The body is read as JSONC, the way the az CLI sends it (see {@link #LENIENT}).
 *
 * <p>{@code whatIf}, {@code cancel}, {@code operations} and {@code operationStatuses}, and deployments at any other
 * scope, answer {@code 404} in Phase 1. Deployments live in memory, as the rest of ARM state does.
 */
@ApplicationScoped
public class DeploymentsHandler implements AzureServiceHandler, Resettable {

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

    /** {@code subscriptions/{sub}/resourcegroups/{rg}/providers/Microsoft.Resources/deployments[/{name}[/{action}]]}. */
    private static final Pattern PATH = Pattern.compile(
        "subscriptions/([^/]+)/resourcegroups/([^/]+)/providers/microsoft\\.resources/deployments(?:/([^/?]+)(?:/([^/?]+))?)?/?",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern STATE_FILTER =
        Pattern.compile("provisioningState\\s+eq\\s+'([^']*)'", Pattern.CASE_INSENSITIVE);

    private final EmulatorConfig config;
    private final DeploymentEngine engine;
    private final ResourceGroups groups;

    /** Every deployment, keyed by {@link #key}. */
    private final Map<String, StoredDeployment> deployments = new ConcurrentHashMap<>();

    /**
     * One deployment as {@code GET} answers it, and the template {@code exportTemplate} returns.
     *
     * @param body     the {@code DeploymentExtended} body
     * @param template the template as sent
     */
    record StoredDeployment(ObjectNode body, JsonNode template) {
    }

    @Inject
    public DeploymentsHandler(EmulatorConfig config, DeploymentEngine engine, ResourceGroups groups) {
        this.config = config;
        this.engine = engine;
        this.groups = groups;
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

    /** A path under {@code /providers/Microsoft.Resources/deployments}, at any scope. */
    static boolean isDeploymentPath(String path) {
        return ArmPaths.indexOfIgnoreCase(path, MARKER) >= 0;
    }

    @Override
    public Response handle(AzureRequest req) {
        String path = req.resourcePath();
        int query = path.indexOf('?');
        Matcher matcher = PATH.matcher(query < 0 ? path : path.substring(0, query));
        if (!matcher.matches()) {
            return ArmErrors.notFound("floci-az serves deployments at resource-group scope only: " + path);
        }
        String subscriptionId = matcher.group(1);
        String resourceGroup = matcher.group(2);
        String name = matcher.group(3);
        String action = matcher.group(4);
        String method = req.method().toUpperCase(Locale.ROOT);
        if (!groups.exists(subscriptionId, resourceGroup)) {
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
            case "GET" -> get(subscriptionId, resourceGroup, name);
            case "HEAD" -> Response.status(deployments.containsKey(key(subscriptionId, resourceGroup, name))
                ? 204 : 404).build();
            case "DELETE" -> delete(subscriptionId, resourceGroup, name);
            default -> Response.status(405).build();
        };
    }

    private Response createOrUpdate(AzureRequest req, String subscriptionId, String resourceGroup, String name) {
        JsonNode body = body(req);
        if (body == null) {
            return invalidContent();
        }
        DeploymentEngine.Request request = new DeploymentEngine.Request(subscriptionId, resourceGroup, name,
            body.get("properties"), req);
        DeploymentEngine.Result result;
        try {
            result = engine.deploy(request);
        } catch (TemplateException e) {
            LOG.infov("Deployment {0} refused: {1}", name, e.getMessage());
            return templateError(e);
        }
        ObjectNode answer = deploymentBody(request, body, result);
        boolean created = deployments.put(key(subscriptionId, resourceGroup, name),
            new StoredDeployment(answer, body.path("properties").get("template"))) == null;
        return json(created ? 201 : 200, answer);
    }

    private Response get(String subscriptionId, String resourceGroup, String name) {
        StoredDeployment stored = deployments.get(key(subscriptionId, resourceGroup, name));
        return stored == null ? deploymentNotFound(name) : json(200, stored.body());
    }

    private Response delete(String subscriptionId, String resourceGroup, String name) {
        deployments.remove(key(subscriptionId, resourceGroup, name));
        return Response.status(204).build();
    }

    private Response list(AzureRequest req, String subscriptionId, String resourceGroup) {
        String prefix = key(subscriptionId, resourceGroup, "");
        String state = null;
        String filter = req.queryParams() == null ? null : req.queryParams().get("$filter");
        if (filter != null) {
            Matcher matcher = STATE_FILTER.matcher(filter);
            state = matcher.find() ? matcher.group(1) : null;
        }
        int top = Integer.MAX_VALUE;
        String topParam = req.queryParams() == null ? null : req.queryParams().get("$top");
        if (topParam != null && topParam.matches("\\d+")) {
            top = Integer.parseInt(topParam);
        }
        ArrayNode value = JsonNodeFactory.instance.arrayNode();
        List<Map.Entry<String, StoredDeployment>> entries = new ArrayList<>(deployments.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, StoredDeployment> entry : entries) {
            if (value.size() >= top || !entry.getKey().startsWith(prefix)) {
                continue;
            }
            ObjectNode body = entry.getValue().body();
            if (state == null || body.path("properties").path("provisioningState").asText().equalsIgnoreCase(state)) {
                value.add(body);
            }
        }
        ObjectNode answer = JsonNodeFactory.instance.objectNode();
        answer.set("value", value);
        return json(200, answer);
    }

    private Response action(AzureRequest req, String subscriptionId, String resourceGroup, String name,
                            String action) {
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "validate" -> validate(req, subscriptionId, resourceGroup, name);
            case "exporttemplate" -> {
                StoredDeployment stored = deployments.get(key(subscriptionId, resourceGroup, name));
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
        JsonNode body = body(req);
        if (body == null) {
            return invalidContent();
        }
        DeploymentEngine.Request request = new DeploymentEngine.Request(subscriptionId, resourceGroup, name,
            body.get("properties"), req);
        try {
            List<String> resources = engine.validate(request);
            ObjectNode answer = header(request, body);
            ObjectNode properties = answer.putObject("properties");
            properties.put("templateHash", DeploymentEngine.templateHash(
                DeploymentTemplate.parse(body.path("properties").get("template"))));
            properties.set("parameters", engine.echoedParameters(request));
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
                                             DeploymentEngine.Result result) {
        ObjectNode answer = header(request, body);
        ObjectNode properties = answer.putObject("properties");
        properties.put("templateHash", result.templateHash());
        properties.set("parameters", result.parameters());
        properties.put("mode", body.path("properties").path("mode").asText());
        properties.put("provisioningState", result.provisioningState());
        properties.put("timestamp", result.finished().toString());
        properties.put("duration", Duration.between(result.started(), result.finished()).toString());
        properties.put("correlationId", UUID.randomUUID().toString());
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

    private static ObjectNode header(DeploymentEngine.Request request, JsonNode body) {
        ObjectNode answer = JsonNodeFactory.instance.objectNode();
        answer.put("id", request.id());
        answer.put("name", request.name());
        answer.put("type", DeploymentEngine.DEPLOYMENTS_TYPE);
        if (body.path("tags").isObject()) {
            answer.set("tags", body.get("tags"));
        }
        return answer;
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

    private static String key(String subscriptionId, String resourceGroup, String name) {
        return (subscriptionId + "/" + resourceGroup + "/" + name).toLowerCase(Locale.ROOT);
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

    @Override
    public void clear() {
        deployments.clear();
    }
}
