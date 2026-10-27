package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The public startup seed format, translated into directory objects before anything is written. */
final class DirectorySeed {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DirectorySeed() {
    }

    static void load(Path file, String defaultTenant, GraphDirectoryStore store) {
        JsonNode root;
        try {
            root = MAPPER.readTree(file.toFile());
        } catch (IOException invalidSeed) {
            // Parser context can include a client secret, so keep it out of the startup exception.
            throw new IllegalArgumentException("Cannot read or parse Entra directory seed file: " + file);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Entra directory seed must be a JSON object.");
        }
        fields(root, Set.of("tenantId", "applications", "users", "groups"));
        String tenant = root.has("tenantId") ? required(root, "tenantId") : defaultTenant;
        List<DirectoryObject> objects = new ArrayList<>();
        Map<String, List<String>> memberships = new LinkedHashMap<>();
        for (JsonNode application : array(root, "applications")) {
            fields(application, Set.of("id", "appId", "displayName", "clientSecret"));
            String appId = required(application, "appId");
            List<ObjectNode> credentials = new ArrayList<>();
            if (application.has("clientSecret")) {
                ObjectNode credential = MAPPER.createObjectNode();
                credential.put("keyId", UUID.nameUUIDFromBytes(appId.getBytes(StandardCharsets.UTF_8)).toString());
                credential.put(GraphDirectoryStore.SECRET_TEXT, required(application, "clientSecret"));
                credentials.add(credential);
            }
            objects.add(GraphDirectoryStore.application(tenant, id(application), appId,
                    optional(application, "displayName", appId), credentials));
        }
        for (JsonNode user : array(root, "users")) {
            fields(user, Set.of("id", "userPrincipalName", "displayName", "mail"));
            String upn = required(user, "userPrincipalName");
            objects.add(GraphDirectoryStore.user(tenant, id(user), upn,
                    optional(user, "displayName", upn), optional(user, "mail", null)));
        }
        for (JsonNode group : array(root, "groups")) {
            fields(group, Set.of("id", "displayName", "securityEnabled", "members"));
            String id = id(group);
            if (group.has("securityEnabled") && !group.path("securityEnabled").isBoolean()) {
                throw new IllegalArgumentException("Seed group securityEnabled must be a boolean.");
            }
            objects.add(GraphDirectoryStore.group(tenant, id, required(group, "displayName"),
                    group.path("securityEnabled").asBoolean(true)));
            List<String> members = new ArrayList<>();
            for (JsonNode member : array(group, "members")) {
                if (!member.isTextual() || member.asText().isBlank()) {
                    throw new IllegalArgumentException("Seed group members must contain object ids.");
                }
                members.add(member.asText());
            }
            memberships.put(id, members);
        }
        validate(objects, memberships, tenant, store);
        for (DirectoryObject object : objects) {
            if (store.get(tenant, set(object), object.id()).isEmpty()) {
                store.put(object);
            }
        }
        memberships.forEach((group, members) -> members.forEach(member ->
                store.addLink(tenant, group, GraphDirectoryStore.MEMBERS, member)));
    }

    private static void validate(List<DirectoryObject> objects, Map<String, List<String>> memberships,
                                 String tenant, GraphDirectoryStore store) {
        Map<String, DirectoryObject> byId = new HashMap<>();
        Set<String> alternateKeys = new HashSet<>();
        for (DirectoryObject object : objects) {
            if (byId.putIfAbsent(object.id(), object) != null) {
                throw new IllegalArgumentException("Directory seed contains a duplicate object id.");
            }
            DirectoryEntitySet set = set(object);
            for (DirectoryEntitySet other : DirectoryEntitySet.values()) {
                if (other != set && store.get(tenant, other, object.id()).isPresent()) {
                    throw new IllegalArgumentException("Seed object id belongs to another directory entity set.");
                }
            }
            for (String key : set.alternateKeys()) {
                String value = object.text(key);
                if (!alternateKeys.add(set.setName() + ":" + value.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Directory seed contains a duplicate alternate key.");
                }
                DirectoryObject indexed = store.findByAlternateKey(tenant, set, key, value).orElse(null);
                DirectoryObject existing = store.get(tenant, set, object.id()).orElse(null);
                if ((indexed != null && !indexed.id().equals(object.id()))
                        || (existing != null && !value.equalsIgnoreCase(existing.text(key)))) {
                    throw new IllegalArgumentException("Directory seed alternate key conflicts with an existing object.");
                }
            }
        }
        for (List<String> members : memberships.values()) {
            for (String member : members) {
                DirectoryObject planned = byId.get(member);
                boolean plannedMember = planned != null && (set(planned) == DirectoryEntitySet.USERS
                        || set(planned) == DirectoryEntitySet.GROUPS);
                boolean existingMember = store.get(tenant, DirectoryEntitySet.USERS, member).isPresent()
                        || store.get(tenant, DirectoryEntitySet.GROUPS, member).isPresent();
                if (!plannedMember && !existingMember) {
                    throw new IllegalArgumentException("Seed member must identify a user or group in the seed tenant.");
                }
            }
        }
    }

    private static DirectoryEntitySet set(DirectoryObject object) {
        return switch (object.entitySet()) {
            case "users" -> DirectoryEntitySet.USERS;
            case "groups" -> DirectoryEntitySet.GROUPS;
            case "applications" -> DirectoryEntitySet.APPLICATIONS;
            default -> throw new IllegalArgumentException("Unsupported seed entity set.");
        };
    }

    private static void fields(JsonNode object, Set<String> supported) {
        if (!object.isObject() || object.properties().stream().anyMatch(field -> !supported.contains(field.getKey()))) {
            throw new IllegalArgumentException("Directory seed object contains an unsupported field or shape.");
        }
    }

    private static JsonNode array(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null) {
            return MAPPER.createArrayNode();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("Directory seed " + field + " must be an array.");
        }
        return value;
    }

    private static String id(JsonNode object) {
        String id = required(object, "id");
        if (!id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Seed ids may contain only letters, digits, periods, underscores and hyphens.");
        }
        return id;
    }

    private static String required(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Directory seed requires a non-empty string: " + field);
        }
        return value.asText();
    }

    private static String optional(JsonNode object, String field, String fallback) {
        return object.has(field) && !object.path(field).isNull() ? required(object, field) : fallback;
    }
}
