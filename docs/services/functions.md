# Azure Functions

Floci-AZ emulates Azure Functions by spawning real Azure Functions runtime Docker containers.

## Supported Runtimes
- `node`
- `python`
- `java`
- `dotnet`

## Features
- REST Management API (CRUD for apps/functions)
- HTTP Triggers
- Warm Container Pool (LIFO reuse)
- Automatic code injection (ZIP extraction)

## Endpoint
`http://localhost:4577/{accountName}-functions`
