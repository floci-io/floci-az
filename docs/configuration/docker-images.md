# Docker Images

Floci-AZ publishes images to [Docker Hub (`floci/floci-az`)](https://hub.docker.com/r/floci/floci-az).

Every image tag combines two independent choices: **what's inside** (variant) and **how stable it is** (channel).

## Axis 1: Variant (what's inside)

| Variant | Contents | When to use |
|---|---|---|
| **Standard** | Floci-AZ native binary only | General use: CI, local dev, Testcontainers **(recommended)** |
| **Compat** | Floci-AZ + Python 3 + Azure CLI + `azfloci` | Workflows that need Azure tooling available inside the container |

The compat image runs the same native binary as the standard image, so startup time and memory footprint are identical. Only the image size increases.

The standard image is built on Red Hat UBI 9 micro. Besides Floci-AZ it contains only bash and coreutils. There is no package manager, curl, grep or sed inside. Pick the compat image when you need tools inside the container.

## Axis 2: Channel (how stable)

| Channel | Source | Published |
|---|---|---|
| **Release** | Tagged version (e.g. `x.y.z`) | 15th and last day of each month, 00:30 CT |
| **Nightly** | Tip of `main` | Every night |

Release images are stable and recommended for most use cases. Between trains, `nightly` carries every merged fix from the following morning. Nightly images track active development and may include unreleased changes.

## Full Tag Matrix

Combining both axes gives the complete set of published tags:

|  | Standard | Compat |
|---|---|---|
| **Release (latest)** | `latest` ✅ | `latest-compat` |
| **Release (pinned)** | `x.y.z` | `x.y.z-compat` |
| **Nightly (floating)** | `nightly` | `nightly-compat` |
| **Nightly (dated)** | `nightly-mmddyyyy` | `nightly-mmddyyyy-compat` |

Dated nightly tags (e.g. `nightly-05022026`) name one night's build of `main`. A same-day rerun of the nightly workflow republishes that day's tag, so for a build you can rely on not changing, pin a release version.

!!! warning
    Nightly images may include unreleased or experimental changes. Use release tags in production-like environments.

## Quick Reference

```yaml title="docker-compose.yml"
# Standard release : recommended
image: floci/floci-az:latest

# Compat release : includes the Azure CLI and azfloci
image: floci/floci-az:latest-compat

# Pinned release : reproducible builds
image: floci/floci-az:x.y.z

# Nightly : track main
image: floci/floci-az:nightly
```

## Multi-Architecture

Standard and compat images are published as multi-arch manifests supporting `linux/amd64` and `linux/arm64`.

## What's in the Compat Image

The compat image adds the following to the standard image contents:

- Python 3
- [Azure CLI](https://learn.microsoft.com/cli/azure/) (`az`), installed from Microsoft's RHEL 9 package repository
- [`azfloci`](../getting-started/azure-setup.md), the companion wrapper that points `az` at the local emulator

Use `azfloci` in place of `az` to run storage commands against the local emulator without passing a connection string, for example from a script that runs inside the container:

```sh
#!/bin/sh
azfloci storage container create --name my-container
azfloci storage queue create --name my-queue
```

`azfloci` targets `http://localhost:4577`, which is the emulator's address from inside the container. It does not change the plain `az` command: calling `az` directly still needs `--connection-string` or the `AZURE_STORAGE_CONNECTION_STRING` environment variable, as described in [Azure CLI & SDK Setup](../getting-started/azure-setup.md).
