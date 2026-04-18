# Storage Modes

Floci-AZ supports multiple storage backends to balance performance and durability.

| Mode | Behavior | Best for... |
|:---:|---|---|
| **`memory`** | Entirely in-RAM. Data is lost on stop. | Speed, ephemeral testing, CI. |
| **`persistent`** | Loaded at startup, flushed on shutdown. | Simple local dev with state. |
| **`hybrid`** | In-memory with periodic async flushing (5s). | Balance of speed and safety. |
| **`wal`** | Write-Ahead Log. Every mutation is logged. | Maximum durability. |

## Per-service Overrides

You can override the global storage mode per service:

```yaml
environment:
  FLOCI_AZ_STORAGE_MODE: memory
  FLOCI_AZ_STORAGE_SERVICES_BLOB_MODE: wal
  FLOCI_AZ_STORAGE_SERVICES_QUEUE_MODE: hybrid
```
