# Cloud catalog tooling

`gen_clouds.py` generates `src/main/resources/azure/clouds.json`, the catalog of the Azure clouds
floci-az serves (`AzureCloud`, `AzureUSGovernment`, `AzureChinaCloud`). The Java side reads it through
`io.floci.az.core.cloud.CloudCatalog`.

Every value comes from a file under `local/azure`, a directory of upstream checkouts that is not part
of this repository; the module docstring lists the files. A value no local file settles is written as
`{"value": null, "source": "UNSETTLED: <what to verify>"}`, never as fact. The `_source` block records
each file read and the commit of its checkout.

## Recreating the sources

Clone each repository below into `local/azure/<directory>` (or any root passed with `--local-root`).
The `_source.files` block of `clouds.json` names the commit each file was read at: check those commits
out to reproduce the vendored file exactly, or use newer ones to refresh it.

| Directory | Repository |
|---|---|
| `azure-cli` | [Azure/azure-cli](https://github.com/Azure/azure-cli) |
| `azure-docs` | [MicrosoftDocs/azure-docs](https://github.com/MicrosoftDocs/azure-docs) |
| `azure-rest-api-specs` | [Azure/azure-rest-api-specs](https://github.com/Azure/azure-rest-api-specs) |
| `azure-sdk-for-java` | [Azure/azure-sdk-for-java](https://github.com/Azure/azure-sdk-for-java) |
| `azure-sdk-for-net` | [Azure/azure-sdk-for-net](https://github.com/Azure/azure-sdk-for-net) |
| `entra-docs` | [MicrosoftDocs/entra-docs](https://github.com/MicrosoftDocs/entra-docs) |
| `go-azure-sdk` | [hashicorp/go-azure-sdk](https://github.com/hashicorp/go-azure-sdk) |
| `reliability-docs` | [MicrosoftDocs/reliability-docs](https://github.com/MicrosoftDocs/reliability-docs) |

## Commands

| Command | What it does |
|---|---|
| `make az-data-sync` | Regenerate `clouds.json` from `local/azure`. Commit the result. |
| `make az-data-check` | With `local/azure` present: fail when `clouds.json` differs from a fresh generation. Without it (CI): verify the file's shape only. |
| `make az-data-test` | Run the generator's unit tests (`pytest`, see `requirements.txt`). |

`--local-root <dir>` points the generator at another checkout root; an empty directory exercises the
shape-only path.

To settle an `UNSETTLED` cell, check out a source that states the value under `local/azure`, add its
repository to the table above, teach the generator to read it, and regenerate. Do not type the value
into `clouds.json`.

## Literal ratchet

`literals.py` keeps hardcoded single-cloud and single-scope assumptions in `src/main/java` from
growing back. It counts four categories per file and gates them against `literals-baseline.tsv`:

| Category | What counts |
|---|---|
| `cloud-literal` | a string literal containing a host or DNS suffix of any cloud in `clouds.json` (service suffixes, endpoint and audience hosts, and the parent zones they share except `microsoft.com`), so the patterns follow the catalog |
| `location-literal` | the literal `"eastus"` |
| `scope-parse` | under `services/`: `ArmPaths.subscription(`, `ArmPaths.resourceGroup(`, `ArmScope.of(` and `segmentAfter(..., "subscriptions"/"resourcegroups")` |
| `name-guard` | a declaration (method, field or local) named `ownedElsewhere`, `foreignServer` or `nameClaims`: one per guard copy, never its call sites |

Comments never count. A new file with an occurrence fails, a count that grew fails, and a count that
dropped also fails until the baseline is regenerated, so the remaining number stays exact and shows
in the diff. A genuinely cloud-invariant occurrence is excused by an entry with a reason in
`literals-allowlist.yaml`, or by ending its line with `// cloud-literal: <reason>`; the audit prints
every escape.

| Command | What it does |
|---|---|
| `make cloud-literals-check` | CI gate: fail when any per-file count differs from the baseline. |
| `make cloud-literals-baseline` | Regenerate `literals-baseline.tsv` after removing occurrences. Commit the result. |
| `make cloud-literals-audit` | Print the per-package table, the baseline totals, the escapes and the allow-listed hits. |

The unit tests run with `make az-data-test`. PyYAML is optional: without it the script reads the
allow-list's own simple shape.
