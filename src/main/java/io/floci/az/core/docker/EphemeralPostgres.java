package io.floci.az.core.docker;

import java.util.List;

/**
 * Skips crash safety for PostgreSQL sidecars whose data dies with the container.
 *
 * <p>Every PostgreSQL-backed sidecar floci-az starts (Azure Database for PostgreSQL servers and the
 * Cosmos DB for PostgreSQL engine) is removed before it is created and removed again on stop or
 * shutdown, with no data volume. Their data can never outlive a crash, so the durability work the
 * official image does by default is pure startup and write latency:
 *
 * <ul>
 *   <li>{@code initdb} fsyncs the whole new data directory on first start; {@code --nosync} skips it,
 *       roughly halving container start-to-ready time.</li>
 *   <li>The server fsyncs every commit and writes full pages after each checkpoint;
 *       {@code fsync=off} and {@code full_page_writes=off} drop both.</li>
 * </ul>
 *
 * <p>Relies on the official {@code postgres} image entrypoint (also used by {@code citusdata/citus}):
 * it reads {@code POSTGRES_INITDB_ARGS}, and when the command starts with {@code -} it runs
 * {@code postgres} with those arguments. Do not apply this to a container that mounts a data volume.
 */
public final class EphemeralPostgres {

    private static final String INITDB_ARGS_ENV = "POSTGRES_INITDB_ARGS";
    private static final String INITDB_ARGS = "--nosync";
    private static final List<String> SERVER_ARGS = List.of("-c", "fsync=off", "-c", "full_page_writes=off");

    private EphemeralPostgres() {
    }

    /** Applies the no-durability settings to a PostgreSQL container that has no data volume. */
    public static ContainerBuilder.Builder apply(ContainerBuilder.Builder builder) {
        return builder
            .withEnv(INITDB_ARGS_ENV, INITDB_ARGS)
            .withCmd(SERVER_ARGS);
    }
}
