package io.floci.az.services.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.AzureRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class NetworkService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Map<String, Object>> resources;

    @Inject
    public NetworkService(NetworkStore store) {
        this.resources = store.resources;
    }

    public Response handleArm(AzureRequest request, String path, String method, String sub) {
        String rg = extractRg(path);
        String tail = extractAfter(path, "/providers/Microsoft.Network/");

        if (tail.matches("[^/]+")) {
            return Response.ok(Map.of("value", listResources(sub, rg, "Microsoft.Network/" + tail))).build();
        }
        if (tail.matches("virtualNetworks/[^/]+/subnets")) {
            String vnetName = tail.split("/")[1];
            return Response.ok(Map.of("value", listSubnets(sub, rg, vnetName))).build();
        }

        String resourceType = resourceType(tail);
        String name = resourceName(tail);
        String key = key(sub, rg, tail);

        return switch (method) {
            case "PUT" -> createOrUpdateResource(request, sub, rg, tail, resourceType, name, key);
            case "GET" -> {
                Map<String, Object> resource = resources.get(key);
                yield resource == null ? notFound(tail) : Response.ok(stripInternal(resource)).build();
            }
            case "DELETE" -> {
                resources.remove(key);
                deleteChildren(sub, rg, tail);
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    public List<Map<String, Object>> listResources(String sub, String rg) {
        return resources.values().stream()
                .filter(r -> sub.equals(r.get("_sub")) && rg.equals(r.get("_rg")))
                .map(NetworkService::stripInternal)
                .toList();
    }

    public List<Map<String, Object>> listResources(String sub, String rg, String type) {
        return resources.values().stream()
                .filter(r -> sub.equals(r.get("_sub")) && rg.equals(r.get("_rg")) && type.equals(r.get("type")))
                .map(NetworkService::stripInternal)
                .toList();
    }

    public List<Map<String, Object>> listSubnets(String sub, String rg, String vnetName) {
        String prefix = key(sub, rg, "virtualNetworks/" + vnetName + "/subnets/");
        return resources.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .map(NetworkService::stripInternal)
                .toList();
    }

    public void clearAll() {
        resources.clear();
    }

    private Response createOrUpdateResource(AzureRequest request, String sub, String rg, String tail,
                                            String resourceType, String name, String key) {
        Map<String, Object> body = parseBody(request);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
        synthesizeProperties(resourceType, properties);
        properties.put("provisioningState", "Succeeded");

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("_sub", sub);
        resource.put("_rg", rg);
        resource.put("id", "/subscriptions/" + sub + "/resourceGroups/" + rg
                + "/providers/Microsoft.Network/" + tail);
        resource.put("name", name);
        resource.put("type", resourceType);
        String location = bodyString(body, "location", null);
        if (location != null) {
            resource.put("location", location);
        }
        if (body.get("tags") instanceof Map<?, ?> tags && !tags.isEmpty()) {
            resource.put("tags", tags);
        }
        resource.put("properties", properties);
        resources.put(key, resource);
        return Response.ok(stripInternal(resource)).build();
    }

    private void deleteChildren(String sub, String rg, String tail) {
        String[] parts = tail.split("/");
        if (parts.length == 2 && "virtualNetworks".equals(parts[0])) {
            String prefix = key(sub, rg, "virtualNetworks/" + parts[1] + "/subnets/");
            resources.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }

    @SuppressWarnings("unchecked")
    private static void synthesizeProperties(String resourceType, Map<String, Object> properties) {
        switch (resourceType) {
            case "Microsoft.Network/networkInterfaces" -> {
                Object cfgs = properties.get("ipConfigurations");
                List<Object> configs = cfgs instanceof List<?> l && !l.isEmpty()
                        ? new ArrayList<>((List<Object>) l)
                        : new ArrayList<>(List.of(new LinkedHashMap<String, Object>(Map.of("name", "ipconfig1"))));
                boolean[] first = {true};
                configs.replaceAll(c -> {
                    Map<String, Object> cfg = new LinkedHashMap<>(cast(c));
                    Map<String, Object> cp = new LinkedHashMap<>(cast(cfg.get("properties")));
                    cp.putIfAbsent("privateIPAddress", "10.0.0.4");
                    cp.putIfAbsent("privateIPAllocationMethod", "Dynamic");
                    cp.put("primary", first[0]);
                    cp.put("provisioningState", "Succeeded");
                    cfg.put("properties", cp);
                    cfg.putIfAbsent("name", "ipconfig1");
                    first[0] = false;
                    return cfg;
                });
                properties.put("ipConfigurations", configs);
            }
            case "Microsoft.Network/publicIPAddresses" -> {
                properties.putIfAbsent("ipAddress", "20.0.0.4");
                properties.putIfAbsent("publicIPAllocationMethod", "Dynamic");
            }
            default -> { }
        }
    }

    private static String resourceType(String tail) {
        String[] parts = tail.split("/");
        if (parts.length >= 4 && "subnets".equals(parts[2])) {
            return "Microsoft.Network/virtualNetworks/subnets";
        }
        return "Microsoft.Network/" + parts[0];
    }

    private static String resourceName(String tail) {
        String[] parts = tail.split("[/?]");
        return parts.length > 0 ? parts[parts.length - 1] : tail;
    }

    private static String key(String sub, String rg, String tail) {
        int q = tail.indexOf('?');
        String clean = q >= 0 ? tail.substring(0, q) : tail;
        return sub + "/" + rg + "/net/" + clean;
    }

    private static String extractRg(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("resourcegroups".equalsIgnoreCase(parts[i])) {
                return parts[i + 1];
            }
        }
        return "unknown";
    }

    private static String extractAfter(String path, String marker) {
        int idx = path.lastIndexOf(marker);
        if (idx < 0) {
            return "unknown";
        }
        String rest = path.substring(idx + marker.length());
        int q = rest.indexOf('?');
        return q >= 0 ? rest.substring(0, q) : rest;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(AzureRequest request) {
        try {
            if (request.bodyStream() == null || request.bodyStream().available() == 0) {
                return Map.of();
            }
            return MAPPER.readValue(request.bodyStream(), Map.class);
        } catch (IOException e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String bodyString(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return v instanceof String s ? s : defaultValue;
    }

    private static Map<String, Object> stripInternal(Map<String, Object> resource) {
        Map<String, Object> copy = new LinkedHashMap<>(resource);
        copy.remove("_sub");
        copy.remove("_rg");
        return copy;
    }

    private Response notFound(String path) {
        return Response.status(404).entity(Map.of("error", Map.of(
                "code", "ResourceNotFound",
                "message", "Resource not found: " + path
        ))).build();
    }
}
