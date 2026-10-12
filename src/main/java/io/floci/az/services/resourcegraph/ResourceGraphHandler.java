package io.floci.az.services.resourcegraph;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.services.resourcegraph.kql.Kql;
import io.floci.az.services.resourcegraph.kql.KqlEngine;
import io.floci.az.services.resourcegraph.kql.KqlException;
import io.floci.az.services.resourcegraph.kql.KqlValues;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Azure Resource Graph: {@code POST /providers/Microsoft.ResourceGraph/resources} runs a KQL query over
 * the emulator's resources, resource containers and policy resources, and answers in the
 * {@code objectArray} or {@code table} result format with the service's paging contract
 * ({@code $top}, {@code $skip}, {@code $skipToken}, {@code totalRecords}, {@code resultTruncated}).
 */
@ApplicationScoped
public class ResourceGraphHandler implements AzureServiceHandler {

    static final String SERVICE_TYPE = "resourcegraph";
    static final int MAX_PAGE = 1000;

    private static final Logger LOG = Logger.getLogger(ResourceGraphHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EmulatorConfig config;
    private final GraphTables tables;

    @Inject
    public ResourceGraphHandler(EmulatorConfig config, GraphTables tables) {
        this.config = config;
        this.tables = tables;
    }

    @Override
    public String getServiceType() {
        return SERVICE_TYPE;
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().resourceGraph().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder().provider("Microsoft.ResourceGraph").build();
    }

    @Override
    public boolean canHandle(AzureRequest request) {
        return SERVICE_TYPE.equals(request.serviceType());
    }

    @Override
    public Response handle(AzureRequest req) {
        String path = req.resourcePath();
        int query = path.indexOf('?');
        String resource = (query >= 0 ? path.substring(0, query) : path).toLowerCase(Locale.ROOT);
        if (!resource.endsWith("providers/microsoft.resourcegraph/resources")) {
            return ArmErrors.error(404, "InvalidResourceType",
                "The resource type '" + resource + "' could not be found in the namespace 'Microsoft.ResourceGraph'.");
        }
        if (!"POST".equalsIgnoreCase(req.method())) {
            return ArmErrors.error(405, "MethodNotAllowed", "The requested resource does not support http method '" + req.method() + "'.");
        }
        Map<String, Object> body;
        try {
            body = ArmJson.parseBodyStrict(req);
        } catch (ArmJson.InvalidBodyException e) {
            return badRequest("InvalidRequestContent", "The request content was invalid and could not be deserialized.");
        }
        Object queryText = body.get("query");
        if (!(queryText instanceof String text) || text.isBlank()) {
            return badRequest("NullOrEmptyQuery", "The query cannot be null or empty.");
        }
        Map<String, Object> options = ArmJson.cast(body.get("options"));
        List<String> subscriptions = strings(body.get("subscriptions"));
        String resultFormat = ArmJson.string(options, "resultFormat", "objectArray");

        List<Map<String, Object>> rows;
        try {
            Kql.Query parsed = Kql.parse(text);
            rows = KqlEngine.run(parsed, scoped(subscriptions));
        } catch (KqlException e) {
            LOG.debugf("Resource Graph query rejected: %s", e.getMessage());
            return queryError(e);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Resource Graph query failed");
            return queryError(new KqlException("SemanticError", e.getMessage() == null ? "The query failed." : e.getMessage()));
        }

        int top = (int) Math.min(MAX_PAGE, Math.max(1, longOption(options, "$top", MAX_PAGE)));
        int skip = (int) Math.max(0, longOption(options, "$skip", 0));
        Object skipToken = options.get("$skipToken");
        if (skipToken instanceof String token && !token.isBlank()) {
            Integer offset = decodeSkipToken(token);
            if (offset == null) {
                return badRequest("InvalidSkipToken", "The provided skip token is invalid.");
            }
            skip = offset;
        }
        int total = rows.size();
        int from = Math.min(skip, total);
        int to = Math.min(from + top, total);
        List<Map<String, Object>> page = rows.subList(from, to);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalRecords", total);
        response.put("count", page.size());
        response.put("data", "table".equalsIgnoreCase(resultFormat) ? table(rows, page) : objectArray(page));
        response.put("facets", List.of());
        response.put("resultTruncated", to < total ? "true" : "false");
        if (to < total) {
            response.put("$skipToken", encodeSkipToken(to));
        }
        return Response.ok(response).build();
    }

    /** The tables narrowed to the subscriptions the request names, or every subscription when it names none. */
    private KqlEngine.Tables scoped(List<String> subscriptions) {
        if (subscriptions.isEmpty()) {
            return tables;
        }
        List<String> wanted = subscriptions.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        return table -> {
            List<Map<String, Object>> rows = tables.rows(table);
            if (rows == null) {
                return null;
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object subscription = row.get("subscriptionId");
                if (subscription == null || wanted.contains(String.valueOf(subscription).toLowerCase(Locale.ROOT))) {
                    out.add(row);
                }
            }
            return out;
        };
    }

    private static List<Map<String, Object>> objectArray(List<Map<String, Object>> page) {
        List<Map<String, Object>> out = new ArrayList<>(page.size());
        for (Map<String, Object> row : page) {
            Map<String, Object> json = new LinkedHashMap<>();
            row.forEach((key, value) -> json.put(key, KqlValues.toJson(value)));
            out.add(json);
        }
        return out;
    }

    private static Map<String, Object> table(List<Map<String, Object>> all, List<Map<String, Object>> page) {
        List<String> names = KqlEngine.columns(all);
        List<Map<String, Object>> columns = new ArrayList<>();
        for (String name : names) {
            String type = "string";
            for (Map<String, Object> row : all) {
                Object value = row.get(name);
                if (value != null) {
                    type = KqlValues.typeName(value);
                    break;
                }
            }
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("name", name);
            column.put("type", type);
            columns.add(column);
        }
        List<List<Object>> rows = new ArrayList<>(page.size());
        for (Map<String, Object> row : page) {
            List<Object> values = new ArrayList<>(names.size());
            for (String name : names) {
                values.add(KqlValues.toJson(row.get(name)));
            }
            rows.add(values);
        }
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("columns", columns);
        table.put("rows", rows);
        return table;
    }

    private static Response queryError(KqlException e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "BadRequest");
        error.put("message", "Please provide below info when asking for support: timestamp = " + Instant.now()
            + ", correlationId = " + UUID.randomUUID() + ".");
        List<Map<String, Object>> details = new ArrayList<>();
        details.add(detail("InvalidQuery", "Query is invalid."));
        details.add(detail(e.code(), e.getMessage()));
        error.put("details", details);
        return Response.status(400).entity(Map.of("error", error)).build();
    }

    private static Response badRequest(String code, String message) {
        return ArmErrors.error(400, code, message);
    }

    private static Map<String, Object> detail(String code, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("message", message);
        return detail;
    }

    private static long longOption(Map<String, Object> options, String key, long fallback) {
        Object value = options.get(key);
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && s.matches("-?\\d+")) {
            return Long.parseLong(s);
        }
        return fallback;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof String s && !s.isBlank()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    static String encodeSkipToken(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("skip:" + offset).getBytes(StandardCharsets.UTF_8));
    }

    static Integer decodeSkipToken(String token) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith("skip:")) {
                return null;
            }
            return Integer.parseInt(decoded.substring(5));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
