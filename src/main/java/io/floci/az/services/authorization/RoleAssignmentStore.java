package io.floci.az.services.authorization;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.storage.StorageFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The role assignments, as their ARM bodies, in one {@link ArmResourceStore} over the {@code authorization} storage
 * backend, keyed by the canonical (lowercased) id, so it follows the global storage mode and lookups ignore case the
 * way ARM does. Assignments live at any scope, from the tenant root to a resource, so the listings read
 * {@link #all()} and compare scopes themselves.
 *
 * <p>A create checks two rules that span assignments, a name used at another scope and the same grant under another
 * name, so the check and the write run together in {@link #atomically}. All access goes through methods: callers
 * hold a normal-scoped client proxy.</p>
 */
@ApplicationScoped
public class RoleAssignmentStore {

    static final String STORE = "authorization";

    /** One role assignment: the ARM body the handler answers with. */
    @RegisterForReflection
    public record RoleAssignment(@JsonValue Map<String, Object> body) {

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public RoleAssignment {
            body = new LinkedHashMap<>(body);
        }

        /** The assignment's ARM id. */
        public String id() {
            return String.valueOf(body.get("id"));
        }
    }

    private final ArmResourceStore<RoleAssignment> store;

    @Inject
    public RoleAssignmentStore(StorageFactory storageFactory) {
        this.store = ArmResourceStore.create(storageFactory, STORE, RbacPath.Type.ROLE_ASSIGNMENT.armType(),
                RoleAssignment.class, RoleAssignment::id, key -> false);
    }

    /** Runs {@code action} with no other write to the store in between. */
    public synchronized <R> R atomically(Supplier<R> action) {
        return action.get();
    }

    public synchronized Optional<Map<String, Object>> get(String id) {
        return ArmResourceId.parse(id).flatMap(store::get).map(stored -> stored.value().body());
    }

    public synchronized List<Map<String, Object>> all() {
        return store.all().stream().map(stored -> stored.value().body()).toList();
    }

    public synchronized void put(Map<String, Object> body) {
        store.put(new RoleAssignment(body));
    }

    /** Removes the assignment; returns it, or empty when there was none. */
    public synchronized Optional<Map<String, Object>> remove(String id) {
        return ArmResourceId.parse(id).flatMap(store::delete).map(stored -> stored.value().body());
    }

    /** Removes every assignment at {@code scopeId} or below it: a resource group that is gone takes them along. */
    public synchronized void removeAtOrBelow(String scopeId) {
        ArmResourceId.parse(scopeId).ifPresent(scope -> store.under(scope).forEach(stored -> store.delete(stored.id())));
    }

    public synchronized void clear() {
        store.clear();
    }
}
