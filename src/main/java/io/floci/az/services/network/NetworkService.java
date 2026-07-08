package io.floci.az.services.network;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmResources;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class NetworkService {


    private final Map<String, Map<String, Object>> resources;

    @Inject
    public NetworkService(NetworkStore store) {
        this.resources = store.resources;
    }

    public Response handleArm(AzureRequest request, String path, String method, String sub) {
        String rg = extractRg(path);
        String tail = extractAfter(path, "/providers/Microsoft.Network/");

        if (tail.startsWith("dnsZones") || tail.startsWith("dnszones")) {
            return handleDnsZones(request, path, method, sub, rg, tail);
        }

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
        return ArmPaths.resourceGroup(path, "unknown");
    }

    private static String extractAfter(String path, String marker) {
        return ArmPaths.afterSegment(path, marker, "unknown");
    }

    private Map<String, Object> parseBody(AzureRequest request) {
        return ArmJson.parseBodyLenient(request);
    }

    private static Map<String, Object> cast(Object o) {
        return ArmJson.cast(o);
    }

    private static String bodyString(Map<String, Object> map, String key, String defaultValue) {
        return ArmJson.string(map, key, defaultValue);
    }

    private static Map<String, Object> stripInternal(Map<String, Object> resource) {
        return ArmResources.stripInternal(resource);
    }

    private Response notFound(String path) {
        return ArmErrors.notFound("Resource not found: " + path);
    }

    // ── Azure DNS Support ───────────────────────────────────────────────────

    private Response handleDnsZones(AzureRequest request, String path, String method, String sub, String rg, String tail) {
        String[] parts = tail.split("/");

        // 1. List zones
        if (parts.length == 1) {
            return Response.ok(Map.of("value", listDnsZones(sub, rg))).build();
        }

        // 2. Zone CRUD
        if (parts.length == 2) {
            String zoneName = parts[1];
            return switch (method) {
                case "PUT"    -> createOrUpdateDnsZone(request, sub, rg, zoneName);
                case "GET"    -> getDnsZone(sub, rg, zoneName);
                case "DELETE" -> deleteDnsZone(sub, rg, zoneName);
                default       -> Response.status(405).build();
            };
        }

        // 3. List all record sets
        if (parts.length == 3 && ("recordsets".equalsIgnoreCase(parts[2]) || "all".equalsIgnoreCase(parts[2]))) {
            String zoneName = parts[1];
            return Response.ok(Map.of("value", listAllRecordSets(sub, rg, zoneName))).build();
        }

        // 4. List record sets by type
        if (parts.length == 3 && isRecordType(parts[2])) {
            String zoneName = parts[1];
            String recordType = parts[2];
            return Response.ok(Map.of("value", listRecordSetsByType(sub, rg, zoneName, recordType))).build();
        }

        // 5. Record Set CRUD
        if (parts.length >= 4 && isRecordType(parts[2])) {
            String zoneName = parts[1];
            String recordType = parts[2];
            StringBuilder nameBuilder = new StringBuilder(parts[3]);
            for (int i = 4; i < parts.length; i++) {
                nameBuilder.append("/").append(parts[i]);
            }
            String relativeRecordSetName = nameBuilder.toString();

            return switch (method) {
                case "PUT"    -> createOrUpdateRecordSet(request, sub, rg, zoneName, recordType, relativeRecordSetName);
                case "GET"    -> getRecordSet(sub, rg, zoneName, recordType, relativeRecordSetName);
                case "DELETE" -> deleteRecordSet(sub, rg, zoneName, recordType, relativeRecordSetName);
                default       -> Response.status(405).build();
            };
        }

        return notFound(tail);
    }

    private static boolean isRecordType(String part) {
        String u = part.toUpperCase();
        return "A".equals(u) || "AAAA".equals(u) || "CNAME".equals(u) ||
               "TXT".equals(u) || "MX".equals(u) || "NS".equals(u) ||
               "SOA".equals(u) || "SRV".equals(u) || "CAA".equals(u);
    }

    private List<Map<String, Object>> listDnsZones(String sub, String rg) {
        return resources.values().stream()
                .filter(r -> sub.equals(r.get("_sub"))
                        && ("unknown".equals(rg) || rg.equals(r.get("_rg")))
                        && "Microsoft.Network/dnsZones".equals(r.get("type")))
                .map(NetworkService::stripInternal)
                .toList();
    }

    private Response getDnsZone(String sub, String rg, String zoneName) {
        String key = sub + "/" + rg + "/net/dnsZones/" + zoneName;
        Map<String, Object> zone = resources.get(key);
        if (zone == null) {
            return notFound("dnsZones/" + zoneName);
        }
        return Response.ok(stripInternal(zone)).build();
    }

    private Response createOrUpdateDnsZone(AzureRequest request, String sub, String rg, String zoneName) {
        String key = sub + "/" + rg + "/net/dnsZones/" + zoneName;
        Map<String, Object> body = parseBody(request);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));

        Map<String, Object> existingZone = resources.get(key);
        String etag = existingZone != null ? (String) existingZone.get("etag") : "\"" + java.util.UUID.randomUUID() + "\"";
        int numberOfRecordSets = existingZone != null ? ((Number) cast(existingZone.get("properties")).getOrDefault("numberOfRecordSets", 2)).intValue() : 2;

        properties.putIfAbsent("maxNumberOfRecordSets", 10000);
        properties.putIfAbsent("numberOfRecordSets", numberOfRecordSets);
        properties.putIfAbsent("zoneType", "Public");
        properties.putIfAbsent("nameServers", List.of(
                "ns1-01.azure-dns.com.",
                "ns2-01.azure-dns.net.",
                "ns3-01.azure-dns.org.",
                "ns4-01.azure-dns.info."
        ));

        Map<String, Object> zone = new LinkedHashMap<>();
        zone.put("_sub", sub);
        zone.put("_rg", rg);
        zone.put("id", "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Network/dnsZones/" + zoneName);
        zone.put("name", zoneName);
        zone.put("type", "Microsoft.Network/dnsZones");
        zone.put("location", bodyString(body, "location", "global"));
        if (body.get("tags") instanceof Map<?, ?> tags && !tags.isEmpty()) {
            zone.put("tags", tags);
        } else if (existingZone != null && existingZone.containsKey("tags")) {
            zone.put("tags", existingZone.get("tags"));
        }
        zone.put("etag", etag);
        zone.put("properties", properties);

        resources.put(key, zone);

        if (existingZone == null) {
            createDefaultRecordSets(sub, rg, zoneName);
        }

        return Response.status(existingZone == null ? 201 : 200).entity(stripInternal(zone)).build();
    }

    private void createDefaultRecordSets(String sub, String rg, String zoneName) {
        String soaKey = sub + "/" + rg + "/net/dnsZones/" + zoneName + "/SOA/@";
        Map<String, Object> soaProperties = new LinkedHashMap<>();
        soaProperties.put("fqdn", zoneName + ".");
        soaProperties.put("TTL", 3600);
        soaProperties.put("SOARecord", Map.of(
                "host", "ns1-01.azure-dns.com.",
                "email", "azuredns-hostmaster.microsoft.com.",
                "serialNumber", 1L,
                "refreshTime", 3600L,
                "retryTime", 300L,
                "expireTime", 2419200L,
                "minimumTTL", 300L
        ));
        Map<String, Object> soa = new LinkedHashMap<>();
        soa.put("_sub", sub);
        soa.put("_rg", rg);
        soa.put("id", "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Network/dnsZones/" + zoneName + "/SOA/@");
        soa.put("name", "@");
        soa.put("type", "Microsoft.Network/dnsZones/SOA");
        soa.put("etag", "\"" + java.util.UUID.randomUUID() + "\"");
        soa.put("properties", soaProperties);
        resources.put(soaKey, soa);

        String nsKey = sub + "/" + rg + "/net/dnsZones/" + zoneName + "/NS/@";
        Map<String, Object> nsProperties = new LinkedHashMap<>();
        nsProperties.put("fqdn", zoneName + ".");
        nsProperties.put("TTL", 172800);
        nsProperties.put("NSRecords", List.of(
                Map.of("nsdname", "ns1-01.azure-dns.com."),
                Map.of("nsdname", "ns2-01.azure-dns.net."),
                Map.of("nsdname", "ns3-01.azure-dns.org."),
                Map.of("nsdname", "ns4-01.azure-dns.info.")
        ));
        Map<String, Object> ns = new LinkedHashMap<>();
        ns.put("_sub", sub);
        ns.put("_rg", rg);
        ns.put("id", "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Network/dnsZones/" + zoneName + "/NS/@");
        ns.put("name", "@");
        ns.put("type", "Microsoft.Network/dnsZones/NS");
        ns.put("etag", "\"" + java.util.UUID.randomUUID() + "\"");
        ns.put("properties", nsProperties);
        resources.put(nsKey, ns);
    }

    private Response deleteDnsZone(String sub, String rg, String zoneName) {
        String key = sub + "/" + rg + "/net/dnsZones/" + zoneName;
        Map<String, Object> zone = resources.remove(key);
        if (zone == null) {
            return notFound("dnsZones/" + zoneName);
        }
        String prefix = key + "/";
        resources.keySet().removeIf(k -> k.startsWith(prefix));
        return Response.ok().build();
    }

    private List<Map<String, Object>> listAllRecordSets(String sub, String rg, String zoneName) {
        String prefix = sub + "/" + rg + "/net/dnsZones/" + zoneName + "/";
        return resources.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .map(NetworkService::stripInternal)
                .toList();
    }

    private List<Map<String, Object>> listRecordSetsByType(String sub, String rg, String zoneName, String recordType) {
        String prefix = sub + "/" + rg + "/net/dnsZones/" + zoneName + "/" + recordType + "/";
        return resources.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .map(NetworkService::stripInternal)
                .toList();
    }

    private Response getRecordSet(String sub, String rg, String zoneName, String recordType, String relativeRecordSetName) {
        String key = sub + "/" + rg + "/net/dnsZones/" + zoneName + "/" + recordType + "/" + relativeRecordSetName;
        Map<String, Object> recordSet = resources.get(key);
        if (recordSet == null) {
            return notFound("dnsZones/" + zoneName + "/" + recordType + "/" + relativeRecordSetName);
        }
        return Response.ok(stripInternal(recordSet)).build();
    }

    private Response deleteRecordSet(String sub, String rg, String zoneName, String recordType, String relativeRecordSetName) {
        if ("@".equals(relativeRecordSetName) && ("SOA".equals(recordType) || "NS".equals(recordType))) {
            return Response.status(400).entity(Map.of("error", Map.of(
                    "code", "BadRequest",
                    "message", "Default SOA and NS record sets at the zone root cannot be deleted."
            ))).build();
        }

        String key = sub + "/" + rg + "/net/dnsZones/" + zoneName + "/" + recordType + "/" + relativeRecordSetName;
        Map<String, Object> recordSet = resources.remove(key);
        if (recordSet == null) {
            return notFound("dnsZones/" + zoneName + "/" + recordType + "/" + relativeRecordSetName);
        }

        decrementZoneRecordSetCount(sub, rg, zoneName);

        return Response.ok().build();
    }

    private Response createOrUpdateRecordSet(AzureRequest request, String sub, String rg, String zoneName, String recordType, String relativeRecordSetName) {
        String key = sub + "/" + rg + "/net/dnsZones/" + zoneName + "/" + recordType + "/" + relativeRecordSetName;

        String zoneKey = sub + "/" + rg + "/net/dnsZones/" + zoneName;
        Map<String, Object> parentZone = resources.get(zoneKey);
        if (parentZone == null) {
            return Response.status(404).entity(Map.of("error", Map.of(
                    "code", "ParentResourceNotFound",
                    "message", "DNS Zone " + zoneName + " was not found."
            ))).build();
        }

        Map<String, Object> existingRecordSet = resources.get(key);

        String ifMatch = request.headers().getHeaderString("If-Match");
        String ifNoneMatch = request.headers().getHeaderString("If-None-Match");

        if (ifMatch != null) {
            if ("*".equals(ifMatch)) {
                if (existingRecordSet == null) {
                    return preconditionFailed();
                }
            } else {
                if (existingRecordSet == null || !ifMatch.equals(existingRecordSet.get("etag"))) {
                    return preconditionFailed();
                }
            }
        }

        if (ifNoneMatch != null) {
            if ("*".equals(ifNoneMatch)) {
                if (existingRecordSet != null) {
                    return preconditionFailed();
                }
            } else {
                if (existingRecordSet != null && ifNoneMatch.equals(existingRecordSet.get("etag"))) {
                    return preconditionFailed();
                }
            }
        }

        Map<String, Object> body = parseBody(request);
        Map<String, Object> bodyProps = body.containsKey("properties") ? cast(body.get("properties")) : body;

        Object ttlVal = bodyProps.containsKey("TTL") ? bodyProps.get("TTL") : bodyProps.get("ttl");
        int ttl = ttlVal instanceof Number n ? n.intValue() : 3600;

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("fqdn", "@".equals(relativeRecordSetName) ? zoneName + "." : relativeRecordSetName + "." + zoneName + ".");
        properties.put("TTL", ttl);

        if (bodyProps.containsKey("metadata")) {
            properties.put("metadata", bodyProps.get("metadata"));
        } else if (bodyProps.containsKey("Metadata")) {
            properties.put("metadata", bodyProps.get("Metadata"));
        }

        copyRecordProperties(recordType, bodyProps, properties);

        String newEtag = "\"" + java.util.UUID.randomUUID() + "\"";

        Map<String, Object> recordSet = new LinkedHashMap<>();
        recordSet.put("_sub", sub);
        recordSet.put("_rg", rg);
        recordSet.put("id", "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Network/dnsZones/" + zoneName + "/" + recordType + "/" + relativeRecordSetName);
        recordSet.put("name", relativeRecordSetName);
        recordSet.put("type", "Microsoft.Network/dnsZones/" + recordType);
        recordSet.put("etag", newEtag);
        recordSet.put("properties", properties);

        resources.put(key, recordSet);

        if (existingRecordSet == null) {
            incrementZoneRecordSetCount(sub, rg, zoneName);
        }

        return Response.status(existingRecordSet == null ? 201 : 200).entity(stripInternal(recordSet)).build();
    }

    private static void copyRecordProperties(String recordType, Map<String, Object> bodyProps, Map<String, Object> properties) {
        String uType = recordType.toUpperCase();
        switch (uType) {
            case "A"     -> copyProp(bodyProps, properties, "aRecords", "ARecords");
            case "AAAA"  -> copyProp(bodyProps, properties, "aaaaRecords", "AAAARecords");
            case "CNAME" -> copyProp(bodyProps, properties, "cnameRecord", "CNAMERecord");
            case "MX"    -> copyProp(bodyProps, properties, "mxRecords", "MXRecords");
            case "NS"    -> copyProp(bodyProps, properties, "nsRecords", "NSRecords");
            case "SOA"   -> copyProp(bodyProps, properties, "soaRecord", "SOARecord");
            case "SRV"   -> copyProp(bodyProps, properties, "srvRecords", "SRVRecords");
            case "TXT"   -> copyProp(bodyProps, properties, "txtRecords", "TXTRecords");
            case "CAA"   -> copyProp(bodyProps, properties, "caaRecords", "CAARecords");
        }
    }

    private static void copyProp(Map<String, Object> src, Map<String, Object> dest, String key1, String key2) {
        if (src.containsKey(key1)) {
            dest.put(key1, src.get(key1));
            dest.put(key2, src.get(key1));
        } else if (src.containsKey(key2)) {
            dest.put(key1, src.get(key2));
            dest.put(key2, src.get(key2));
        }
    }

    private void incrementZoneRecordSetCount(String sub, String rg, String zoneName) {
        String zoneKey = sub + "/" + rg + "/net/dnsZones/" + zoneName;
        Map<String, Object> zone = resources.get(zoneKey);
        if (zone != null) {
            Map<String, Object> props = new LinkedHashMap<>(cast(zone.get("properties")));
            int count = ((Number) props.getOrDefault("numberOfRecordSets", 2)).intValue();
            props.put("numberOfRecordSets", count + 1);
            zone.put("properties", props);
        }
    }

    private void decrementZoneRecordSetCount(String sub, String rg, String zoneName) {
        String zoneKey = sub + "/" + rg + "/net/dnsZones/" + zoneName;
        Map<String, Object> zone = resources.get(zoneKey);
        if (zone != null) {
            Map<String, Object> props = new LinkedHashMap<>(cast(zone.get("properties")));
            int count = ((Number) props.getOrDefault("numberOfRecordSets", 2)).intValue();
            props.put("numberOfRecordSets", Math.max(2, count - 1));
            zone.put("properties", props);
        }
    }

    private Response preconditionFailed() {
        return Response.status(412).entity(Map.of("error", Map.of(
                "code", "PreconditionFailed",
                "message", "The precondition given in the Request-Headers failed for this resource."
        ))).build();
    }
}
