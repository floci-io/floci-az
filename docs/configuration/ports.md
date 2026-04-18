# Ports Reference

Floci-AZ uses a unified port for all services.

| Port | Protocol | Service |
|---|---|---|
| `4577` | HTTP | Blob, Queue, Table, Functions |

By default, the emulator listens on port `4577`. You can change this using the `FLOCI_AZ_PORT` environment variable.
