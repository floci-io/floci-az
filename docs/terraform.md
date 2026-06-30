# Terraform / OpenTofu (azurerm provider)

You can run the `hashicorp/azurerm` provider against floci-az for local-first IaC —
`plan` / `apply` / `destroy` against the emulator instead of real Azure.

> **TLS is required.** The azurerm provider discovers the cloud over **HTTPS**
> (`GET https://<host>/metadata/endpoints`). floci-az serves plain HTTP by default, so you
> **must** enable TLS or the provider fails before it sends a single resource request. This is
> the single most common setup mistake (see [Troubleshooting](#troubleshooting)).

---

## 1 — Start floci-az with TLS enabled

```yaml
# docker-compose.yml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock   # for container-backed services (SQL, Postgres, …)
      - ./data:/app/data                            # persist the generated cert across restarts
    environment:
      FLOCI_AZ_TLS_ENABLED: "true"
```

floci-az serves HTTP and HTTPS on the **same** port (4577) via a protocol-sniffing proxy.
A self-signed certificate is generated at startup and served at `GET /_floci/tls-cert`.

## 2 — Trust the certificate

The provider validates the TLS chain, so the self-signed cert must be trusted by the machine
running `tofu`/`terraform`:

```bash
curl -sf http://localhost:4577/_floci/tls-cert -o floci-az.crt
# Linux: copy into the system trust store, then refresh
sudo cp floci-az.crt /usr/local/share/ca-certificates/ && sudo update-ca-certificates
```

(Adjust for your OS trust store; on a CI runner you typically install it the same way.)

## 3 — Configure the provider

```hcl
terraform {
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = ">= 3.0"   # works with the 3.x and 4.x provider lines
    }
  }
}

provider "azurerm" {
  features {}
  skip_provider_registration = true
  use_cli                    = false

  environment   = "stack"            # use a custom cloud described by metadata_host
  metadata_host = "localhost:4577"   # floci-az serves /metadata/endpoints over HTTPS here

  subscription_id = "00000000-0000-0000-0000-000000000001"
  tenant_id       = "00000000-0000-0000-0000-000000000002"
  client_id       = "00000000-0000-0000-0000-000000000003"
  client_secret   = "fake-secret"    # credentials are not validated in dev auth mode
}
```

Then the usual flow works:

```bash
tofu init
tofu apply
tofu destroy
```

See the `compatibility-tests/compat-opentofu` directory in the repo for a complete, CI-verified
example (resource group, storage, Key Vault, VNet, VM, Redis, ACR, PostgreSQL Flexible Server).

---

## Troubleshooting

**`http: server gave HTTP response to HTTPS client`** (or `tls: first record does not look
like a TLS handshake`) when the provider configures itself:

```
Configuring cloud environment from Metadata Service at localhost:4577
```

→ TLS is not enabled. floci-az is answering the provider's HTTPS metadata probe with plain HTTP.
**Fix:** set `FLOCI_AZ_TLS_ENABLED=true`, restart, and trust the cert (steps 1–2 above).

**`x509: certificate signed by unknown authority`**

→ TLS is enabled but the self-signed certificate is not trusted on the machine running the
provider. Re-do step 2, or point `SSL_CERT_FILE` at the downloaded `floci-az.crt`.

**`GET /_floci/tls-cert` returns `"tlsEnabled": false`**

→ Confirms TLS is off. Enable it as in step 1.
