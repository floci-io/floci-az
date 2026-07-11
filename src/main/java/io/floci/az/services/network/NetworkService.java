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

        if (tail.startsWith("privateDnsZones")) {
            return handlePrivateDnsZones(request, path, method, sub, rg, tail);
        }

        if (tail.startsWith("privateEndpoints")) {
            return handlePrivateEndpoints(request, path, method, sub, rg, tail);
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
        synthesizeProperties(resourceType, name, properties);
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
    private static void synthesizeProperties(String resourceType, String name, Map<String, Object> properties) {
        switch (resourceType) {
            case "Microsoft.Network/privateLinkServices" -> {
                properties.putIfAbsent("alias",
                        name + "." + java.util.UUID.randomUUID() + ".azure.privatelinkservice");
                properties.putIfAbsent("visibility", new LinkedHashMap<>(Map.of("subscriptions", List.of())));
                properties.putIfAbsent("autoApproval", new LinkedHashMap<>(Map.of("subscriptions", List.of())));
                properties.putIfAbsent("fqdns", List.of());
            }
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
        return ArmJson.parseBodyStrict(request);
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
            case "PTR"   -> copyProp(bodyProps, properties, "ptrRecords", "PTRRecords");
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

    // ── Azure Private DNS Support ───────────────────────────────────────────

    private Response handlePrivateDnsZones(AzureRequest request, String path, String method, String sub, String rg, String tail) {
        String[] parts = tail.split("/");

        // 1. List zones
        if (parts.length == 1) {
            return Response.ok(Map.of("value", listPrivateDnsZones(sub, rg))).build();
        }

        // 2. Zone CRUD
        if (parts.length == 2) {
            String zoneName = parts[1];
            return switch (method) {
                case "PUT"    -> createOrUpdatePrivateDnsZone(request, sub, rg, zoneName);
                case "GET"    -> getPrivateDnsZone(sub, rg, zoneName);
                case "DELETE" -> deletePrivateDnsZone(sub, rg, zoneName);
                default       -> Response.status(405).build();
            };
        }

        // 3. List virtual network links
        if (parts.length == 3 && "virtualNetworkLinks".equals(parts[2])) {
            return Response.ok(Map.of("value", listVirtualNetworkLinks(sub, rg, parts[1]))).build();
        }

        // 4. Virtual network link CRUD
        if (parts.length == 4 && "virtualNetworkLinks".equals(parts[2])) {
            String zoneName = parts[1];
            String linkName = parts[3];
            return switch (method) {
                case "PUT"    -> createOrUpdateVirtualNetworkLink(request, sub, rg, zoneName, linkName);
                case "GET"    -> getVirtualNetworkLink(sub, rg, zoneName, linkName);
                case "DELETE" -> deleteVirtualNetworkLink(sub, rg, zoneName, linkName);
                default       -> Response.status(405).build();
            };
        }

        // 5. List all record sets
        if (parts.length == 3 && ("recordsets".equalsIgnoreCase(parts[2]) || "all".equalsIgnoreCase(parts[2]))) {
            return Response.ok(Map.of("value", listAllPrivateRecordSets(sub, rg, parts[1]))).build();
        }

        // 6. List record sets by type
        if (parts.length == 3 && isPrivateDnsRecordType(parts[2])) {
            return Response.ok(Map.of("value", listPrivateRecordSetsByType(sub, rg, parts[1], parts[2]))).build();
        }

        // 7. Record set CRUD
        if (parts.length >= 4 && isPrivateDnsRecordType(parts[2])) {
            String zoneName = parts[1];
            String recordType = parts[2];
            StringBuilder nameBuilder = new StringBuilder(parts[3]);
            for (int i = 4; i < parts.length; i++) {
                nameBuilder.append("/").append(parts[i]);
            }
            String relativeRecordSetName = nameBuilder.toString();

            return switch (method) {
                case "PUT"    -> createOrUpdatePrivateRecordSet(request, sub, rg, zoneName, recordType, relativeRecordSetName);
                case "GET"    -> getPrivateRecordSet(sub, rg, zoneName, recordType, relativeRecordSetName);
                case "DELETE" -> deletePrivateRecordSet(sub, rg, zoneName, recordType, relativeRecordSetName);
                default       -> Response.status(405).build();
            };
        }

        return notFound(tail);
    }

    private static boolean isPrivateDnsRecordType(String part) {
        String u = part.toUpperCase();
        return "A".equals(u) || "AAAA".equals(u) || "CNAME".equals(u) ||
               "TXT".equals(u) || "MX".equals(u) || "PTR".equals(u) ||
               "SOA".equals(u) || "SRV".equals(u);
    }

    private static String privateZoneKey(String sub, String rg, String zoneName) {
        return sub + "/" + rg + "/net/privateDnsZones/" + zoneName;
    }

    private List<Map<String, Object>> listPrivateDnsZones(String sub, String rg) {
        return resources.values().stream()
                .filter(r -> sub.equals(r.get("_sub"))
                        && ("unknown".equals(rg) || rg.equals(r.get("_rg")))
                        && "Microsoft.Network/privateDnsZones".equals(r.get("type")))
                .map(NetworkService::stripInternal)
                .toList();
    }

    private Response getPrivateDnsZone(String sub, String rg, String zoneName) {
        Map<String, Object> zone = resources.get(privateZoneKey(sub, rg, zoneName));
        if (zone == null) {
            return notFound("privateDnsZones/" + zoneName);
        }
        return Response.ok(stripInternal(zone)).build();
    }

    private Response createOrUpdatePrivateDnsZone(AzureRequest request, String sub, String rg, String zoneName) {
        String key = privateZoneKey(sub, rg, zoneName);
        Map<String, Object> body = parseBody(request);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));

        Map<String, Object> existingZone = resources.get(key);
        // Azure rotates the ETag on every mutation (as record sets and VNet links do here).
        String etag = "\"" + java.util.UUID.randomUUID() + "\"";
        Map<String, Object> existingProps = existingZone != null ? cast(existingZone.get("properties")) : Map.of();

        properties.putIfAbsent("maxNumberOfRecordSets", 25000);
        properties.putIfAbsent("maxNumberOfVirtualNetworkLinks", 1000);
        properties.putIfAbsent("maxNumberOfVirtualNetworkLinksWithRegistration", 100);
        properties.putIfAbsent("numberOfRecordSets",
                ((Number) existingProps.getOrDefault("numberOfRecordSets", 1)).intValue());
        properties.putIfAbsent("numberOfVirtualNetworkLinks",
                ((Number) existingProps.getOrDefault("numberOfVirtualNetworkLinks", 0)).intValue());
        properties.putIfAbsent("numberOfVirtualNetworkLinksWithRegistration",
                ((Number) existingProps.getOrDefault("numberOfVirtualNetworkLinksWithRegistration", 0)).intValue());
        properties.put("provisioningState", "Succeeded");

        Map<String, Object> zone = new LinkedHashMap<>();
        zone.put("_sub", sub);
        zone.put("_rg", rg);
        zone.put("id", "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Network/privateDnsZones/" + zoneName);
        zone.put("name", zoneName);
        zone.put("type", "Microsoft.Network/privateDnsZones");
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
            createDefaultPrivateSoaRecordSet(sub, rg, zoneName);
        }

        return Response.status(existingZone == null ? 201 : 200).entity(stripInternal(zone)).build();
    }

    private void createDefaultPrivateSoaRecordSet(String sub, String rg, String zoneName) {
        String soaKey = privateZoneKey(sub, rg, zoneName) + "/SOA/@";
        Map<String, Object> soaProperties = new LinkedHashMap<>();
        soaProperties.put("fqdn", zoneName + ".");
        soaProperties.put("TTL", 3600);
        soaProperties.put("SOARecord", Map.of(
                "host", "azureprivatedns.net.",
                "email", "azureprivatedns-host.microsoft.com.",
                "serialNumber", 1L,
                "refreshTime", 3600L,
                "retryTime", 300L,
                "expireTime", 2419200L,
                "minimumTTL", 10L
        ));
        Map<String, Object> soa = new LinkedHashMap<>();
        soa.put("_sub", sub);
        soa.put("_rg", rg);
        soa.put("id", "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Network/privateDnsZones/" + zoneName + "/SOA/@");
        soa.put("name", "@");
        soa.put("type", "Microsoft.Network/privateDnsZones/SOA");
        soa.put("etag", "\"" + java.util.UUID.randomUUID() + "\"");
        soa.put("properties", soaProperties);
        resources.put(soaKey, soa);
    }

    private Response deletePrivateDnsZone(String sub, String rg, String zoneName) {
        String key = privateZoneKey(sub, rg, zoneName);
        Map<String, Object> zone = resources.remove(key);
        if (zone == null) {
            return notFound("privateDnsZones/" + zoneName);
        }
        String prefix = key + "/";
        resources.keySet().removeIf(k -> k.startsWith(prefix));
        return Response.ok().build();
    }

    private List<Map<String, Object>> listAllPrivateRecordSets(String sub, String rg, String zoneName) {
        String prefix = privateZoneKey(sub, rg, zoneName) + "/";
        return resources.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix) && !e.getKey().contains("/virtualNetworkLinks/"))
                .map(Map.Entry::getValue)
                .map(NetworkService::stripInternal)
                .toList();
    }

    private List<Map<String, Object>> listPrivateRecordSetsByType(String sub, String rg, String zoneName, String recordType) {
        String prefix = privateZoneKey(sub, rg, zoneName) + "/" + recordType + "/";
        return resources.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .map(NetworkService::stripInternal)
                .toList();
    }

    private Response getPrivateRecordSet(String sub, String rg, String zoneName, String recordType, String relativeRecordSetName) {
        String key = privateZoneKey(sub, rg, zoneName) + "/" + recordType + "/" + relativeRecordSetName;
        Map<String, Object> recordSet = resources.get(key);
        if (recordSet == null) {
            return notFound("privateDnsZones/" + zoneName + "/" + recordType + "/" + relativeRecordSetName);
        }
        return Response.ok(stripInternal(recordSet)).build();
    }

    private Response deletePrivateRecordSet(String sub, String rg, String zoneName, String recordType, String relativeRecordSetName) {
        if ("@".equals(relativeRecordSetName) && "SOA".equals(recordType)) {
            return Response.status(400).entity(Map.of("error", Map.of(
                    "code", "BadRequest",
                    "message", "The default SOA record set at the zone root cannot be deleted."
            ))).build();
        }

        String key = privateZoneKey(sub, rg, zoneName) + "/" + recordType + "/" + relativeRecordSetName;
        Map<String, Object> recordSet = resources.remove(key);
        if (recordSet == null) {
            return notFound("privateDnsZones/" + zoneName + "/" + recordType + "/" + relativeRecordSetName);
        }

        adjustPrivateZoneCount(sub, rg, zoneName, "numberOfRecordSets", -1, 1);

        return Response.ok().build();
    }

    private Response createOrUpdatePrivateRecordSet(AzureRequest request, String sub, String rg, String zoneName, String recordType, String relativeRecordSetName) {
        String key = privateZoneKey(sub, rg, zoneName) + "/" + recordType + "/" + relativeRecordSetName;

        Map<String, Object> parentZone = resources.get(privateZoneKey(sub, rg, zoneName));
        if (parentZone == null) {
            return Response.status(404).entity(Map.of("error", Map.of(
                    "code", "ParentResourceNotFound",
                    "message", "Private DNS Zone " + zoneName + " was not found."
            ))).build();
        }

        Map<String, Object> existingRecordSet = resources.get(key);

        Response precondition = checkPreconditions(request, existingRecordSet);
        if (precondition != null) {
            return precondition;
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

        Map<String, Object> recordSet = new LinkedHashMap<>();
        recordSet.put("_sub", sub);
        recordSet.put("_rg", rg);
        recordSet.put("id", "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Network/privateDnsZones/" + zoneName + "/" + recordType + "/" + relativeRecordSetName);
        recordSet.put("name", relativeRecordSetName);
        recordSet.put("type", "Microsoft.Network/privateDnsZones/" + recordType);
        recordSet.put("etag", "\"" + java.util.UUID.randomUUID() + "\"");
        recordSet.put("properties", properties);

        resources.put(key, recordSet);

        if (existingRecordSet == null) {
            adjustPrivateZoneCount(sub, rg, zoneName, "numberOfRecordSets", 1, 1);
        }

        return Response.status(existingRecordSet == null ? 201 : 200).entity(stripInternal(recordSet)).build();
    }

    private List<Map<String, Object>> listVirtualNetworkLinks(String sub, String rg, String zoneName) {
        String prefix = privateZoneKey(sub, rg, zoneName) + "/virtualNetworkLinks/";
        return resources.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .map(NetworkService::stripInternal)
                .toList();
    }

    private Response getVirtualNetworkLink(String sub, String rg, String zoneName, String linkName) {
        String key = privateZoneKey(sub, rg, zoneName) + "/virtualNetworkLinks/" + linkName;
        Map<String, Object> link = resources.get(key);
        if (link == null) {
            return notFound("privateDnsZones/" + zoneName + "/virtualNetworkLinks/" + linkName);
        }
        return Response.ok(stripInternal(link)).build();
    }

    private Response createOrUpdateVirtualNetworkLink(AzureRequest request, String sub, String rg, String zoneName, String linkName) {
        Map<String, Object> parentZone = resources.get(privateZoneKey(sub, rg, zoneName));
        if (parentZone == null) {
            return Response.status(404).entity(Map.of("error", Map.of(
                    "code", "ParentResourceNotFound",
                    "message", "Private DNS Zone " + zoneName + " was not found."
            ))).build();
        }

        String key = privateZoneKey(sub, rg, zoneName) + "/virtualNetworkLinks/" + linkName;
        Map<String, Object> existing = resources.get(key);

        Map<String, Object> body = parseBody(request);
        Map<String, Object> bodyProps = cast(body.get("properties"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("provisioningState", "Succeeded");
        properties.put("registrationEnabled", bodyProps.getOrDefault("registrationEnabled", false));
        if (bodyProps.get("virtualNetwork") instanceof Map<?, ?> vnet) {
            properties.put("virtualNetwork", vnet);
        }
        properties.put("virtualNetworkLinkState", "Completed");

        Map<String, Object> link = new LinkedHashMap<>();
        link.put("_sub", sub);
        link.put("_rg", rg);
        link.put("id", "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Network/privateDnsZones/" + zoneName + "/virtualNetworkLinks/" + linkName);
        link.put("name", linkName);
        link.put("type", "Microsoft.Network/privateDnsZones/virtualNetworkLinks");
        link.put("location", bodyString(body, "location", "global"));
        if (body.get("tags") instanceof Map<?, ?> tags && !tags.isEmpty()) {
            link.put("tags", tags);
        }
        link.put("etag", "\"" + java.util.UUID.randomUUID() + "\"");
        link.put("properties", properties);

        resources.put(key, link);

        if (existing == null) {
            adjustPrivateZoneCount(sub, rg, zoneName, "numberOfVirtualNetworkLinks", 1, 0);
        }

        return Response.status(existing == null ? 201 : 200).entity(stripInternal(link)).build();
    }

    private Response deleteVirtualNetworkLink(String sub, String rg, String zoneName, String linkName) {
        String key = privateZoneKey(sub, rg, zoneName) + "/virtualNetworkLinks/" + linkName;
        Map<String, Object> link = resources.remove(key);
        if (link == null) {
            return notFound("privateDnsZones/" + zoneName + "/virtualNetworkLinks/" + linkName);
        }
        adjustPrivateZoneCount(sub, rg, zoneName, "numberOfVirtualNetworkLinks", -1, 0);
        return Response.ok().build();
    }

    private Response checkPreconditions(AzureRequest request, Map<String, Object> existing) {
        String ifMatch = request.headers().getHeaderString("If-Match");
        String ifNoneMatch = request.headers().getHeaderString("If-None-Match");

        if (ifMatch != null) {
            if ("*".equals(ifMatch)) {
                if (existing == null) {
                    return preconditionFailed();
                }
            } else if (existing == null || !ifMatch.equals(existing.get("etag"))) {
                return preconditionFailed();
            }
        }

        if (ifNoneMatch != null) {
            if ("*".equals(ifNoneMatch)) {
                if (existing != null) {
                    return preconditionFailed();
                }
            } else if (existing != null && ifNoneMatch.equals(existing.get("etag"))) {
                return preconditionFailed();
            }
        }
        return null;
    }

    private void adjustPrivateZoneCount(String sub, String rg, String zoneName, String field, int delta, int floor) {
        Map<String, Object> zone = resources.get(privateZoneKey(sub, rg, zoneName));
        if (zone != null) {
            Map<String, Object> props = new LinkedHashMap<>(cast(zone.get("properties")));
            int count = ((Number) props.getOrDefault(field, floor)).intValue();
            props.put(field, Math.max(floor, count + delta));
            zone.put("properties", props);
        }
    }

    // ── Azure Private Endpoint Support ──────────────────────────────────────

    private Response handlePrivateEndpoints(AzureRequest request, String path, String method, String sub, String rg, String tail) {
        String[] parts = tail.split("/");

        // 0. List all endpoints in the resource group
        if (parts.length == 1) {
            return Response.ok(Map.of("value",
                    listResources(sub, rg, "Microsoft.Network/privateEndpoints"))).build();
        }

        // 1. Endpoint CRUD
        if (parts.length == 2) {
            String peName = parts[1];
            return switch (method) {
                case "PUT"    -> createOrUpdatePrivateEndpoint(request, sub, rg, peName);
                case "GET"    -> {
                    Map<String, Object> pe = resources.get(privateEndpointKey(sub, rg, peName));
                    yield pe == null ? notFound("privateEndpoints/" + peName) : Response.ok(stripInternal(pe)).build();
                }
                case "DELETE" -> deletePrivateEndpoint(sub, rg, peName);
                default       -> Response.status(405).build();
            };
        }

        // 2. List private DNS zone groups
        if (parts.length == 3 && "privateDnsZoneGroups".equals(parts[2])) {
            return Response.ok(Map.of("value", listPrivateDnsZoneGroups(sub, rg, parts[1]))).build();
        }

        // 3. Private DNS zone group CRUD
        if (parts.length == 4 && "privateDnsZoneGroups".equals(parts[2])) {
            String peName = parts[1];
            String groupName = parts[3];
            return switch (method) {
                case "PUT"    -> createOrUpdatePrivateDnsZoneGroup(request, sub, rg, peName, groupName);
                case "GET"    -> getPrivateDnsZoneGroup(sub, rg, peName, groupName);
                case "DELETE" -> deletePrivateDnsZoneGroup(sub, rg, peName, groupName);
                default       -> Response.status(405).build();
            };
        }

        return notFound(tail);
    }

    private static String privateEndpointKey(String sub, String rg, String peName) {
        return sub + "/" + rg + "/net/privateEndpoints/" + peName;
    }

    @SuppressWarnings("unchecked")
    private Response createOrUpdatePrivateEndpoint(AzureRequest request, String sub, String rg, String peName) {
        String key = privateEndpointKey(sub, rg, peName);
        String peId = "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Network/privateEndpoints/" + peName;
        Map<String, Object> body = parseBody(request);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));

        Map<String, Object> existingPe = resources.get(key);

        List<Object> connections = normalizeConnections(peId, properties.get("privateLinkServiceConnections"), false);
        List<Object> manualConnections = normalizeConnections(peId, properties.get("manualPrivateLinkServiceConnections"), true);
        properties.put("privateLinkServiceConnections", connections);
        properties.put("manualPrivateLinkServiceConnections", manualConnections);
        properties.putIfAbsent("customDnsConfigs", List.of());

        // The synthesized NIC is stable for the endpoint's lifetime (as in Azure), so
        // reuse the existing one on update rather than minting a new randomly-named NIC
        // on every PUT — otherwise prior NICs leak into the store and survive the
        // endpoint's cascade delete. Only synthesize on first create or when an explicit
        // customNetworkInterfaceName changes it (removing the NIC it replaces).
        String customNicName = bodyString(properties, "customNetworkInterfaceName", null);
        String existingNicKey = existingPe != null && existingPe.get("_nic") instanceof String s ? s : null;
        String nicKey;
        String nicId;
        if (existingNicKey != null && resources.containsKey(existingNicKey)
                && (customNicName == null || existingNicKey.equals(key(sub, rg, "networkInterfaces/" + customNicName)))) {
            nicKey = existingNicKey;
            nicId = String.valueOf(cast(resources.get(existingNicKey)).get("id"));
        } else {
            String nicName = customNicName != null ? customNicName
                    : peName + ".nic." + java.util.UUID.randomUUID().toString().substring(0, 8);
            nicKey = key(sub, rg, "networkInterfaces/" + nicName);
            nicId = "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Network/networkInterfaces/" + nicName;
            Map<String, Object> nicProps = new LinkedHashMap<>();
            synthesizeProperties("Microsoft.Network/networkInterfaces", nicName, nicProps);
            nicProps.put("provisioningState", "Succeeded");
            Map<String, Object> nic = new LinkedHashMap<>();
            nic.put("_sub", sub);
            nic.put("_rg", rg);
            nic.put("id", nicId);
            nic.put("name", nicName);
            nic.put("type", "Microsoft.Network/networkInterfaces");
            nic.put("properties", nicProps);
            resources.put(nicKey, nic);
            if (existingNicKey != null && !existingNicKey.equals(nicKey)) {
                resources.remove(existingNicKey);
            }
        }

        properties.put("networkInterfaces", List.of(new LinkedHashMap<>(Map.of("id", nicId))));
        properties.put("provisioningState", "Succeeded");

        Map<String, Object> pe = new LinkedHashMap<>();
        pe.put("_sub", sub);
        pe.put("_rg", rg);
        pe.put("_nic", nicKey);
        pe.put("id", peId);
        pe.put("name", peName);
        pe.put("type", "Microsoft.Network/privateEndpoints");
        String location = bodyString(body, "location", null);
        if (location != null) {
            pe.put("location", location);
        }
        if (body.get("tags") instanceof Map<?, ?> tags && !tags.isEmpty()) {
            pe.put("tags", tags);
        }
        pe.put("properties", properties);

        resources.put(key, pe);
        return Response.status(existingPe == null ? 201 : 200).entity(stripInternal(pe)).build();
    }

    @SuppressWarnings("unchecked")
    private List<Object> normalizeConnections(String peId, Object raw, boolean manual) {
        if (!(raw instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Object> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> conn = new LinkedHashMap<>(cast(item));
            String name = bodyString(conn, "name", "connection");
            conn.put("id", peId + "/privateLinkServiceConnections/" + name);
            Map<String, Object> connProps = new LinkedHashMap<>(cast(conn.get("properties")));
            Map<String, Object> state = new LinkedHashMap<>(cast(connProps.get("privateLinkServiceConnectionState")));
            if (manual) {
                state.putIfAbsent("status", "Pending");
                state.putIfAbsent("description", "Awaiting approval");
            } else {
                state.put("status", "Approved");
                state.put("description", "Auto-approved");
            }
            state.putIfAbsent("actionsRequired", "None");
            connProps.put("privateLinkServiceConnectionState", state);
            connProps.put("provisioningState", "Succeeded");
            conn.put("properties", connProps);
            result.add(conn);
        }
        return result;
    }

    private Response deletePrivateEndpoint(String sub, String rg, String peName) {
        String key = privateEndpointKey(sub, rg, peName);
        Map<String, Object> pe = resources.remove(key);
        if (pe == null) {
            return notFound("privateEndpoints/" + peName);
        }
        if (pe.get("_nic") instanceof String nicKey) {
            resources.remove(nicKey);
        }
        String prefix = key + "/";
        resources.keySet().removeIf(k -> k.startsWith(prefix));
        return Response.ok().build();
    }

    private List<Map<String, Object>> listPrivateDnsZoneGroups(String sub, String rg, String peName) {
        String prefix = privateEndpointKey(sub, rg, peName) + "/privateDnsZoneGroups/";
        return resources.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .map(NetworkService::stripInternal)
                .toList();
    }

    private Response getPrivateDnsZoneGroup(String sub, String rg, String peName, String groupName) {
        String key = privateEndpointKey(sub, rg, peName) + "/privateDnsZoneGroups/" + groupName;
        Map<String, Object> group = resources.get(key);
        if (group == null) {
            return notFound("privateEndpoints/" + peName + "/privateDnsZoneGroups/" + groupName);
        }
        return Response.ok(stripInternal(group)).build();
    }

    @SuppressWarnings("unchecked")
    private Response createOrUpdatePrivateDnsZoneGroup(AzureRequest request, String sub, String rg, String peName, String groupName) {
        if (resources.get(privateEndpointKey(sub, rg, peName)) == null) {
            return Response.status(404).entity(Map.of("error", Map.of(
                    "code", "ParentResourceNotFound",
                    "message", "Private Endpoint " + peName + " was not found."
            ))).build();
        }

        String key = privateEndpointKey(sub, rg, peName) + "/privateDnsZoneGroups/" + groupName;
        Map<String, Object> body = parseBody(request);
        Map<String, Object> bodyProps = cast(body.get("properties"));

        List<Object> configs = new ArrayList<>();
        if (bodyProps.get("privateDnsZoneConfigs") instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> cfg = new LinkedHashMap<>(cast(item));
                Map<String, Object> cfgProps = new LinkedHashMap<>(cast(cfg.get("properties")));
                cfgProps.putIfAbsent("recordSets", List.of());
                cfg.put("properties", cfgProps);
                configs.add(cfg);
            }
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("provisioningState", "Succeeded");
        properties.put("privateDnsZoneConfigs", configs);

        Map<String, Object> group = new LinkedHashMap<>();
        group.put("_sub", sub);
        group.put("_rg", rg);
        group.put("id", "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Network/privateEndpoints/" + peName + "/privateDnsZoneGroups/" + groupName);
        group.put("name", groupName);
        group.put("type", "Microsoft.Network/privateEndpoints/privateDnsZoneGroups");
        group.put("etag", "\"" + java.util.UUID.randomUUID() + "\"");
        group.put("properties", properties);

        boolean created = resources.put(key, group) == null;
        return Response.status(created ? 201 : 200).entity(stripInternal(group)).build();
    }

    private Response deletePrivateDnsZoneGroup(String sub, String rg, String peName, String groupName) {
        String key = privateEndpointKey(sub, rg, peName) + "/privateDnsZoneGroups/" + groupName;
        Map<String, Object> group = resources.remove(key);
        if (group == null) {
            return notFound("privateEndpoints/" + peName + "/privateDnsZoneGroups/" + groupName);
        }
        return Response.ok().build();
    }
}
