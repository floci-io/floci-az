package io.floci.az.core;

/**
 * A state-holding service component that can wipe all of its emulator state.
 * Implementations are discovered via CDI ({@code Instance<Resettable>}) and
 * invoked by {@code POST /_admin/reset} in no particular order, so
 * {@link #clearAll()} must be self-contained (a service that manages sidecar
 * containers stops them itself) and idempotent.
 */
public interface Resettable {

    void clearAll();
}
