# Copilot Instructions for Pull Request Review

Review pull requests in the Floci-Az repository with Azure compatibility as the primary concern.

Floci-Az is a Java-based local Azure emulator built on Quarkus. Its goal is to match Azure SDK and Azure CLI behavior through real Azure wire protocols, not convenience APIs or custom abstractions.

## Review Priorities

Evaluate changes in this order:

1. Preserve Azure protocol compatibility
2. Match Azure SDK and Azure CLI behavior
3. Reuse existing Floci-Az patterns
4. Prefer correctness over convenience
5. Keep changes focused and testable

## What to Flag

Raise concerns when a PR introduces any of the following without strong justification:

- Non-Azure endpoint shapes or routing patterns
- Request or response format changes made for convenience
- Broad refactors unrelated to the PR goal
- New service patterns where an existing Floci-Az pattern should be reused
- Direct storage implementation usage instead of `StorageFactory`
- Auth bypasses that do not respect the configured `auth.mode`

## Architecture Expectations

Floci-Az follows a layered design:

- `AzureRoutingFilter` dispatches incoming requests to the correct service handler
- `AzureServiceHandler` implementations parse Azure protocol input and produce Azure-compatible responses
- Services contain business logic and should produce `AzureErrorResponse` on failure
- Models hold domain data

Core infrastructure commonly relevant in reviews:

- `EmulatorConfig`
- `AzureServiceRegistry`
- `StorageFactory`
- `AzureServiceHandler`
- `AzureRoutingFilter`
- `AzureErrorResponse`
- `AuthPipeline` / `AuthVerifier`

Check that handlers stay thin, business logic remains in service classes, and new changes fit existing repository patterns.

## Service Routing

Each service is routed by account-suffix on a single port (default `4577`):

| Service | Path prefix | Notes |
|---|---|---|
| Blob Storage | `/{account}/` | Container and blob operations |
| Queue Storage | `/{account}-queue/` | Queue and message operations |
| Table Storage | `/{account}-table/` | Table and entity operations |
| Azure Functions | `/{account}-functions/` | Deploy and invoke HTTP-triggered functions |

Flag PRs that change routing prefixes or introduce non-Azure path conventions.

## Protocol Review Rules

Floci-Az implements real Azure wire protocols. Review protocol-affecting changes carefully.

- Blob Storage uses REST XML for list operations and raw binary for blob data
- Queue Storage uses REST XML for messages and queue metadata
- Table Storage uses OData JSON (application/json;odata=minimalmetadata) for entity operations
- Azure Functions management uses REST JSON; function invocation proxies directly to the container

Pay extra attention to these cases:

- Error responses must follow Azure's error envelope: `{ "error": { "code": "...", "message": "..." } }`
- Blob and Queue operations use shared-key HMAC-SHA256 signatures — auth must not be silently skipped in `strict` mode
- SAS token parsing must respect expiry, permissions, and resource fields
- Azure Functions must use Docker-in-Docker; do not introduce direct process execution

## Auth Review

Floci-Az supports two auth modes configured via `floci-az.auth.mode`:

- `dev` — accept any credentials without signature validation (default)
- `strict` — validate HMAC-SHA256 signatures via `SharedKeyAuthVerifier`

Also supports Bearer tokens (`BearerTokenVerifier`) and SAS tokens (`SasTokenParser`).

Flag PRs that:

- Hardcode auth behavior instead of reading from `EmulatorConfig`
- Break `strict` mode by skipping `AuthPipeline`
- Accept malformed or expired SAS tokens

## XML and JSON Rules

Flag PRs that:

- Return error responses that do not follow Azure error structures
- Change OData metadata levels without justification
- Use non-standard XML namespaces for Storage responses
- Change controller return types in ways that may break reflection or native-image compatibility

## Config and Storage Review

When a PR changes configuration or persistence behavior, verify the change is wired consistently.

Check for updates to:

- `EmulatorConfig`
- main `application.yml`
- test `application.yml`
- `StorageFactory`

Supported storage modes include:

- `memory`
- `persistent`
- `hybrid`
- `wal`

Treat repository YAML as the source of truth for runtime behavior unless the PR explicitly changes configuration semantics.

## Testing Expectations

Expect automated coverage for changes that affect:

- request parsing
- response shape
- error handling
- persistence semantics
- URL and endpoint generation
- service enablement
- auth validation

Prefer:

- Azure SDK-based validation (Java, Node, Python SDKs) over raw HTTP-only testing
- Integration tests for compatibility-sensitive behavior
- Existing naming conventions such as `*ServiceTest.java` and `*IntegrationTest.java`

If behavior changes without automated coverage, call that out explicitly.

## Review Checklist

When analyzing a PR, check:

- Is the change focused?
- Does it preserve Azure-compatible wire behavior?
- Does it reuse an existing Floci-Az pattern?
- Are handlers thin and service classes responsible for domain logic?
- Are `AzureErrorResponse` and existing error-mapping patterns used correctly?
- Are config and YAML updates complete?
- Are storage changes wired through `StorageFactory`?
- Are auth changes consistent with `AuthPipeline` and both `dev`/`strict` modes?
- Are tests added or updated where compatibility is affected?
- Are docs updated when user-facing behavior changes?

## How to Write Feedback

Write review comments that are:

- specific
- repository-aware
- grounded in Azure compatibility risk

Use severity when helpful:

- `high`: likely breaks Azure SDK / CLI compatibility or protocol behavior
- `medium`: inconsistent with Floci-Az architecture, wiring, or testing expectations
- `low`: maintainability, clarity, or minor convention issue

Prefer comments that explain:

- what is risky
- why it matters in Floci-Az
- which existing pattern should be followed instead

## If Behavior Is Unclear

Use this fallback order:

1. Prefer Azure behavior
2. Then existing Floci-Az behavior
3. Then compatibility test expectations

If correctness would require a broader architectural change, call out the tradeoff instead of suggesting blind refactoring.