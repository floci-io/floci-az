# sdk-test-cpp

Compatibility tests for [Floci AZ](https://github.com/floci-io/floci-az) using the **Azure SDK for C++**.

Tests are [GoogleTest](https://github.com/google/googletest) cases driving the real SDK clients, mirroring the Python/Java/Node suites. Dependencies come from [vcpkg](https://vcpkg.io) in manifest mode (`vcpkg.json`).

## Services Covered

| Suite           | Coverage                                                                    |
| --------------- | --------------------------------------------------------------------------- |
| `blob_test`     | Blob lifecycle, `Get Blob Properties`, error codes (404 / 409), XML error body |
| `queue_test`    | Queue lifecycle, enqueue/receive/delete, queue properties, error codes        |

## Why this suite exists

The Azure SDK for C++ fails on bodyless error responses in a way no other SDK does. `StorageException::CreateFromResponse` picks a parser by substring-matching the `content-type` header and hands the body to `XmlReader` without checking that the buffer is non-empty. On a `HEAD` error response advertising `application/xml` with zero bytes, the resulting `std::runtime_error` is thrown from *inside* the `RequestFailedException` constructor, so the exception object is never built, no catch clause can run, and the process calls `terminate()`.

## Requirements

- Docker (the image builds its own toolchain)

Building outside Docker additionally needs CMake 4.3+, a C++17 compiler, Ninja, and vcpkg.

## Running

```bash
# Build the emulator + run this suite (from repo root)
make test-cpp-compat

# As part of the full compatibility matrix
make compat-docker
```

## Configuration

| Variable            | Default                   | Description                |
| ------------------- | ------------------------- | -------------------------- |
| `FLOCI_AZ_ENDPOINT` | `http://floci-az:4577`    | Floci AZ emulator endpoint |

## Build cost

vcpkg compiles OpenSSL, libxml2, libcurl and the SDK from source, so the **cold build takes roughly 6 minutes**. Dependencies are installed before the test sources are copied, so editing a test rebuilds in seconds.

The build stage is ~2.7 GB, but the SDK is static-linked and the runtime stage copies only the test binary — the final image is ~130 MB.

## Docker

```bash
docker build -t compat-sdk-test-cpp .
docker run --rm --network compat-net \
  -e FLOCI_AZ_ENDPOINT=http://floci-az:4577 \
  -v "$PWD/results:/results" compat-sdk-test-cpp

# Run a subset (GoogleTest filters)
docker run --rm --network compat-net compat-sdk-test-cpp --gtest_filter='BlobErrors.*'
```

Results are written as JUnit XML to `/results/junit.xml`.
