# Queue Storage

Compatible with the `azure-storage-queue` SDKs (Java, Python, Node.js), the Azure CLI
(`az storage queue`), and Azurite-style connection strings. Speaks the Azure Storage Queue REST
protocol with Shared Key authentication and XML responses.

> **HTTP-only — no Docker.** Data is held by the configured [storage backend](../configuration/storage.md)
> (`memory` by default; `persistent`, `hybrid`, or `wal` for durability).

---

## Features

- **Queues** — Create, Delete, Get/Set metadata; duplicate create is idempotent, missing queue
  returns the Azure `404 QueueNotFound` shape
- **Messages** — Enqueue (send), Dequeue (receive), Delete, Peek; multiple in-flight messages
- **Peek is non-consuming** — `?peekonly=true` returns messages without advancing the dequeue count
  or hiding them
- **Visibility timeout** — a received message is hidden for its `visibilitytimeout` and reappears
  after it elapses
- **Pop-receipt validation** — delete/update require the current pop receipt; a stale receipt is
  rejected, and updating a message rotates the receipt
- **Message TTL** — messages expire and are removed after `messagettl`
- **Update message** — replaces content and resets visibility, returning a fresh pop receipt

## Endpoint

```
http://localhost:4577/{account}-queue/{queue}                    # queue operations
http://localhost:4577/{account}-queue/{queue}/messages           # message operations
```

The account also answers at the host-style address `{account}.queue.core.windows.net` when the
`Host` header is set.

## Quickstart

=== "Python"

    ```python
    from azure.storage.queue import QueueClient

    conn = ("DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
            "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==;"
            "QueueEndpoint=http://localhost:4577/devstoreaccount1-queue;")
    queue = QueueClient.from_connection_string(conn, "my-queue")
    queue.create_queue()
    queue.send_message("hello world")
    msg = queue.receive_message()
    print(msg.content)
    queue.delete_message(msg)
    ```

=== "Azure CLI"

    ```bash
    az storage message put --queue-name my-queue --content "hello world" \
      --connection-string "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=...;QueueEndpoint=http://localhost:4577/devstoreaccount1-queue;"
    ```

The well-known Azurite development key (`Eby8vdM0…`) is accepted; any account name works.

## Configuration

```yaml
floci-az:
  services:
    queue:
      enabled: true
  storage:
    services:
      queue:
        # mode: persistent     # override the global storage.mode for queue only
        flush-interval-ms: 5000
```

| Property | Env var | Default | Description |
|---|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_QUEUE_ENABLED` | `true` | Enables the Queue Storage service |
| `storage.services.queue.mode` | `FLOCI_AZ_STORAGE_SERVICES_QUEUE_MODE` | *(inherits `storage.mode`)* | Per-service backend override (`memory` / `persistent` / `hybrid` / `wal`) |
| `storage.services.queue.flush-interval-ms` | `FLOCI_AZ_STORAGE_SERVICES_QUEUE_FLUSH_INTERVAL_MS` | `5000` | Background flush-to-disk interval for the `hybrid` mode only; ignored by `memory` / `persistent` / `wal` (`wal` compacts on `storage.wal.compaction-interval-ms` instead) |

## Intentional deviations

- **Shared Key signatures are accepted but not cryptographically verified.**
- **No SAS enforcement** — SAS query parameters are parsed but not validated.
- **Message dequeue count** is tracked but `maxDequeueCount` poison-message handling is not modeled.
