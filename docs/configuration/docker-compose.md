# Docker Compose Configuration

## Basic Setup

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - ./data:/app/data
      - /var/run/docker.sock:/var/run/docker.sock
```

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_PORT` | `4577` | Port exposed by the API |
| `FLOCI_AZ_STORAGE_MODE` | `memory` | Global storage mode: `memory`, `persistent`, `hybrid`, `wal` |
| `FLOCI_AZ_STORAGE_PATH` | `/app/data` | Directory for persisted state |
| `FLOCI_AZ_AUTH_MODE` | `dev` | `dev` (accept any) or `strict` (validate HMAC) |
