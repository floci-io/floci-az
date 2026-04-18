# Installation

## Docker

Floci-AZ is primarily distributed as a Docker image.

### Image Tags

| Tag | Description |
|---|---|
| `latest` | Native image — sub-second startup **(recommended)** |
| `latest-jvm` | JVM image |
| `x.y.z` / `x.y.z-jvm` | Pinned releases |
| `edge` | Weekly build from main |

## Manual Build (Development)

To build Floci-AZ locally, you need:
- **Java 25**
- **Maven**
- **Docker** (for testing and native builds)

```bash
./mvnw package
```

To build a native image:
```bash
./mvnw package -Dnative
```
