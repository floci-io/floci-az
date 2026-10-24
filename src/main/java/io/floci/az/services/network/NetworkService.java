package io.floci.az.services.network;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmResourceStore.Stored;
import io.floci.az.core.arm.ArmResourceStore.Written;
import io.floci.az.core.arm.ArmResources;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Microsoft.Network's ARM resources.
 *
 * <p>Every resource, top level or child (subnets, DNS record sets, virtual network links, private DNS zone groups,
 * the NIC a private endpoint synthesizes), is one JSON document in one {@link ArmResourceStore} over the
 * {@value #STORE} backend, keyed by its canonical id, so a path that differs only in case addresses the one resource
 * and the casing of its first PUT is what responses echo. One store of documents rather than a model per type: the
 * service keeps dozens of types as the documents ARM returns, and a document round-trips them byte for byte. Load
 * balancer pools and NSG rules stay arrays on their parent document, as Azure models them. The store follows the
 * global storage mode; Network was kept in memory before, so there are no older entries to read.
 */
@ApplicationScoped
public class NetworkService {

    /** The {@link StorageFactory} backend the documents live in. */
    static final String STORE = "network";

    private static final String PROVIDER = "/providers/Microsoft.Network/";
    private static final String SUBNET_TYPE = "Microsoft.Network/virtualNetworks/subnets";

    private final ArmResourceStore<Map<String, Object>> store;

    /**
     * Held by every VNet and subnet PUT and by every DELETE: a VNet's inline {@code subnets} and its child subnet
     * documents are written as several store calls, so a subnet written or deleted on its own must not interleave
     * with a VNet PUT that rewrites the set.
     */
    private final Object subnetLock = new Object();

    @Inject
    public NetworkService(StorageFactory storageFactory) {
        this.store = ArmResourceStore.create(storageFactory, STORE, documentType(),
                document -> String.valueOf(document.get("id")), key -> false);
    }

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> documentType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    public Response handleArm(AzureRequest request, String path, String method, String sub) {
        String rg = ArmPaths.resourceGroup(path).orElse(null);
        String tail = ArmPaths.afterProvider(path, "Microsoft.Network", "unknown");

        // Every Network resource lives in a resource group: a path that names none only lists.
        if (rg == null && !tail.matches("[^/]+")) {
            return notFound(tail);
        }

        String type = typeOf(tail);
        if (isType(type, "dnsZones")) {
            return handleDnsZones(request, path, method, sub, rg, tail);
        }

        if (isType(type, "privateDnsZones")) {
            return handlePrivateDnsZones(request, path, method, sub, rg, tail);
        }

        if (isType(type, "privateEndpoints")) {
            return handlePrivateEndpoints(request, path, method, sub, rg, tail);
        }

        if (tail.matches("[^/]+")) {
            return Response.ok(Map.of("value", listResources(sub, rg, "Microsoft.Network/" + tail))).build();
        }
        if (tail.split("/").length == 3 && isChildPath(tail, "virtualNetworks", "subnets")) {
            return Response.ok(Map.of("value", children(sub, rg, resourceId(sub, rg, tail)))).build();
        }

        // Child resources whose canonical home is an array on the parent. Azure models LB
        // backend pools and NSG security rules this way, and azurerm manages them through
        // these dedicated child endpoints: routing them into the generic CRUD would store
        // a mis-typed sibling the parent read-back never sees.
        if (isChildPath(tail, "loadBalancers", "backendAddressPools")) {
            return handleParentArrayChild(request, method, sub, rg, tail,
                    "loadBalancers", "backendAddressPools", false);
        }
        if (isChildPath(tail, "networkSecurityGroups", "securityRules")) {
            return handleParentArrayChild(request, method, sub, rg, tail,
                    "networkSecurityGroups", "securityRules", false);
        }
        if (isChildPath(tail, "networkSecurityGroups", "defaultSecurityRules")) {
            return handleParentArrayChild(request, method, sub, rg, tail,
                    "networkSecurityGroups", "defaultSecurityRules", true);
        }

        String id = resourceId(sub, rg, tail);
        ArmResourceId rid = parseId(id);

        return switch (method) {
            case "PUT" -> {
                if (isVnetPath(tail) || isSubnetPath(tail)) {
                    synchronized (subnetLock) {
                        yield createOrUpdateResource(request, rid, tail);
                    }
                }
                yield createOrUpdateResource(request, rid, tail);
            }
            case "GET" -> find(rid).map(NetworkService::ok).orElseGet(() -> notFound(tail));
            case "PATCH" -> updateTags(request, tail, rid);
            case "DELETE" -> {
                synchronized (subnetLock) {
                    store.delete(rid);
                    deleteChildren(sub, rg, tail, id);
                    if (isSubnetPath(tail)) {
                        refreshParentSubnets(vnetIdOf(rid));
                    }
                }
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    /** {@code UpdateTags}: a {@code tags} object replaces the resource's tags whole; every other field stays. */
    private Response updateTags(AzureRequest request, String tail, ArmResourceId rid) {
        if (find(rid).isEmpty()) {
            return notFound(tail);
        }
        Object tags = parseBody(request).get("tags");
        return store.update(rid, existing -> {
            Map<String, Object> copy = new LinkedHashMap<>(existing);
            if (tags instanceof Map<?, ?> named) {
                if (named.isEmpty()) {
                    copy.remove("tags");
                } else {
                    copy.put("tags", named);
                }
            }
            return copy;
        }).map(stored -> ok(stored.value())).orElseGet(() -> notFound(tail));
    }

    public List<Map<String, Object>> listResources(String sub, String rg) {
        return store.list(new ArmScope(sub, rg)).stream()
                .map(Stored::value)
                .map(NetworkService::stripInternal)
                .toList();
    }

    public List<Map<String, Object>> listResources(String sub) {
        return listResources(sub, null);
    }

    /** The resources of {@code type} in the resource group, or in the whole subscription when {@code rg} is null. */
    public List<Map<String, Object>> listResources(String sub, String rg, String type) {
        return store.list(new ArmScope(sub, rg)).stream()
                .map(Stored::value)
                .filter(r -> type.equalsIgnoreCase(String.valueOf(r.get("type"))))
                .map(NetworkService::stripInternal)
                .toList();
    }

    public void clear() {
        store.clear();
    }

    private Response createOrUpdateResource(AzureRequest request, ArmResourceId rid, String tail) {
        Map<String, Object> body = parseBody(request);
        Written<Map<String, Object>> written = store.createOrUpdate(rid, existing -> {
            String id = firstCasing(existing, "id", rid.id());
            String name = firstCasing(existing, "name", resourceName(tail));
            String resourceType = firstCasing(existing, "type", resourceType(tail));
            Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
            synthesizeProperties(resourceType, name, id, properties);
            properties.put("provisioningState", "Succeeded");

            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("id", id);
            resource.put("name", name);
            resource.put("type", resourceType);
            String location = bodyString(body, "location", null);
            if (location != null) {
                resource.put("location", location);
            }
            if (body.get("tags") instanceof Map<?, ?> tags && !tags.isEmpty()) {
                resource.put("tags", tags);
            }
            // ARM envelope fields that live beside properties and must round-trip verbatim:
            // azurerm reads them from the response model, not from its own state (e.g.
            // azurerm_lb sets sku/sku_tier from model.Sku and diffs forever if it vanishes).
            for (String envelope : List.of("sku", "zones", "identity")) {
                Object value = body.get(envelope);
                if (value != null) {
                    resource.put(envelope, value);
                }
            }
            resource.put("properties", properties);
            if (isVnetPath(tail)) {
                syncInlineSubnets(id, properties, existing);
            }
            return resource;
        });
        if (isSubnetPath(tail)) {
            refreshParentSubnets(vnetIdOf(rid));
        }
        return ok(written.stored().value());
    }

    /**
     * A VNet PUT that names {@code subnets} makes that list the VNet's subnets, as ARM does: each entry is stored as
     * a {@code virtualNetworks/subnets} child, and a child the list leaves out is removed. A PUT without the key
     * keeps the children and lists them, in the previous VNet's order. Runs inside the VNet's
     * {@link ArmResourceStore#createOrUpdate}, so the child writes and the VNet write share one store transaction.
     */
    private void syncInlineSubnets(String vnetId, Map<String, Object> properties,
                                   Optional<Map<String, Object>> previous) {
        ArmResourceId subnets = parseId(vnetId + "/subnets");
        if (!(properties.get("subnets") instanceof List<?>)) {
            properties.put("subnets", childSubnets(subnets, previous.map(NetworkService::parentSubnets).orElse(List.of())));
            return;
        }
        stampChildArray(properties, "subnets", vnetId, entryProps -> { });
        List<Object> entries = new ArrayList<>();
        Set<String> kept = new HashSet<>();
        for (Object item : (List<?>) properties.get("subnets")) {
            Map<String, Object> entry = new LinkedHashMap<>(cast(item));
            String name = bodyString(entry, "name", null);
            ArmResourceId childId = parseId(subnetId(vnetId, name, entry.get("id")));
            Map<String, Object> existingChild = find(childId).orElse(null);
            String childIdText = existingChild == null ? childId.id() : String.valueOf(existingChild.get("id"));
            String childName = existingChild == null ? name : String.valueOf(existingChild.get("name"));
            entry.put("id", childIdText);
            entry.put("name", childName);
            kept.add(childId.canonicalKey());
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("id", childIdText);
            child.put("name", childName);
            child.put("type", SUBNET_TYPE);
            child.put("properties", entry.get("properties"));
            store.put(child);
            entries.add(entry);
        }
        properties.put("subnets", entries);
        for (Stored<Map<String, Object>> child : store.under(subnets)) {
            if (!kept.contains(child.id().canonicalKey())) {
                store.delete(child.id());
            }
        }
    }

    /**
     * The id a VNet PUT's inline subnet entry is stored under: the id the entry sends when that names this subnet of
     * this VNet (azurerm echoes the ids it read), else {@code {vnetId}/subnets/{name}}.
     */
    private static String subnetId(String vnetId, String name, Object sent) {
        String expected = vnetId + "/subnets/" + name;
        String expectedKey = parseId(expected).canonicalKey();
        if (sent instanceof String text) {
            Optional<ArmResourceId> parsed = ArmResourceId.parse(text);
            if (parsed.isPresent() && parsed.get().canonicalKey().equals(expectedKey)) {
                return text;
            }
        }
        return expected;
    }

    /** A subnet written or deleted on its own also updates its VNet's {@code subnets}, so a later VNet PUT that echoes them keeps it. */
    private void refreshParentSubnets(ArmResourceId vnetId) {
        ArmResourceId subnets = parseId(vnetId.id() + "/subnets");
        store.update(vnetId, existing -> {
            Map<String, Object> properties = new LinkedHashMap<>(cast(existing.get("properties")));
            properties.put("subnets", childSubnets(subnets, parentSubnets(existing)));
            Map<String, Object> copy = new LinkedHashMap<>(existing);
            copy.put("properties", properties);
            return copy;
        });
    }

    /**
     * The VNet's child subnets (the documents under {@code subnets}, its subnets collection id) as
     * {@code properties.subnets} entries: those named in {@code order} first, then the rest by name. Names compare
     * ignoring case, as the store's keys do.
     */
    private List<Object> childSubnets(ArmResourceId subnets, List<?> order) {
        Map<String, Map<String, Object>> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Stored<Map<String, Object>> child : store.under(subnets)) {
            Map<String, Object> entry = new LinkedHashMap<>(stripInternal(child.value()));
            entry.remove("location");
            byName.put(String.valueOf(entry.get("name")), entry);
        }
        List<Object> entries = new ArrayList<>();
        for (Object item : order) {
            Map<String, Object> entry = byName.remove(String.valueOf(cast(item).get("name")));
            if (entry != null) {
                entries.add(entry);
            }
        }
        entries.addAll(byName.values());
        return entries;
    }

    private static List<?> parentSubnets(Map<String, Object> vnet) {
        return cast(vnet.get("properties")).get("subnets") instanceof List<?> list ? list : List.of();
    }

    /** Whether {@code tail} addresses one virtual network: {@code virtualNetworks/{name}}, in any casing. */
    private static boolean isVnetPath(String tail) {
        String[] parts = tail.split("\\?")[0].split("/");
        return parts.length == 2 && isType(parts[0], "virtualNetworks");
    }

    /** Whether {@code tail} addresses one subnet: {@code virtualNetworks/{name}/subnets/{name}}, in any casing. */
    private static boolean isSubnetPath(String tail) {
        String clean = tail.split("\\?")[0];
        return clean.split("/").length == 4 && isChildPath(clean, "virtualNetworks", "subnets");
    }

    /** The VNet a subnet id sits in. */
    private static ArmResourceId vnetIdOf(ArmResourceId subnetId) {
        String id = subnetId.id();
        int subnets = id.toLowerCase(Locale.ROOT).lastIndexOf("/subnets/");
        return parseId(id.substring(0, subnets));
    }

    private void deleteChildren(String sub, String rg, String tail, String id) {
        String[] parts = tail.split("/");
        if (parts.length == 2 && isType(parts[0], "virtualNetworks")) {
            deleteUnder(sub, rg, id + "/subnets");
        }
    }

    /**
     * CRUD for child resources stored as an array on the parent (LB backendAddressPools,
     * NSG securityRules/defaultSecurityRules). The parent's array is the single source of
     * truth: a child PUT upserts into it, so the parent read-back and the child endpoint
     * never diverge, and cascade delete is inherent. {@code readOnly} models Azure's
     * defaultSecurityRules, which exist only as a synthesized read surface.
     */
    @SuppressWarnings("unchecked")
    private Response handleParentArrayChild(AzureRequest request, String method, String sub, String rg,
                                            String tail, String parentSegment, String childSegment,
                                            boolean readOnly) {
        String[] parts = tail.split("[/?]");
        String parentName = parts[1];
        String childName = parts.length > 3 && !parts[3].isEmpty() ? parts[3] : null;

        ArmResourceId parentId = parseId(resourceId(sub, rg, parentSegment + "/" + parentName));
        Map<String, Object> parent = find(parentId).orElse(null);
        if (parent == null) {
            return notFound(parentSegment + "/" + parentName);
        }
        List<Object> entries = childEntries(parent, childSegment);

        if (childName == null) {
            return "GET".equals(method)
                    ? Response.ok(Map.of("value", entries)).build()
                    : Response.status(405).build();
        }

        switch (method) {
            case "GET" -> {
                for (Object item : entries) {
                    Map<String, Object> entry = cast(item);
                    if (childName.equals(entry.get("name"))) {
                        return Response.ok(entry).build();
                    }
                }
                return notFound(tail);
            }
            case "PUT" -> {
                if (readOnly) {
                    return Response.status(405).build();
                }
                Map<String, Object> body = parseBody(request);
                Map<String, Object> entryProps = new LinkedHashMap<>(cast(body.get("properties")));
                entryProps.put("provisioningState", "Succeeded");
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", childName);
                entry.put("id", parent.get("id") + "/" + childSegment + "/" + childName);
                entry.put("properties", entryProps);
                Optional<Stored<Map<String, Object>>> updated = store.update(parentId,
                        current -> withChildEntries(current, childSegment, list -> {
                            list.removeIf(item -> childName.equals(cast(item).get("name")));
                            list.add(entry);
                            return list;
                        }));
                return updated.isPresent() ? Response.ok(entry).build() : notFound(parentSegment + "/" + parentName);
            }
            case "DELETE" -> {
                if (readOnly) {
                    return Response.status(405).build();
                }
                store.update(parentId, current -> withChildEntries(current, childSegment, list -> {
                    list.removeIf(item -> childName.equals(cast(item).get("name")));
                    return list;
                }));
                return Response.ok().build();
            }
            default -> {
                return Response.status(405).build();
            }
        }
    }

    /** A copy of the parent's {@code segment} array, empty when it has none. */
    @SuppressWarnings("unchecked")
    private static List<Object> childEntries(Map<String, Object> parent, String segment) {
        return cast(parent.get("properties")).get(segment) instanceof List<?> l
                ? new ArrayList<>((List<Object>) l)
                : new ArrayList<>();
    }

    /** A copy of {@code parent} whose {@code segment} array is {@code change} applied to its current entries. */
    private static Map<String, Object> withChildEntries(Map<String, Object> parent, String segment,
                                                        UnaryOperator<List<Object>> change) {
        Map<String, Object> properties = new LinkedHashMap<>(cast(parent.get("properties")));
        properties.put(segment, change.apply(childEntries(parent, segment)));
        Map<String, Object> copy = new LinkedHashMap<>(parent);
        copy.put("properties", properties);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static void synthesizeProperties(String resourceType, String name, String id,
                                             Map<String, Object> properties) {
        switch (resourceType) {
            case "Microsoft.Network/loadBalancers" -> {
                // azurerm's flatteners scan every frontend for id + private IP fields, and
                // lb_rule/probe/nat_rule child resources read-modify-write these arrays on
                // the parent, so entries must echo with well-formed ARM ids. Every collection
                // must EXIST even when empty: real Azure always returns them, and azurerm v3
                // child resources deref the collection unguarded (probe_resource panics on a
                // missing probes array).
                stampChildArray(properties, "frontendIPConfigurations", id, entryProps -> {
                    entryProps.putIfAbsent("privateIPAllocationMethod", "Dynamic");
                    entryProps.putIfAbsent("privateIPAddress", "10.0.0.6");
                });
                properties.putIfAbsent("frontendIPConfigurations", new ArrayList<>());
                for (String segment : List.of("backendAddressPools", "loadBalancingRules",
                        "probes", "inboundNatRules", "inboundNatPools", "outboundRules")) {
                    stampChildArray(properties, segment, id, entryProps -> { });
                    properties.putIfAbsent(segment, new ArrayList<>());
                }
            }
            case "Microsoft.Network/networkSecurityGroups" -> {
                stampChildArray(properties, "securityRules", id, entryProps -> { });
                properties.putIfAbsent("securityRules", new ArrayList<>());
                properties.putIfAbsent("defaultSecurityRules", defaultSecurityRules(id));
            }
            case "Microsoft.Network/applicationGateways" -> {
                // azurerm builds child sub-resource ids itself and sends them in the PUT, so
                // echo is usually enough, but its flatteners parse every id and fail the
                // apply on a malformed one, so ids synthesized here must use the exact
                // {agwId}/{segment}/{name} form.
                for (String segment : List.of("gatewayIPConfigurations", "frontendIPConfigurations",
                        "frontendPorts", "httpListeners", "backendAddressPools",
                        "backendHttpSettingsCollection", "requestRoutingRules", "probes",
                        "sslCertificates", "urlPathMaps", "redirectConfigurations")) {
                    stampChildArray(properties, segment, id, entryProps -> { });
                }
                properties.putIfAbsent("operationalState", "Running");
            }
            case "Microsoft.Network/privateLinkServices" -> {
                properties.putIfAbsent("alias",
                        name + "." + UUID.randomUUID() + ".azure.privatelinkservice");
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

    /**
     * Stamps each entry of an inline child array with its ARM id
     * ({@code {parentId}/{segment}/{name}}), a Succeeded provisioningState, and
     * segment-specific property defaults. Existing ids are kept: azurerm sends
     * self-built ids for Application Gateway children and they must echo untouched.
     */
    @SuppressWarnings("unchecked")
    private static void stampChildArray(Map<String, Object> properties, String segment, String parentId,
                                        Consumer<Map<String, Object>> defaults) {
        if (!(properties.get(segment) instanceof List<?> items) || items.isEmpty()) {
            return;
        }
        List<Object> stamped = new ArrayList<>(items.size());
        int index = 0;
        for (Object item : items) {
            index++;
            Map<String, Object> entry = new LinkedHashMap<>(cast(item));
            String name = bodyString(entry, "name", segment + index);
            entry.put("name", name);
            entry.putIfAbsent("id", parentId + "/" + segment + "/" + name);
            Map<String, Object> entryProps = new LinkedHashMap<>(cast(entry.get("properties")));
            defaults.accept(entryProps);
            entryProps.put("provisioningState", "Succeeded");
            entry.put("properties", entryProps);
            stamped.add(entry);
        }
        properties.put(segment, stamped);
    }

    /**
     * The six default rules every real NSG carries, from the Network 2025-01-01 spec's
     * NetworkSecurityGroupGet example. Terraform never reads them; az and the SDKs do.
     */
    private static List<Object> defaultSecurityRules(String nsgId) {
        record Rule(String name, String description, String sourceAddressPrefix,
                    String destinationAddressPrefix, String access, int priority, String direction) { }
        List<Rule> rules = List.of(
                new Rule("AllowVnetInBound", "Allow inbound traffic from all VMs in VNET",
                        "VirtualNetwork", "VirtualNetwork", "Allow", 65000, "Inbound"),
                new Rule("AllowAzureLoadBalancerInBound", "Allow inbound traffic from azure load balancer",
                        "AzureLoadBalancer", "*", "Allow", 65001, "Inbound"),
                new Rule("DenyAllInBound", "Deny all inbound traffic",
                        "*", "*", "Deny", 65500, "Inbound"),
                new Rule("AllowVnetOutBound", "Allow outbound traffic from all VMs to all VMs in VNET",
                        "VirtualNetwork", "VirtualNetwork", "Allow", 65000, "Outbound"),
                new Rule("AllowInternetOutBound", "Allow outbound traffic from all VMs to Internet",
                        "*", "Internet", "Allow", 65001, "Outbound"),
                new Rule("DenyAllOutBound", "Deny all outbound traffic",
                        "*", "*", "Deny", 65500, "Outbound"));

        List<Object> result = new ArrayList<>(rules.size());
        for (Rule rule : rules) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("description", rule.description());
            props.put("protocol", "*");
            props.put("sourcePortRange", "*");
            props.put("destinationPortRange", "*");
            props.put("sourceAddressPrefix", rule.sourceAddressPrefix());
            props.put("destinationAddressPrefix", rule.destinationAddressPrefix());
            props.put("access", rule.access());
            props.put("priority", rule.priority());
            props.put("direction", rule.direction());
            props.put("provisioningState", "Succeeded");
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", rule.name());
            entry.put("id", nsgId + "/defaultSecurityRules/" + rule.name());
            entry.put("properties", props);
            result.add(entry);
        }
        return result;
    }

    /** The first segment of {@code tail}: the top-level resource type it addresses. */
    private static String typeOf(String tail) {
        return tail.split("/", 2)[0];
    }

    /**
     * Whether {@code segment} names the resource type (or record type) {@code type}. ARM type names are
     * case-insensitive, like the store's keys, so every type segment this service routes on is compared here.
     */
    private static boolean isType(String segment, String type) {
        return type.equalsIgnoreCase(segment);
    }

    /** Whether {@code tail} is {@code parent/{name}/child}, or something below it. */
    private static boolean isChildPath(String tail, String parent, String child) {
        String[] parts = tail.split("/");
        return parts.length >= 3 && isType(parts[0], parent) && isType(parts[2], child);
    }

    private static String resourceType(String tail) {
        String[] parts = tail.split("/");
        if (parts.length >= 4 && isType(parts[2], "subnets")) {
            return "Microsoft.Network/virtualNetworks/subnets";
        }
        return "Microsoft.Network/" + parts[0];
    }

    private static String resourceName(String tail) {
        String[] parts = tail.split("[/?]");
        return parts.length > 0 ? parts[parts.length - 1] : tail;
    }

    /** The ARM id of {@code tail} (the path after {@code /providers/Microsoft.Network/}) in the resource group. */
    private static String resourceId(String sub, String rg, String tail) {
        int q = tail.indexOf('?');
        String clean = q >= 0 ? tail.substring(0, q) : tail;
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + PROVIDER + clean;
    }

    private static ArmResourceId parseId(String id) {
        return ArmResourceId.parse(id).orElseThrow(() -> new IllegalArgumentException("not an ARM id: " + id));
    }

    private Optional<Map<String, Object>> find(ArmResourceId id) {
        return store.get(id).map(Stored::value);
    }

    private Optional<Map<String, Object>> find(String id) {
        return find(parseId(id));
    }

    /** The resources under {@code parentId} (a resource or one of its collections), at any depth. */
    private List<Map<String, Object>> descendants(String sub, String rg, String parentId) {
        String prefix = parseId(parentId).canonicalKey() + "/";
        return store.list(new ArmScope(sub, rg)).stream()
                .filter(stored -> stored.id().canonicalKey().startsWith(prefix))
                .map(Stored::value)
                .toList();
    }

    /** {@link #descendants}, as responses. */
    private List<Map<String, Object>> children(String sub, String rg, String parentId) {
        return descendants(sub, rg, parentId).stream().map(NetworkService::stripInternal).toList();
    }

    private void deleteUnder(String sub, String rg, String parentId) {
        for (Map<String, Object> child : descendants(sub, rg, parentId)) {
            store.delete(parseId(String.valueOf(child.get("id"))));
        }
    }

    /** The stored resource's {@code field}, so an update through other casing keeps the casing of the first PUT. */
    private static String firstCasing(Optional<Map<String, Object>> existing, String field, String requested) {
        return existing.map(e -> e.get(field)).filter(String.class::isInstance).map(String.class::cast)
                .orElse(requested);
    }

    private static Response ok(Map<String, Object> resource) {
        return Response.ok(stripInternal(resource)).build();
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

    /** The subscription's DNS zones, or one resource group's when {@code rg} is non-null. */
    private List<Map<String, Object>> listDnsZones(String sub, String rg) {
        return listResources(sub, rg, "Microsoft.Network/dnsZones");
    }

    private static String dnsZoneId(String sub, String rg, String zoneName) {
        return resourceId(sub, rg, "dnsZones/" + zoneName);
    }

    private Response getDnsZone(String sub, String rg, String zoneName) {
        return find(dnsZoneId(sub, rg, zoneName)).map(NetworkService::ok)
                .orElseGet(() -> notFound("dnsZones/" + zoneName));
    }

    private Response createOrUpdateDnsZone(AzureRequest request, String sub, String rg, String zoneName) {
        Map<String, Object> body = parseBody(request);
        Written<Map<String, Object>> written = store.createOrUpdate(parseId(dnsZoneId(sub, rg, zoneName)), existing -> {
            Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
            Map<String, Object> existingZone = existing.orElse(null);
            String etag = existingZone != null ? (String) existingZone.get("etag") : "\"" + UUID.randomUUID() + "\"";
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
            zone.put("id", firstCasing(existing, "id", dnsZoneId(sub, rg, zoneName)));
            zone.put("name", firstCasing(existing, "name", zoneName));
            zone.put("type", "Microsoft.Network/dnsZones");
            zone.put("location", bodyString(body, "location", "global"));
            if (body.get("tags") instanceof Map<?, ?> tags && !tags.isEmpty()) {
                zone.put("tags", tags);
            } else if (existingZone != null && existingZone.containsKey("tags")) {
                zone.put("tags", existingZone.get("tags"));
            }
            zone.put("etag", etag);
            zone.put("properties", properties);
            return zone;
        });

        Map<String, Object> zone = written.stored().value();
        if (written.created()) {
            createDefaultRecordSets(String.valueOf(zone.get("id")), String.valueOf(zone.get("name")));
        }

        return Response.status(written.created() ? 201 : 200).entity(stripInternal(zone)).build();
    }

    private void createDefaultRecordSets(String zoneId, String zoneName) {
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
        soa.put("id", zoneId + "/SOA/@");
        soa.put("name", "@");
        soa.put("type", "Microsoft.Network/dnsZones/SOA");
        soa.put("etag", "\"" + UUID.randomUUID() + "\"");
        soa.put("properties", soaProperties);
        store.put(soa);

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
        ns.put("id", zoneId + "/NS/@");
        ns.put("name", "@");
        ns.put("type", "Microsoft.Network/dnsZones/NS");
        ns.put("etag", "\"" + UUID.randomUUID() + "\"");
        ns.put("properties", nsProperties);
        store.put(ns);
    }

    private Response deleteDnsZone(String sub, String rg, String zoneName) {
        String zoneId = dnsZoneId(sub, rg, zoneName);
        if (store.delete(parseId(zoneId)).isEmpty()) {
            return notFound("dnsZones/" + zoneName);
        }
        deleteUnder(sub, rg, zoneId);
        return Response.ok().build();
    }

    private List<Map<String, Object>> listAllRecordSets(String sub, String rg, String zoneName) {
        return children(sub, rg, dnsZoneId(sub, rg, zoneName));
    }

    private List<Map<String, Object>> listRecordSetsByType(String sub, String rg, String zoneName, String recordType) {
        return children(sub, rg, dnsZoneId(sub, rg, zoneName) + "/" + recordType);
    }

    private static String recordSetId(String sub, String rg, String zoneName, String recordType,
                                      String relativeRecordSetName) {
        return dnsZoneId(sub, rg, zoneName) + "/" + recordType + "/" + relativeRecordSetName;
    }

    private Response getRecordSet(String sub, String rg, String zoneName, String recordType, String relativeRecordSetName) {
        return find(recordSetId(sub, rg, zoneName, recordType, relativeRecordSetName)).map(NetworkService::ok)
                .orElseGet(() -> notFound("dnsZones/" + zoneName + "/" + recordType + "/" + relativeRecordSetName));
    }

    private Response deleteRecordSet(String sub, String rg, String zoneName, String recordType, String relativeRecordSetName) {
        if ("@".equals(relativeRecordSetName) && (isType(recordType, "SOA") || isType(recordType, "NS"))) {
            return Response.status(400).entity(Map.of("error", Map.of(
                    "code", "BadRequest",
                    "message", "Default SOA and NS record sets at the zone root cannot be deleted."
            ))).build();
        }

        String id = recordSetId(sub, rg, zoneName, recordType, relativeRecordSetName);
        if (store.delete(parseId(id)).isEmpty()) {
            return notFound("dnsZones/" + zoneName + "/" + recordType + "/" + relativeRecordSetName);
        }

        adjustZoneCount(dnsZoneId(sub, rg, zoneName), "numberOfRecordSets", -1, 2, 2);

        return Response.ok().build();
    }

    private Response createOrUpdateRecordSet(AzureRequest request, String sub, String rg, String zoneName, String recordType, String relativeRecordSetName) {
        if (find(dnsZoneId(sub, rg, zoneName)).isEmpty()) {
            return Response.status(404).entity(Map.of("error", Map.of(
                    "code", "ParentResourceNotFound",
                    "message", "DNS Zone " + zoneName + " was not found."
            ))).build();
        }

        ArmResourceId id = parseId(recordSetId(sub, rg, zoneName, recordType, relativeRecordSetName));
        Map<String, Object> existingRecordSet = find(id).orElse(null);

        Response precondition = checkPreconditions(request, existingRecordSet);
        if (precondition != null) {
            return precondition;
        }

        Map<String, Object> properties = recordSetProperties(parseBody(request), zoneName, recordType,
                relativeRecordSetName);
        Written<Map<String, Object>> written = store.createOrUpdate(id, existing -> {
            Map<String, Object> recordSet = new LinkedHashMap<>();
            recordSet.put("id", firstCasing(existing, "id", id.id()));
            recordSet.put("name", firstCasing(existing, "name", relativeRecordSetName));
            recordSet.put("type", firstCasing(existing, "type", "Microsoft.Network/dnsZones/" + recordType));
            recordSet.put("etag", "\"" + UUID.randomUUID() + "\"");
            recordSet.put("properties", properties);
            return recordSet;
        });

        if (written.created()) {
            adjustZoneCount(dnsZoneId(sub, rg, zoneName), "numberOfRecordSets", 1, 2, 2);
        }

        return Response.status(written.created() ? 201 : 200).entity(stripInternal(written.stored().value())).build();
    }

    /** A record set's {@code properties} from a PUT body: its fqdn, TTL, metadata and the records of its type. */
    private static Map<String, Object> recordSetProperties(Map<String, Object> body, String zoneName,
                                                           String recordType, String relativeRecordSetName) {
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
        return properties;
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

    /**
     * Adds {@code delta} to a zone's {@code field} count, never below {@code floor}; a zone without the field counts
     * from {@code missing}. A zone that is gone is left alone.
     */
    private void adjustZoneCount(String zoneId, String field, int delta, int floor, int missing) {
        store.update(parseId(zoneId), zone -> {
            Map<String, Object> props = new LinkedHashMap<>(cast(zone.get("properties")));
            int count = ((Number) props.getOrDefault(field, missing)).intValue();
            props.put(field, Math.max(floor, count + delta));
            Map<String, Object> copy = new LinkedHashMap<>(zone);
            copy.put("properties", props);
            return copy;
        });
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
            return Response.ok(Map.of("value", listPrivateDnsZones(sub, rg)))
                    .build();
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
        if (parts.length == 3 && isType(parts[2], "virtualNetworkLinks")) {
            return Response.ok(Map.of("value", listVirtualNetworkLinks(sub, rg, parts[1]))).build();
        }

        // 4. Virtual network link CRUD
        if (parts.length == 4 && isType(parts[2], "virtualNetworkLinks")) {
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

    private static String privateZoneId(String sub, String rg, String zoneName) {
        return resourceId(sub, rg, "privateDnsZones/" + zoneName);
    }

    /** The subscription's private DNS zones, or one resource group's when {@code rg} is non-null. */
    private List<Map<String, Object>> listPrivateDnsZones(String sub, String rg) {
        return listResources(sub, rg, "Microsoft.Network/privateDnsZones");
    }

    private Response getPrivateDnsZone(String sub, String rg, String zoneName) {
        return find(privateZoneId(sub, rg, zoneName)).map(NetworkService::ok)
                .orElseGet(() -> notFound("privateDnsZones/" + zoneName));
    }

    private Response createOrUpdatePrivateDnsZone(AzureRequest request, String sub, String rg, String zoneName) {
        String zoneId = privateZoneId(sub, rg, zoneName);
        Map<String, Object> body = parseBody(request);
        Written<Map<String, Object>> written = store.createOrUpdate(parseId(zoneId), existing -> {
            Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
            Map<String, Object> existingZone = existing.orElse(null);
            // Azure rotates the ETag on every mutation (as record sets and VNet links do here).
            String etag = "\"" + UUID.randomUUID() + "\"";
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
            zone.put("id", firstCasing(existing, "id", zoneId));
            zone.put("name", firstCasing(existing, "name", zoneName));
            zone.put("type", "Microsoft.Network/privateDnsZones");
            zone.put("location", bodyString(body, "location", "global"));
            if (body.get("tags") instanceof Map<?, ?> tags && !tags.isEmpty()) {
                zone.put("tags", tags);
            } else if (existingZone != null && existingZone.containsKey("tags")) {
                zone.put("tags", existingZone.get("tags"));
            }
            zone.put("etag", etag);
            zone.put("properties", properties);
            return zone;
        });

        Map<String, Object> zone = written.stored().value();
        if (written.created()) {
            createDefaultPrivateSoaRecordSet(String.valueOf(zone.get("id")), String.valueOf(zone.get("name")));
        }

        return Response.status(written.created() ? 201 : 200).entity(stripInternal(zone)).build();
    }

    private void createDefaultPrivateSoaRecordSet(String zoneId, String zoneName) {
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
        soa.put("id", zoneId + "/SOA/@");
        soa.put("name", "@");
        soa.put("type", "Microsoft.Network/privateDnsZones/SOA");
        soa.put("etag", "\"" + UUID.randomUUID() + "\"");
        soa.put("properties", soaProperties);
        store.put(soa);
    }

    private Response deletePrivateDnsZone(String sub, String rg, String zoneName) {
        String zoneId = privateZoneId(sub, rg, zoneName);
        if (store.delete(parseId(zoneId)).isEmpty()) {
            return notFound("privateDnsZones/" + zoneName);
        }
        deleteUnder(sub, rg, zoneId);
        return Response.ok().build();
    }

    private List<Map<String, Object>> listAllPrivateRecordSets(String sub, String rg, String zoneName) {
        String zoneId = privateZoneId(sub, rg, zoneName);
        String links = parseId(zoneId + "/virtualNetworkLinks").canonicalKey() + "/";
        return descendants(sub, rg, zoneId).stream()
                .filter(r -> !String.valueOf(r.get("id")).toLowerCase(Locale.ROOT).startsWith(links))
                .map(NetworkService::stripInternal)
                .toList();
    }

    private List<Map<String, Object>> listPrivateRecordSetsByType(String sub, String rg, String zoneName, String recordType) {
        return children(sub, rg, privateZoneId(sub, rg, zoneName) + "/" + recordType);
    }

    private static String privateRecordSetId(String sub, String rg, String zoneName, String recordType,
                                             String relativeRecordSetName) {
        return privateZoneId(sub, rg, zoneName) + "/" + recordType + "/" + relativeRecordSetName;
    }

    private Response getPrivateRecordSet(String sub, String rg, String zoneName, String recordType, String relativeRecordSetName) {
        return find(privateRecordSetId(sub, rg, zoneName, recordType, relativeRecordSetName)).map(NetworkService::ok)
                .orElseGet(() -> notFound("privateDnsZones/" + zoneName + "/" + recordType + "/" + relativeRecordSetName));
    }

    private Response deletePrivateRecordSet(String sub, String rg, String zoneName, String recordType, String relativeRecordSetName) {
        if ("@".equals(relativeRecordSetName) && isType(recordType, "SOA")) {
            return Response.status(400).entity(Map.of("error", Map.of(
                    "code", "BadRequest",
                    "message", "The default SOA record set at the zone root cannot be deleted."
            ))).build();
        }

        String id = privateRecordSetId(sub, rg, zoneName, recordType, relativeRecordSetName);
        if (store.delete(parseId(id)).isEmpty()) {
            return notFound("privateDnsZones/" + zoneName + "/" + recordType + "/" + relativeRecordSetName);
        }

        adjustZoneCount(privateZoneId(sub, rg, zoneName), "numberOfRecordSets", -1, 1, 1);

        return Response.ok().build();
    }

    private Response createOrUpdatePrivateRecordSet(AzureRequest request, String sub, String rg, String zoneName, String recordType, String relativeRecordSetName) {
        if (find(privateZoneId(sub, rg, zoneName)).isEmpty()) {
            return Response.status(404).entity(Map.of("error", Map.of(
                    "code", "ParentResourceNotFound",
                    "message", "Private DNS Zone " + zoneName + " was not found."
            ))).build();
        }

        ArmResourceId id = parseId(privateRecordSetId(sub, rg, zoneName, recordType, relativeRecordSetName));
        Map<String, Object> existingRecordSet = find(id).orElse(null);

        Response precondition = checkPreconditions(request, existingRecordSet);
        if (precondition != null) {
            return precondition;
        }

        Map<String, Object> properties = recordSetProperties(parseBody(request), zoneName, recordType,
                relativeRecordSetName);
        Written<Map<String, Object>> written = store.createOrUpdate(id, existing -> {
            Map<String, Object> recordSet = new LinkedHashMap<>();
            recordSet.put("id", firstCasing(existing, "id", id.id()));
            recordSet.put("name", firstCasing(existing, "name", relativeRecordSetName));
            recordSet.put("type", firstCasing(existing, "type", "Microsoft.Network/privateDnsZones/" + recordType));
            recordSet.put("etag", "\"" + UUID.randomUUID() + "\"");
            recordSet.put("properties", properties);
            return recordSet;
        });

        if (written.created()) {
            adjustZoneCount(privateZoneId(sub, rg, zoneName), "numberOfRecordSets", 1, 1, 1);
        }

        return Response.status(written.created() ? 201 : 200).entity(stripInternal(written.stored().value())).build();
    }

    private List<Map<String, Object>> listVirtualNetworkLinks(String sub, String rg, String zoneName) {
        return children(sub, rg, privateZoneId(sub, rg, zoneName) + "/virtualNetworkLinks");
    }

    private static String virtualNetworkLinkId(String sub, String rg, String zoneName, String linkName) {
        return privateZoneId(sub, rg, zoneName) + "/virtualNetworkLinks/" + linkName;
    }

    private Response getVirtualNetworkLink(String sub, String rg, String zoneName, String linkName) {
        return find(virtualNetworkLinkId(sub, rg, zoneName, linkName)).map(NetworkService::ok)
                .orElseGet(() -> notFound("privateDnsZones/" + zoneName + "/virtualNetworkLinks/" + linkName));
    }

    private Response createOrUpdateVirtualNetworkLink(AzureRequest request, String sub, String rg, String zoneName, String linkName) {
        if (find(privateZoneId(sub, rg, zoneName)).isEmpty()) {
            return Response.status(404).entity(Map.of("error", Map.of(
                    "code", "ParentResourceNotFound",
                    "message", "Private DNS Zone " + zoneName + " was not found."
            ))).build();
        }

        ArmResourceId id = parseId(virtualNetworkLinkId(sub, rg, zoneName, linkName));
        Map<String, Object> body = parseBody(request);
        Map<String, Object> bodyProps = cast(body.get("properties"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("provisioningState", "Succeeded");
        properties.put("registrationEnabled", bodyProps.getOrDefault("registrationEnabled", false));
        if (bodyProps.get("virtualNetwork") instanceof Map<?, ?> vnet) {
            properties.put("virtualNetwork", vnet);
        }
        properties.put("virtualNetworkLinkState", "Completed");

        Written<Map<String, Object>> written = store.createOrUpdate(id, existing -> {
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("id", firstCasing(existing, "id", id.id()));
            link.put("name", firstCasing(existing, "name", linkName));
            link.put("type", "Microsoft.Network/privateDnsZones/virtualNetworkLinks");
            link.put("location", bodyString(body, "location", "global"));
            if (body.get("tags") instanceof Map<?, ?> tags && !tags.isEmpty()) {
                link.put("tags", tags);
            }
            link.put("etag", "\"" + UUID.randomUUID() + "\"");
            link.put("properties", properties);
            return link;
        });

        if (written.created()) {
            adjustZoneCount(privateZoneId(sub, rg, zoneName), "numberOfVirtualNetworkLinks", 1, 0, 0);
        }

        return Response.status(written.created() ? 201 : 200).entity(stripInternal(written.stored().value())).build();
    }

    private Response deleteVirtualNetworkLink(String sub, String rg, String zoneName, String linkName) {
        if (store.delete(parseId(virtualNetworkLinkId(sub, rg, zoneName, linkName))).isEmpty()) {
            return notFound("privateDnsZones/" + zoneName + "/virtualNetworkLinks/" + linkName);
        }
        adjustZoneCount(privateZoneId(sub, rg, zoneName), "numberOfVirtualNetworkLinks", -1, 0, 0);
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
                case "GET"    -> find(privateEndpointId(sub, rg, peName)).map(NetworkService::ok)
                        .orElseGet(() -> notFound("privateEndpoints/" + peName));
                case "DELETE" -> deletePrivateEndpoint(sub, rg, peName);
                default       -> Response.status(405).build();
            };
        }

        // 2. List private DNS zone groups
        if (parts.length == 3 && isType(parts[2], "privateDnsZoneGroups")) {
            return Response.ok(Map.of("value", listPrivateDnsZoneGroups(sub, rg, parts[1]))).build();
        }

        // 3. Private DNS zone group CRUD
        if (parts.length == 4 && isType(parts[2], "privateDnsZoneGroups")) {
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

    private static String privateEndpointId(String sub, String rg, String peName) {
        return resourceId(sub, rg, "privateEndpoints/" + peName);
    }

    private Response createOrUpdatePrivateEndpoint(AzureRequest request, String sub, String rg, String peName) {
        String requestedId = privateEndpointId(sub, rg, peName);
        Map<String, Object> body = parseBody(request);

        // The NIC is written inside the endpoint's write, so a concurrent PUT of the same endpoint cannot
        // synthesize a second one: the store's writes are atomic with respect to each other.
        Written<Map<String, Object>> written = store.createOrUpdate(parseId(requestedId), existing -> {
            String peId = firstCasing(existing, "id", requestedId);
            Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));

            List<Object> connections = normalizeConnections(peId, properties.get("privateLinkServiceConnections"), false);
            List<Object> manualConnections = normalizeConnections(peId, properties.get("manualPrivateLinkServiceConnections"), true);
            properties.put("privateLinkServiceConnections", connections);
            properties.put("manualPrivateLinkServiceConnections", manualConnections);
            properties.putIfAbsent("customDnsConfigs", List.of());

            properties.put("networkInterfaces", List.of(new LinkedHashMap<>(Map.of("id",
                    endpointNic(sub, rg, peName, existing, bodyString(properties, "customNetworkInterfaceName", null))))));
            properties.put("provisioningState", "Succeeded");

            Map<String, Object> pe = new LinkedHashMap<>();
            pe.put("id", peId);
            pe.put("name", firstCasing(existing, "name", peName));
            pe.put("type", "Microsoft.Network/privateEndpoints");
            String location = bodyString(body, "location", null);
            if (location != null) {
                pe.put("location", location);
            }
            if (body.get("tags") instanceof Map<?, ?> tags && !tags.isEmpty()) {
                pe.put("tags", tags);
            }
            pe.put("properties", properties);
            return pe;
        });
        return Response.status(written.created() ? 201 : 200).entity(stripInternal(written.stored().value())).build();
    }

    /**
     * The id of the NIC a private endpoint uses. The synthesized NIC is stable for the endpoint's lifetime (as in
     * Azure), so an update reuses the existing one rather than minting a new randomly-named NIC on every PUT, which
     * would leak prior NICs past the endpoint's cascade delete. A NIC is synthesized only on first create or when an
     * explicit {@code customNetworkInterfaceName} changes it, removing the NIC it replaces.
     */
    private String endpointNic(String sub, String rg, String peName, Optional<Map<String, Object>> existingPe,
                               String customNicName) {
        Optional<String> existingNicId = existingPe.flatMap(NetworkService::endpointNicId);
        Optional<Map<String, Object>> existingNic = existingNicId.flatMap(this::find);
        if (existingNic.isPresent() && (customNicName == null || parseId(existingNicId.get()).canonicalKey()
                .equals(parseId(resourceId(sub, rg, "networkInterfaces/" + customNicName)).canonicalKey()))) {
            return String.valueOf(existingNic.get().get("id"));
        }
        String nicName = customNicName != null ? customNicName
                : peName + ".nic." + UUID.randomUUID().toString().substring(0, 8);
        String nicId = resourceId(sub, rg, "networkInterfaces/" + nicName);
        Map<String, Object> nicProps = new LinkedHashMap<>();
        synthesizeProperties("Microsoft.Network/networkInterfaces", nicName, nicId, nicProps);
        nicProps.put("provisioningState", "Succeeded");
        Map<String, Object> nic = new LinkedHashMap<>();
        nic.put("id", nicId);
        nic.put("name", nicName);
        nic.put("type", "Microsoft.Network/networkInterfaces");
        nic.put("properties", nicProps);
        store.put(nic);
        String nicKey = parseId(nicId).canonicalKey();
        existingNicId.filter(previous -> !parseId(previous).canonicalKey().equals(nicKey))
                .ifPresent(previous -> store.delete(parseId(previous)));
        return nicId;
    }

    /** The id of the NIC a stored private endpoint references, from its {@code properties.networkInterfaces}. */
    private static Optional<String> endpointNicId(Map<String, Object> pe) {
        if (cast(pe.get("properties")).get("networkInterfaces") instanceof List<?> nics && !nics.isEmpty()
                && cast(nics.getFirst()).get("id") instanceof String id) {
            return Optional.of(id);
        }
        return Optional.empty();
    }

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
        String peId = privateEndpointId(sub, rg, peName);
        Optional<Stored<Map<String, Object>>> pe = store.delete(parseId(peId));
        if (pe.isEmpty()) {
            return notFound("privateEndpoints/" + peName);
        }
        endpointNicId(pe.get().value()).ifPresent(nicId -> store.delete(parseId(nicId)));
        deleteUnder(sub, rg, peId);
        return Response.ok().build();
    }

    private List<Map<String, Object>> listPrivateDnsZoneGroups(String sub, String rg, String peName) {
        return children(sub, rg, privateEndpointId(sub, rg, peName) + "/privateDnsZoneGroups");
    }

    private static String privateDnsZoneGroupId(String sub, String rg, String peName, String groupName) {
        return privateEndpointId(sub, rg, peName) + "/privateDnsZoneGroups/" + groupName;
    }

    private Response getPrivateDnsZoneGroup(String sub, String rg, String peName, String groupName) {
        return find(privateDnsZoneGroupId(sub, rg, peName, groupName)).map(NetworkService::ok)
                .orElseGet(() -> notFound("privateEndpoints/" + peName + "/privateDnsZoneGroups/" + groupName));
    }

    private Response createOrUpdatePrivateDnsZoneGroup(AzureRequest request, String sub, String rg, String peName, String groupName) {
        if (find(privateEndpointId(sub, rg, peName)).isEmpty()) {
            return Response.status(404).entity(Map.of("error", Map.of(
                    "code", "ParentResourceNotFound",
                    "message", "Private Endpoint " + peName + " was not found."
            ))).build();
        }

        ArmResourceId id = parseId(privateDnsZoneGroupId(sub, rg, peName, groupName));
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

        Written<Map<String, Object>> written = store.createOrUpdate(id, existing -> {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("id", firstCasing(existing, "id", id.id()));
            group.put("name", firstCasing(existing, "name", groupName));
            group.put("type", "Microsoft.Network/privateEndpoints/privateDnsZoneGroups");
            group.put("etag", "\"" + UUID.randomUUID() + "\"");
            group.put("properties", properties);
            return group;
        });
        return Response.status(written.created() ? 201 : 200).entity(stripInternal(written.stored().value())).build();
    }

    private Response deletePrivateDnsZoneGroup(String sub, String rg, String peName, String groupName) {
        if (store.delete(parseId(privateDnsZoneGroupId(sub, rg, peName, groupName))).isEmpty()) {
            return notFound("privateEndpoints/" + peName + "/privateDnsZoneGroups/" + groupName);
        }
        return Response.ok().build();
    }
}
