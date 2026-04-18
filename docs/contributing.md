# Contributing

We welcome contributions to Floci-AZ!

## Development Environment
- Java 25
- Maven 3.9+
- Docker

## Project Structure
- `src/main/java/io/floci/az/core`: Core routing, auth, and persistence.
- `src/main/java/io/floci/az/services`: Service implementations (blob, queue, table, functions).
- `compatibility-tests`: SDK-based tests in Python, Java, and Node.js.

## Running Tests
```bash
# Compile and run unit tests
./mvnw test

# Run compatibility tests (requires running container)
make test-python
make test-java-compat
make test-node-compat
```
