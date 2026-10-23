package io.floci.az.services.policy;

import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmResourceStore.Written;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.policy.PolicyPath.Type;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * ARM resource state for the Microsoft.Authorization policy resources: definitions, set definitions, assignments and
 * exemptions. Each type has its own {@link ArmResourceStore} over the one {@code policy} storage backend, keyed by the
 * canonical (lowercased) resource id, so it follows the global storage mode and lookups are scope- and
 * name-insensitive the way ARM itself is. The resources were kept in memory before, so no older key shape exists on
 * disk and no key is legacy.
 *
 * <p>Policy resources live at management-group, subscription, resource-group and resource scope, so the listings
 * filter {@link #list(Type)} on the stored {@code _scope} rather than going through
 * {@link ArmResourceStore#list}, which only knows subscriptions and resource groups.
 *
 * <p>A lookup takes an id as a client wrote it in a reference ({@code policyDefinitionId},
 * {@code policyAssignmentId}): only an absolute id that names a resource of the store's own type addresses one, so a
 * relative reference, another provider's id or a collection path stays missing as it always has, and a set
 * definition's id never finds a definition in the shared backend. All access goes through methods: callers hold a
 * normal-scoped client proxy.
 */
@ApplicationScoped
public class PolicyStore {

    static final String STORE = "policy";

    /** What a create-or-update wrote, and whether the resource was new. */
    public record Write(Map<String, Object> resource, boolean created) {
    }

    private final Map<Type, ArmResourceStore<PolicyResource>> stores = new EnumMap<>(Type.class);

    @Inject
    public PolicyStore(StorageFactory storageFactory) {
        for (Type type : Type.values()) {
            stores.put(type, ArmResourceStore.create(storageFactory, STORE, type.armType(), PolicyResource.class,
                PolicyResource::id, key -> false));
        }
    }

    /** The resource of {@code type} with id {@code id}, or null. */
    public Map<String, Object> get(Type type, String id) {
        return resourceId(type, id).flatMap(stores.get(type)::get).map(stored -> stored.value().body()).orElse(null);
    }

    /** Every resource of {@code type}, at every scope. */
    public List<Map<String, Object>> list(Type type) {
        return stores.get(type).all().stream().map(stored -> stored.value().body()).toList();
    }

    /**
     * Writes {@code change} applied to the stored resource, or to empty when there is none, atomically with respect
     * to every other write of that type, so two concurrent creates leave one resource.
     */
    public Write createOrUpdate(Type type, String id, Function<Optional<Map<String, Object>>, Map<String, Object>> change) {
        ArmResourceId resourceId = resourceId(type, id).orElseThrow(() ->
            new IllegalArgumentException("not a " + type.armType() + " id: " + id));
        Written<PolicyResource> written = stores.get(type).createOrUpdate(resourceId,
            existing -> new PolicyResource(change.apply(existing.map(PolicyResource::body))));
        return new Write(written.stored().value().body(), written.created());
    }

    /** Applies {@code change} to the stored resource and writes it; null when there is none. */
    public Map<String, Object> update(Type type, String id, UnaryOperator<Map<String, Object>> change) {
        return resourceId(type, id)
            .flatMap(resourceId -> stores.get(type).update(resourceId,
                stored -> new PolicyResource(change.apply(stored.body()))))
            .map(stored -> stored.value().body())
            .orElse(null);
    }

    /** Removes the resource; returns it, or null when there was none. */
    public Map<String, Object> remove(Type type, String id) {
        return resourceId(type, id).flatMap(stores.get(type)::delete).map(stored -> stored.value().body()).orElse(null);
    }

    public Map<String, Object> getDefinition(String id) {
        return get(Type.DEFINITION, id);
    }

    public List<Map<String, Object>> listDefinitions() {
        return list(Type.DEFINITION);
    }

    public Map<String, Object> getSetDefinition(String id) {
        return get(Type.SET_DEFINITION, id);
    }

    public List<Map<String, Object>> listSetDefinitions() {
        return list(Type.SET_DEFINITION);
    }

    public Map<String, Object> getAssignment(String id) {
        return get(Type.ASSIGNMENT, id);
    }

    public List<Map<String, Object>> listAssignments() {
        return list(Type.ASSIGNMENT);
    }

    public List<Map<String, Object>> listExemptions() {
        return list(Type.EXEMPTION);
    }

    /** Id of the first set definition whose {@code policyDefinitions} reference {@code definitionId}. */
    public Optional<String> findSetDefinitionReferencing(String definitionId) {
        String wanted = key(definitionId);
        for (Map<String, Object> set : listSetDefinitions()) {
            if (properties(set).get("policyDefinitions") instanceof List<?> references) {
                for (Object reference : references) {
                    if (reference instanceof Map<?, ?> ref
                            && ref.get("policyDefinitionId") instanceof String id
                            && wanted.equals(key(id))) {
                        return Optional.of(String.valueOf(set.get("id")));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Id of the first assignment whose {@code policyDefinitionId} is {@code definitionId} (definition or set). */
    public Optional<String> findAssignmentReferencing(String definitionId) {
        String wanted = key(definitionId);
        for (Map<String, Object> assignment : listAssignments()) {
            if (properties(assignment).get("policyDefinitionId") instanceof String id && wanted.equals(key(id))) {
                return Optional.of(String.valueOf(assignment.get("id")));
            }
        }
        return Optional.empty();
    }

    /** Removes every exemption bound to {@code assignmentId}; Azure deletes them with the assignment. */
    public void removeExemptionsForAssignment(String assignmentId) {
        String wanted = key(assignmentId);
        stores.get(Type.EXEMPTION).deleteIf(exemption ->
            properties(exemption.body()).get("policyAssignmentId") instanceof String id && wanted.equals(key(id)));
    }

    public void clear() {
        stores.values().forEach(ArmResourceStore::clear);
    }

    /**
     * The id {@code id} names in the store of {@code type}: an absolute id of a resource of that type. The stores
     * share one backend, and {@link ArmResourceStore#get} reads any canonical key in it, so the type is checked here.
     * {@link ArmResourceId#parse} also reads a relative path, which would find a resource that the reference
     * comparisons ({@link #findAssignmentReferencing}) then miss, so the leading slash is checked first.
     */
    private static Optional<ArmResourceId> resourceId(Type type, String id) {
        if (id == null || !id.startsWith("/")) {
            return Optional.empty();
        }
        return ArmResourceId.parse(id)
            .filter(parsed -> parsed.name() != null && type.armType().equalsIgnoreCase(parsed.typePath()));
    }

    private static String key(String resourceId) {
        return resourceId.toLowerCase(Locale.ROOT);
    }

    private static Map<?, ?> properties(Map<String, Object> resource) {
        return resource.get("properties") instanceof Map<?, ?> props ? props : Map.of();
    }
}
