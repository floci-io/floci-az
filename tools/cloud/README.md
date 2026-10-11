# Cloud catalog tooling

`gen_clouds.py` generates `src/main/resources/azure/clouds.json`, the catalog of the Azure clouds
floci-az serves (`AzureCloud`, `AzureUSGovernment`, `AzureChinaCloud`). The Java side reads it through
`io.floci.az.core.cloud.CloudCatalog`.

Every value comes from a file under `local/azure` (the reference checkouts that
`local/azure/sync-refs.sh` manages); the module docstring lists them. A value no local file settles is
written as `{"value": null, "source": "UNSETTLED: <what to verify>"}`, never as fact. The `_source`
block records each file read and the commit of its checkout.

| Command | What it does |
|---|---|
| `make az-data-sync` | Regenerate `clouds.json` from `local/azure`. Commit the result. |
| `make az-data-check` | With `local/azure` present: fail when `clouds.json` differs from a fresh generation. Without it (CI): verify the file's shape only. |
| `make az-data-test` | Run the generator's unit tests (`pytest`, see `requirements.txt`). |

`--local-root <dir>` points the generator at another checkout root; an empty directory exercises the
shape-only path.

To settle an `UNSETTLED` cell, add the source to the `local/azure/sync-refs.sh` manifest, teach the
generator to read it, and regenerate. Do not type the value into `clouds.json`.
