# Storage Modes

Floci-AZ supports four storage backends. Set the global default with `FLOCI_AZ_STORAGE_MODE`
and override it per service if needed.

| Mode | Behavior | Best for |
|:---:|---|---|
| **`memory`** | Entirely in-RAM. Data is lost when the container stops. | Speed, ephemeral testing, CI pipelines. |
| **`persistent`** | Loaded at startup, flushed to disk on graceful shutdown. | Simple local dev with state preservation. |
| **`hybrid`** | In-memory with periodic async flush every 5 s. | Best balance of speed and safety. |
| **`wal`** | Write-Ahead Log — every mutation is written to disk before responding. | Maximum durability. |

## Global mode

```yaml
environment:
  FLOCI_AZ_STORAGE_MODE: hybrid
```

## Per-service overrides

You can set a different mode for each service independently:

```yaml
environment:
  FLOCI_AZ_STORAGE_MODE: memory                    # default for all services
  FLOCI_AZ_STORAGE_SERVICES_BLOB_MODE: wal         # blob writes every mutation to disk
  FLOCI_AZ_STORAGE_SERVICES_QUEUE_MODE: hybrid     # queue flushes every 5 s
  FLOCI_AZ_STORAGE_SERVICES_TABLE_MODE: persistent # table flushes on shutdown
  FLOCI_AZ_STORAGE_SERVICES_APP_CONFIG_MODE: wal   # app config writes every mutation to disk
```

## Persistence directory

Mount a local directory and point `FLOCI_AZ_STORAGE_PERSISTENT_PATH` at it:

```yaml
volumes:
  - ./data:/app/data
environment:
  FLOCI_AZ_STORAGE_MODE: hybrid
  FLOCI_AZ_STORAGE_PERSISTENT_PATH: /app/data
```

The default path inside the container is `/app/data`, so the volume mount above is
enough — you only need `FLOCI_AZ_STORAGE_PERSISTENT_PATH` when using a non-default path.

## Tuning WAL and Hybrid

| Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_STORAGE_WAL_COMPACTION_INTERVAL_MS` | `30000` | How often the WAL log is compacted (ms) |
| `FLOCI_AZ_STORAGE_HYBRID_FLUSH_INTERVAL_MS` | `5000` | How often hybrid mode flushes to disk (ms) |
