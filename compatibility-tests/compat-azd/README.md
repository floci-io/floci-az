# compat-azd

The real Azure Developer CLI (`azd`) against floci-az. azd has no custom-cloud support: it always calls
`management.azure.com` and `login.microsoftonline.com`. So the emulator runs with `FLOCI_AZ_TLS_PUBLIC_HOSTS=true`, and
this container trusts its CA and sends those hosts to it through `/etc/hosts` and `socat`.

```bash
make test-azd
```

The tests run in file order and share state: `00-auth` logs in, and later files use that login.

| File | Covers |
|---|---|
| `00-auth.bats` | service principal login, a token for each ARM scope |
| `10-env.bats` | `azd env`, `azd config` |
| `20-terraform.bats` | `azd provision` and `azd down` with `infra.provider: terraform` |
| `30-bicep.bats` | subscription-scope Bicep with modules, `--preview`, the no-change skip, `azd down --purge` |
| `40-containerapp.bats` | `azd deploy` of a container app: the app found by its `azd-service-name` tag, a new `azd-<unix>` revision with the service image, the secret values kept |

The image pins azd 1.36.0 and Bicep 0.48.1, matching `local/azure/azure-dev`'s tag.
`40-containerapp.bats` deploys a published image with `docker.imagePassthrough`, so it needs no Docker daemon. Not
covered yet: `azd deploy` with a Docker build and a push to the registry.
