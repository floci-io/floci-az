# application.yml Reference

Floci-AZ is a Quarkus application and can be configured using an `application.yml` file.

```yaml
floci-az:
  port: 4577
  auth:
    mode: dev
  persistence:
    mode: memory
    path: /app/data
  services:
    blob:
      enabled: true
    queue:
      enabled: true
    table:
      enabled: true
    functions:
      enabled: true
      docker-host: unix:///var/run/docker.sock
      ephemeral: false
      idle-timeout-ms: 300000
```
