# Installation

## Docker

Floci-AZ is distributed as a multi-arch Docker image (`linux/amd64` and `linux/arm64`).

### Image Tags

| Tag | Description |
|---|---|
| `latest` | Native binary — sub-second startup **(recommended)** |
| `latest-jvm` | JVM image — larger, no GraalVM required |
| `x.y.z` | Pinned native release |
| `x.y.z-jvm` | Pinned JVM release |
| `edge` | Weekly build from `main` |

### Quick Run

```bash
docker run -d --name floci-az \
  -p 4577:4577 \
  -v ./data:/app/data \
  -v /var/run/docker.sock:/var/run/docker.sock \
  floci/floci-az:latest
```

The Docker socket mount is required for Azure Functions. The entrypoint automatically
handles Docker socket group permissions on both Linux and macOS/Windows hosts.

---

## Manual Build (Development)

Requirements: **Java 25**, **Maven 3.9+**, **Docker**

### JVM build

```bash
./mvnw package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar
```

### Native build (fastest startup)

```bash
./mvnw package -Dnative -DskipTests
./target/*-runner
```

Native builds require GraalVM / Mandrel with native-image support.

### Docker build (local)

```bash
# JVM image
docker build -f Dockerfile -t floci-az:dev .

# Native image (single-arch, current machine)
docker build -f Dockerfile.native -t floci-az:dev-native .
```
