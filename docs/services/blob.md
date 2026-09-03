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
  Azure-shaped XML for SDK-generated user delegation SAS flows
- **User delegation SAS enforcement** — validates SDK-generated user delegation SAS signatures,
  expiry, signed key validity, permissions, and container/blob/directory resource scope for Blob
  and ADLS path operations
- **ADLS Gen2 / Hadoop ABFS 3.3.4 path operations** - supports the DFS wire shapes used by
  `org.apache.hadoop:hadoop-azure:3.3.4`: conditional file/directory create, path status and
  properties, append/flush, recursive delete, file/directory rename, POSIX owner/group/permission/ACL
  metadata, `checkAccess`, and the ADLS Path Lease POST API. Hadoop's default conditional-create
  overwrite flow (`409` -> status/ETag -> `If-Match`) is modeled explicitly.
- **ADLS filesystem operations** - create/delete a filesystem and get/set filesystem properties via
  `?resource=filesystem`. Root `getAccessControl` is supported so Hadoop can auto-detect HNS and
  resolve `getFileStatus("/")` without forcing `fs.azure.account.hns.enabled=true`.
- **ADLS Path - List** - supports Java DataLake SDK `listPaths` and Hadoop `listStatus`, including
  recursive/non-recursive listing, directory filters, exact-file singleton results, server pagination,
  and Hadoop 3.3.4's Base64 HNS `startFrom` continuation token.
- **ADLS AppendBlob mode** - recognizes Hadoop's `blobType=AppendBlob` create mode and enforces
  sequential append positions, including append requests carrying `flush=true` / `close=true`.
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
- **ADLS large-operation behavior is simplified** - directory rename and recursive delete complete
  atomically in the local backend rather than reproducing Azure's server-side multi-request batching.
  Hadoop observes a completed operation with no continuation token, which is protocol-compatible for
  the caller. The full Azure source/destination lease-transfer and time-condition matrix is not modeled.
- **ADLS access control is metadata-compatible, not a full POSIX authorization engine** - owner,
  group, permissions and ACLs round-trip through the Hadoop/DFS wire protocol, and `checkAccess`
  validates path existence and request shape. It does not deny requests based on emulated POSIX ACLs;
  authentication/SAS authorization remains the emulator's access-control boundary.
- **ADLS close/event semantics are storage-only** - `close=true` is accepted and committed data is
  immediately visible, but Azure Event Grid/change-notification side effects are not emulated.
- **SAS enforcement is scoped to user delegation SAS** — SDK-generated user delegation SAS tokens
  for container (`sr=c`), blob (`sr=b`), and ADLS directory (`sr=d`) resources are validated.
  Account SAS, stored access policies, IP/protocol restrictions, and the full SAS feature matrix
  are not fully modeled. User delegation keys are protected by a process-local secret, so SAS
  tokens issued by a previous emulator process are invalid after restart.
- **Snapshots, versioning, and tiering are not modeled.** Blob leases and ADLS Path leases share
  the emulator's in-memory lease state and support acquire/renew/change/release/break. Lease state is
  intentionally process-local and is lost when the emulator restarts.
- **`x-ms-server-encrypted: true` is reported although no encryption is performed** — blob data is
  stored as-is by the configured storage backend. The header mirrors the
  `x-ms-request-server-encrypted` already returned on upload and exists for SDK compatibility.
