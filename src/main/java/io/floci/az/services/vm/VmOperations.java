package io.floci.az.services.vm;

import io.floci.az.core.arm.ArmResourceId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The power-action operations a {@link VmHandler} issued, so a poll reports what the action actually did instead
 * of assuming it succeeded. Kept in memory, the newest {@value #MAX_TRACKED_OPERATIONS} only: the cap bounds memory
 * in a long-running emulator, and an evicted or never-issued id is answered 404 rather than reported as a success.
 */
final class VmOperations {

    static final int MAX_TRACKED_OPERATIONS = 1024;

    /** Operation states, with the names the ARM operation resource carries in {@code status}. */
    enum Status {
        IN_PROGRESS("InProgress"),
        SUCCEEDED("Succeeded"),
        FAILED("Failed");

        private final String wireName;

        Status(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }

        boolean terminal() {
            return this != IN_PROGRESS;
        }
    }

    /**
     * One issued operation. An {@code IN_PROGRESS} operation follows its VM's provisioning: it is resolved from the
     * VM's state when it is polled.
     */
    record VmOperation(String id, ArmResourceId vmId, Status status, String errorMessage,
                       Instant startTime, Instant endTime) {

        VmOperation resolved(Status outcome, String error) {
            return new VmOperation(id, vmId, outcome, error, startTime, Instant.now());
        }
    }

    private final Map<String, VmOperation> operations = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, VmOperation> eldest) {
            return size() > MAX_TRACKED_OPERATIONS;
        }
    };

    /** Records an operation on {@code vmId} and returns its id. A terminal status is stamped with its end time. */
    synchronized String issue(ArmResourceId vmId, Status status, String errorMessage) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        operations.put(id, new VmOperation(id, vmId, status, errorMessage, now, status.terminal() ? now : null));
        return id;
    }

    synchronized Optional<VmOperation> get(String id) {
        return Optional.ofNullable(operations.get(id));
    }

    /** Stores a resolved operation, unless it was evicted in the meantime. */
    synchronized void update(VmOperation operation) {
        operations.computeIfPresent(operation.id(), (id, previous) -> operation);
    }

    synchronized void clear() {
        operations.clear();
    }
}
