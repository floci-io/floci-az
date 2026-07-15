# Blob Storage

Compatible with the `azure-storage-blob` SDKs (Java, Python, Node.js), focused Java
`azure-storage-file-datalake` SDK flows, the Azure CLI (`az storage blob`), and Azurite-style
connection strings. Speaks the Azure Storage Blob REST protocol with Shared Key authentication,
Blob XML responses, and the Data Lake Storage Gen2 DFS host alias.

> **HTTP-only — no Docker.** Data is held by the configured [storage backend](../configuration/storage.md)
> (`memory` by default; `persistent`, `hybrid`, or `wal` for durability).

---

## Features

- **Containers** — Create, Get properties, Delete, List (`?comp=list`); duplicate create returns
  `409 ContainerAlreadyExists`
- **Blobs** — Put (upload), Get (download), Delete, List within a container; overwrite semantics
- **Block blobs** — staged block upload (`?comp=block`) followed by commit (`?comp=blocklist`) for
  large payloads, in addition to single-request `Put Blob`
- **Data Lake Storage Gen2 endpoint alias** — the `{account}.dfs.core.windows.net` host maps to the
  Blob backend so ADLS SDK path clients can create, read, write, and delete paths through the same
  local data store
- **User delegation key vending** — `POST ?restype=service&comp=userdelegationkey` returns
  deterministic Azure-shaped XML for SDK-generated user delegation SAS flows
- **User delegation SAS enforcement** — validates SDK-generated user delegation SAS signatures,
  expiry, signed key validity, permissions, and container/blob/directory resource scope for Blob
  and ADLS path operations
- **ADLS Path - List** — supports Java DataLake SDK `listPaths` over the DFS endpoint, including
  recursive and non-recursive listing, directory filters, deterministic continuation tokens, and
  user delegation SAS list-scope checks
- **Range download** — `Range: bytes=…` returns `206 Partial Content`
- **Conditional download** — `If-Match` / `If-None-Match` honored; a stale ETag is rejected
- **Metadata** — `x-ms-meta-*` set on upload and returned on Get, round-tripped exactly
- **Not-found semantics** — missing blob/container returns the Azure `404 BlobNotFound` /
  `ContainerNotFound` XML error shape

## Endpoint

```
http://localhost:4577/{account}/{container}                  # container operations
http://localhost:4577/{account}/{container}/{blob}           # blob operations
http://localhost:4577/{account}/{filesystem}?resource=filesystem&recursive=true  # ADLS listPaths
```

The account also answers at the host-style address `{account}.blob.core.windows.net` (and the Data
Lake Gen2 alias `{account}.dfs.core.windows.net`, which maps to the same blob backend) when the
`Host` header is set, matching how the SDKs address storage endpoints.

ARM storage account responses include both `blob` and `dfs` primary endpoints so Data Lake SDK
clients can discover the Gen2 endpoint shape.

## Quickstart

=== "Python"

    ```python
    from azure.storage.blob import BlobServiceClient

    conn = ("DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
            "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==;"
            "BlobEndpoint=http://localhost:4577/devstoreaccount1;")
    svc = BlobServiceClient.from_connection_string(conn)
    container = svc.create_container("my-container")
    container.upload_blob("hello.txt", b"hello world")
    print(container.download_blob("hello.txt").readall())
    ```

=== "Azure CLI"

    ```bash
    az storage container create --name my-container \
      --connection-string "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=...;BlobEndpoint=http://localhost:4577/devstoreaccount1;"
    ```

The well-known Azurite development key (`Eby8vdM0…`) is accepted; any account name works.

## Configuration

```yaml
floci-az:
  services:
    blob:
      enabled: true
  storage:
    services:
      blob:
        # mode: wal            # override the global storage.mode for blob only
        flush-interval-ms: 5000
```

| Property | Env var | Default | Description |
|---|---|---|---|
| `enabled` | `FLOCI_AZ_SERVICES_BLOB_ENABLED` | `true` | Enables the Blob Storage service |
| `storage.services.blob.mode` | `FLOCI_AZ_STORAGE_SERVICES_BLOB_MODE` | *(inherits `storage.mode`)* | Per-service backend override (`memory` / `persistent` / `hybrid` / `wal`) |
| `storage.services.blob.flush-interval-ms` | `FLOCI_AZ_STORAGE_SERVICES_BLOB_FLUSH_INTERVAL_MS` | `5000` | Background flush-to-disk interval for the `hybrid` mode only; ignored by `memory` / `persistent` / `wal` (`wal` compacts on `storage.wal.compaction-interval-ms` instead) |

## Intentional deviations

- **Shared Key signatures are accepted but not cryptographically verified** — the emulator is a
  local dev target; any well-formed `Authorization` header (or the Azurite key) is honored.
- **SAS enforcement is scoped to user delegation SAS** — SDK-generated user delegation SAS tokens
  for container (`sr=c`), blob (`sr=b`), and ADLS directory (`sr=d`) resources are validated.
  Account SAS, stored access policies, IP/protocol restrictions, and the full SAS feature matrix
  are not fully modeled.
- **Snapshots, versioning, leases, and tiering are not modeled.**
