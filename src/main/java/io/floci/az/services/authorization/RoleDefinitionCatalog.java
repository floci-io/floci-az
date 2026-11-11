package io.floci.az.services.authorization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Azure's built-in role definitions, read from {@code azure/role-definitions.json}, which {@code tools/arm/gen_roles.py}
 * generates from iam-dataset's copy of the Azure API's own list. Every GUID, name and permission is Azure's; nothing
 * here is typed by hand.
 */
final class RoleDefinitionCatalog {

    static final String RESOURCE = "azure/role-definitions.json";

    /** One permission set of a role: the action lists, and the condition Azure reports from 2022-05-01-preview. */
    record Permission(List<String> actions, List<String> notActions, List<String> dataActions,
                      List<String> notDataActions, String condition, String conditionVersion) {
        Permission {
            actions = List.copyOf(actions);
            notActions = List.copyOf(notActions);
            dataActions = List.copyOf(dataActions);
            notDataActions = List.copyOf(notDataActions);
        }
    }

    /**
     * One built-in role definition.
     *
     * @param name the role's GUID, which is also the last segment of its id
     */
    record RoleDefinition(String name, String roleName, String description, String roleType,
                          List<String> assignableScopes, List<Permission> permissions) {
        RoleDefinition {
            assignableScopes = List.copyOf(assignableScopes);
            permissions = List.copyOf(permissions);
        }
    }

    private RoleDefinitionCatalog() {
    }

    /** Every built-in role, ordered by GUID. */
    static List<RoleDefinition> all() {
        return Bundled.ROLES;
    }

    /** The role whose GUID is {@code guid}, compared ignoring case. */
    static Optional<RoleDefinition> byGuid(String guid) {
        return guid == null ? Optional.empty()
                : Optional.ofNullable(Bundled.BY_GUID.get(guid.toLowerCase(Locale.ROOT)));
    }

    static List<RoleDefinition> parse(InputStream in) throws IOException {
        JsonNode roles = new ObjectMapper().readTree(in).path("roleDefinitions");
        if (!roles.isArray() || roles.isEmpty()) {
            throw new IllegalStateException(RESOURCE + ": 'roleDefinitions' must be a non-empty list");
        }
        List<RoleDefinition> parsed = new ArrayList<>();
        for (JsonNode role : roles) {
            List<Permission> permissions = new ArrayList<>();
            for (JsonNode permission : role.path("permissions")) {
                permissions.add(new Permission(strings(permission.path("actions")),
                        strings(permission.path("notActions")), strings(permission.path("dataActions")),
                        strings(permission.path("notDataActions")), text(permission.path("condition")),
                        text(permission.path("conditionVersion"))));
            }
            String name = text(role.path("name"));
            String roleName = text(role.path("roleName"));
            if (name == null || roleName == null) {
                throw new IllegalStateException(RESOURCE + ": malformed role definition " + role);
            }
            parsed.add(new RoleDefinition(name, roleName, text(role.path("description")), text(role.path("roleType")),
                    strings(role.path("assignableScopes")), permissions));
        }
        return List.copyOf(parsed);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static String text(JsonNode value) {
        return value.isTextual() ? value.asText() : null;
    }

    /** Lazy holder: the bundled file is read on first use, once. */
    private static final class Bundled {

        static final List<RoleDefinition> ROLES = load();
        static final Map<String, RoleDefinition> BY_GUID = index();

        private static List<RoleDefinition> load() {
            try (InputStream in = RoleDefinitionCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException(RESOURCE + " is not on the classpath");
                }
                return parse(in);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + RESOURCE + ": " + e.getMessage(), e);
            }
        }

        private static Map<String, RoleDefinition> index() {
            Map<String, RoleDefinition> byGuid = new LinkedHashMap<>();
            for (RoleDefinition role : ROLES) {
                byGuid.put(role.name().toLowerCase(Locale.ROOT), role);
            }
            return byGuid;
        }
    }
}
