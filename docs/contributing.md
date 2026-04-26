# Contributing

Contributions are welcome — bug fixes, new service operations, documentation improvements, or test coverage.

## Development Environment

- **Java 25**
- **Maven 3.9+**
- **Docker** (required for Azure Functions tests and native builds)

## Project Structure

```
src/main/java/io/floci/az/
├── config/          # EmulatorConfig — all settings via SmallRye Config
├── core/
│   ├── auth/        # AuthPipeline, SharedKeyAuthVerifier, BearerTokenVerifier, SasTokenParser
│   ├── dns/         # EmbeddedDnsServer — DNS resolver injected into function containers
│   ├── docker/      # DockerClientProducer, DockerHostResolver, ContainerDetector
│   └── storage/     # StorageBackend, StorageFactory, InMemoryStorage, HybridStorage, WalStorage
└── services/
    ├── blob/        # BlobServiceHandler, BlobModels
    ├── queue/       # QueueServiceHandler, QueueModels
    ├── table/       # TableServiceHandler, TableModel
    └── functions/   # FunctionsServiceHandler, ContainerLauncher, WarmPool, FunctionCodeStore

compatibility-tests/
├── sdk-test-java/   # Azure Java SDK tests (JUnit 5)
├── sdk-test-node/   # Azure Node.js SDK tests (Jest)
└── sdk-test-python/ # Azure Python SDK tests (pytest)
```

## Running Tests

### Unit tests

```bash
./mvnw test
```

### Compatibility tests — local (requires running emulator)

Start the emulator first:
```bash
docker compose up -d
```

Then run via Make:
```bash
make test-python        # Python SDK (virtualenv)
make test-java-compat   # Java SDK (Maven)
make test-node-compat   # Node.js SDK (npm)
```

### Compatibility tests — Docker (matches CI)

```bash
make compat-docker
```

This builds each test image and runs it against the running floci-az container on the
`floci_az_default` network. The Java test suite mounts the Docker socket to enable
Azure Functions invocation tests.

## Code Style

- Follow existing patterns — controllers stay thin, logic lives in service classes.
- Errors returned to clients must use `AzureErrorResponse` (never raw strings or arbitrary JSON).
- New config keys belong in `EmulatorConfig`; document them in `docs/configuration/application-yml.md`.
- New service operations should have a corresponding compatibility test.
