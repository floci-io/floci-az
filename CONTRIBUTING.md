# Contributing to floci-az

Thank you for your interest in contributing! floci-az is a community-driven project and all contributions are welcome.

## Ways to Contribute

- **Bug reports**: open an issue with a minimal reproduction
- **Feature requests**: open an issue describing the Azure behavior you need
- **Pull requests**: bug fixes, new service implementations, or improvements
- **Compatibility tests**: add cases to `./compatibility-tests/`

## Getting Started

### Prerequisites

- Java 25+
- Maven 3.9+
- Docker (for the sidecar-backed services and their tests: Service Bus, Event Hubs, SQL, PostgreSQL, Redis, AKS, and others)

Any Java 25+ distribution will work. If you need to install it, [SDKMAN](https://sdkman.io/) is a convenient option:

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25-open
```

### Build & Run

This project includes a Maven wrapper, so you don't need to install Maven separately:

```bash
git clone https://github.com/floci-io/floci-az.git
cd floci-az
./mvnw quarkus:dev     # hot reload on port 4577
```

If you prefer to use your own Maven installation (3.9+), you can use `mvn` instead of `./mvnw`.

### Run Tests

```bash
./mvnw test                                                            # all tests
./mvnw test -Dtest=QueueServiceTest                                    # single class
./mvnw test -Dtest=QueueServiceTest#getQueueServicePropertiesReturnsXml  # single method
```

## Branching Model

floci-az uses a **tag-driven release model**. Docker images are never published on PR merge, only when a maintainer pushes a version tag.

| Branch | Purpose | Docker published? |
|---|---|---|
| `main` | Integration branch: all PRs merge here. Treated as unstable/nightly. | No (CI tests only) |
| `X.Y.Z` tag | Signals a production release. Triggers the full Docker publish pipeline. | Yes (`x.y.z`, `latest`, `x.y.z-compat`, `latest-compat`) |

## Commit Message Format

This project uses [Conventional Commits](https://www.conventionalcommits.org/): semantic-release reads these to generate the changelog and version bumps automatically.

| Prefix | When to use | Version bump |
|--------|-------------|--------------|
| `feat:` | New Azure API action or service | minor |
| `fix:` | Bug fix or Azure compatibility correction | patch |
| `perf:` | Performance improvement | patch |
| `docs:` | Documentation only | none |
| `chore:` | Build, CI, dependencies | none |
| `BREAKING CHANGE:` | Footer or `!` suffix, incompatible change | major |

Do not include `Co-Authored-By` trailers for AI tools in commit messages. Attribution should be limited to human contributors.

**Examples:**

```
feat: add Blob Storage append blob operations
fix: correct Table Storage OData filter comparison operators
feat!: change default storage mode to persistent
```

## Architecture

See [AGENTS.md](AGENTS.md) for a detailed description of the architecture (`AzureRoutingFilter` → `AzureServiceHandler` → `StorageBackend`), the Azure protocol mapping, and conventions for adding new services.

`AGENTS.md` is the canonical agent instructions file for this repository. If your coding agent expects a different filename, create a local symlink to `AGENTS.md` instead of copying the file.

```bash
ln -s AGENTS.md CLAUDE.md
ln -s AGENTS.md GEMINI.md
ln -s AGENTS.md COPILOT.md
```

## Adding a New Azure Service

1. Create a package under `src/main/java/io/floci/az/services/<service>/`
2. Add a `*Handler.java` implementing `AzureServiceHandler` (`getServiceType()`, `canHandle()`, `handle()`), and implement `enabled(String serviceType)` to read the service's config flag
3. Declare how requests reach the handler: return a `ServiceRoutes` from `routes()` naming its account suffix (such as `-queue`), host suffixes (such as `.blob.core.windows.net`) and ARM providers (such as `Microsoft.App`), and add the same literals to the golden lists in `RoutingTableAssemblyTest`, or `./mvnw test` fails
4. Add model classes (`*Models.java`) as needed. A service backed by a Docker sidecar also gets a `*ContainerManager`, a `*Manager` and, if the sidecar needs one, a `*ConfigGenerator`
5. Add config entries in `EmulatorConfig.java` and `application.yml`
6. Add tests (e.g. `*ServiceTest.java`), and compatibility tests in `./compatibility-tests/` for SDK-facing behavior

`AzureServiceRegistry` discovers handlers through CDI, and `AzureRoutingFilter` builds its dispatch tables from every handler's `routes()`, so a service routed by an account suffix, host suffix or ARM provider needs no change to either. A service whose URLs fit none of those three (like IMDS, Entra ID, Microsoft Graph or the Cosmos root) needs a routing **stage** instead: a method added to the `stages` list in `AzureRoutingFilter`, ordered against its neighbours, plus its service type in `LITERAL_ROUTE_SERVICE_TYPES`. That set only declares the type for the routing-drift test; adding to it alone routes nothing.

See [Service Implementation Pattern](AGENTS.md#service-implementation-pattern) in `AGENTS.md` for the full checklist, and copy an existing service's pattern before introducing a new one.

Always implement the **real Azure wire protocol**. Never invent custom endpoints. The Azure SDKs and the Azure CLI must work against floci-az without modification.

## Pull Request Guidelines

1. Branch off `main`: `git checkout -b feature/my-feature`
2. Open a PR targeting `main`.
3. CI runs tests automatically. All checks must pass before merge.
4. Keep PRs focused: one feature or fix per PR.
5. Reference any related issues in the PR description.

Docker images are never built on contributor PRs, so merging to `main` is always cheap.

### Pull Request Limits and Review Bandwidth

To make sure every contribution gets a thorough, high-quality review in a reasonable time, we ask contributors to keep **no more than 4 open pull requests**, drafts included, at any time in this repository, and **no more than 2 of them ready for review**.

- **Why this policy exists:** maintainer review time is limited. Capping concurrent open PRs prevents review backlogs, reduces context switching, and keeps PR cycle times short for everyone.
- **Dependent work:** if your work depends on a PR that has not been merged yet, build on that branch or note the dependency in the discussion instead of opening separate, uncoordinated PRs.
- **Draft PRs:** drafts do not count against the limit of 2 ready pull requests, but they do count toward the total of 4. You can have, for example, 2 ready and 2 drafts, or 1 ready and 3 drafts. Use drafts for work in progress, not as a queue of finished changes waiting for a review slot, and mark a draft as ready for review only when you have review capacity available.
- **How it is applied:** a bot turns a pull request back into a draft if it would be your 3rd ready for review, and closes a pull request opened while you already have 4 open, drafts included. Your branch and commits are always kept: mark the draft ready again once one of your ready pull requests is merged, closed or turned into a draft, and reopen a closed pull request once you have fewer than 4 open. Maintainers and dependency bots are not counted.

Once your current pull requests are reviewed, merged, or closed, you are welcome to open new ones!

## Release Process (maintainers)

Stable releases ship on the **1st and 3rd Tuesday of each month**. Merging to `main` does
not cut a release: the change rides the next train, and reaches the `nightly` image on the
next nightly build.

Releases are cut from `main` with the **Release Cut** workflow
(Actions → Release Cut → Run workflow). semantic-release analyzes the
Conventional Commits since the last tag, bumps `pom.xml`, regenerates
`CHANGELOG.md`, commits, tags, and publishes the GitHub Release; the tag
push triggers the Docker publish pipeline. Use the `dry-run` input to
preview the next version and notes without releasing.

`CHANGELOG.md` is generated. **Do not edit it by hand.** Your Conventional
Commit message is the changelog entry. Genuine corrections to the file
require the `changelog-edit` label on the PR.

## Testing Policy for Pull Requests

floci-az accepts pull requests only when the test coverage is appropriate for the type of change being proposed.

As a project policy:

- Pull requests that introduce new behavior must include tests that validate that behavior.
- Pull requests that fix bugs should include a regression test whenever the bug can be covered realistically.
- Pull requests that modify runtime logic, request handling, persistence behavior, protocol compatibility, or service responses are expected to include updated or additional tests.
- Pull requests that do not change observable behavior, such as documentation updates, formatting, comments, dependency housekeeping, or low-risk internal refactors, may not require new tests.
- Even when no new tests are needed, the existing test suite must still pass.

If a pull request does not include new tests, the author should explain why in the PR description. Valid reasons may include:

- no functional behavior changed
- existing tests already cover the change
- the change is not meaningfully testable in isolation

Maintainers may request additional or more targeted test coverage before approving a PR.

CI runs automatically on every pull request, and build/test checks must pass before merge.

## Reporting Security Issues

Please do **not** open public issues for security vulnerabilities. Report them privately by emailing the maintainer or using [GitHub private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability).
