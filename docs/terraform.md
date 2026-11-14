# Terraform / OpenTofu (azurerm provider)

You can run the `hashicorp/azurerm` provider against floci-az for local-first IaC:
`plan` / `apply` / `destroy` against the emulator instead of real Azure.

> **TLS is required.** The azurerm provider discovers the cloud over **HTTPS**
> (`GET https://<host>/metadata/endpoints`). floci-az serves plain HTTP by default, so you
> **must** enable TLS or the provider fails before it sends a single resource request. This is
> the single most common setup mistake (see [Troubleshooting](#troubleshooting)).

---

## 1: Start floci-az with TLS enabled

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

## 2: Trust the certificate

The provider validates the TLS chain, so every machine that runs `terraform`/`tofu` must trust
floci-az's CA. Download it once (the port answers plain HTTP too):

```bash
curl -sf http://localhost:4577/_floci/tls-cert -o floci-az.crt
```

Then add it to the trust store of the OS that runs the provider:

=== "macOS"

    ```bash
    sudo security add-trusted-cert -d -r trustRoot \
      -k /Library/Keychains/System.keychain floci-az.crt
    ```

    On macOS the provider verifies certificates through the Keychain only: `SSL_CERT_FILE` and
    `SSL_CERT_DIR` are ignored there.

=== "Linux"

    ```bash
    # Debian / Ubuntu (other distributions: /etc/pki/ca-trust/source/anchors + update-ca-trust)
    sudo cp floci-az.crt /usr/local/share/ca-certificates/ && sudo update-ca-certificates
    ```

=== "Windows"

    ```powershell
    # From an elevated prompt
    certutil -addstore -f ROOT floci-az.crt
    ```

The startup log prints the same URL (`TLS: on, self-signed. Clients must trust the CA at ...`).

**Trust it once.** floci-az keeps the CA and its key under `tls/` in the data directory and
issues every later server certificate from it: when the certificate nears expiry, or after a
hostname change (`FLOCI_AZ_HOSTNAME`, `FLOCI_AZ_BASE_URL`) to a name the CA covers, the CA you
trusted stays valid. The CA
carries name constraints, so it can only vouch for the names floci-az serves (`localhost`,
`*.vault.azure.net`, `*.azurecr.io`, each cloud's Redis suffix such as `*.redis.cache.windows.net`,
private and loopback addresses,
and your configured hostnames), not for any other site, even if its key leaks.

floci-az generates a new CA, and logs a `WARN` saying so, in three cases: the data directory is not
persisted (the `./data:/app/data` volume above), a new hostname falls outside the existing CA's
constraints, or a name the CA covers is no longer served (a hostname you removed, or
`FLOCI_AZ_TLS_PUBLIC_HOSTS` turned off), so the CA you trust never vouches for more than floci-az
answers for. Download and trust it again then. On macOS, remove the old one first so the
Keychain does not keep both:
`sudo security delete-certificate -c floci-az-ca /Library/Keychains/System.keychain`.

## 3: Configure the provider

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
  use_cli = false

  environment   = "stack"            # use a custom cloud described by metadata_host
  metadata_host = "localhost:4577"   # floci-az serves /metadata/endpoints over HTTPS here

  subscription_id = "00000000-0000-0000-0000-000000000001"
  tenant_id       = "00000000-0000-0000-0000-000000000002"
  client_id       = "11111111-1111-1111-1111-111111111111"   # the seeded dev app
  client_secret   = "floci-az-dev-secret"   # checked only when entra.validate-client-credentials is on
}
```

No `skip_provider_registration` (3.x) or `resource_provider_registrations = "none"` (4.x) is needed:
floci-az lists every namespace it serves as already registered, so the provider has nothing to register.
See [Resource providers](services/arm.md#resource-providers).

Then the usual flow works:

```bash
tofu init
tofu apply
tofu destroy
```

See the `compatibility-tests/compat-opentofu` directory in the repo for a complete, CI-verified
example (resource group, storage, Key Vault, VNet, VM, Redis, ACR, PostgreSQL Flexible Server).

## Data-plane hostnames

Most resources talk only to the management endpoint (`localhost:4577`). A few also call a
service's data plane at its real Azure hostname, on the standard port:

| Resource | Calls | Port |
|---|---|---|
| `azurerm_key_vault` and its secrets, keys and certificates | `https://<vault>.vault.azure.net/` | 443 (HTTPS) |
| `azurerm_storage_blob`, and `azurerm_storage_container` / `azurerm_storage_queue` when given `storage_account_name` | `http://<account>.blob.core.windows.net/`, `http://<account>.queue.core.windows.net/` | 80 (HTTP) |

floci-az answers those hostnames on its own port, so two things make them work:

1. **Resolve each name to floci-az.** `/etc/hosts` takes no wildcards, so list every vault and
   account the configuration creates:

    ```text
    127.0.0.1  myvault.vault.azure.net  myaccount.blob.core.windows.net  myaccount.queue.core.windows.net
    ```

2. **Publish ports 80 and 443 to floci-az.** Both can go to the main port, which tells HTTP and
   HTTPS apart:

    ```yaml
        ports:
          - "4577:4577"
          - "80:4577"
          - "443:4577"
    ```

The self-signed certificate already covers `*.vault.azure.net`. The CI suites do the same with
`/etc/hosts` entries and `socat` (`compatibility-tests/compat-terraform/run-bats-in-container.sh`).

## Running Terraform in Docker

To leave the host trust store alone, run Terraform in a container that trusts the CA. The
`hashicorp/terraform` image ships `update-ca-certificates`, so mount the downloaded CA where it
looks and refresh the bundle before running:

```bash
curl -sf http://localhost:4577/_floci/tls-cert -o floci-az.crt
docker run --rm -it --network <floci-az network> \
  -v "$PWD:/work" -w /work \
  -v "$PWD/floci-az.crt:/usr/local/share/ca-certificates/floci-az.crt:ro" \
  --entrypoint sh hashicorp/terraform:1.9.8 \
  -c 'update-ca-certificates >/dev/null 2>&1 && terraform init && terraform apply'
```

From inside that network the provider reaches floci-az by its service name, so set
`metadata_host = "floci-az:4577"` and start floci-az with `FLOCI_AZ_HOSTNAME=floci-az`, which adds
that name to the certificate. Set it before you trust the CA: a name added later falls outside the
CA's constraints and makes floci-az generate a new one.

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

→ TLS is enabled but the machine running the provider does not trust floci-az's CA, or trusts an
older one. Re-do step 2. On Linux you can instead point `SSL_CERT_FILE` at a bundle that holds the
system roots plus `floci-az.crt` (`terraform init` still needs the public roots to download
providers). On macOS `SSL_CERT_FILE` has no effect: use the Keychain.

**`GET /_floci/tls-cert` returns `"tlsEnabled": false`**

→ Confirms TLS is off. Enable it as in step 1.

**`azurerm_key_vault` hangs, then `retrieving contact for KeyVault` or `context deadline exceeded`**

→ azurerm calls the vault's data plane at `https://{name}.vault.azure.net/`, on port 443, and that
name does not reach floci-az by default. A vault with public network access (the provider's default)
polls that URL after creating it and fails if it cannot read the certificate contacts there.
**Fix:** make the vault's name resolve to floci-az, for example with an `/etc/hosts` entry or a
Docker network alias, and publish or forward port 443. The self-signed certificate already covers
`*.vault.azure.net`. See [Key Vault](services/key-vault.md) for the details.

With `public_network_access_enabled = false` the provider skips the poll and ignores a failed
contacts read, though that read may still wait for its timeout. floci-az stores this setting but
does not enforce it, so the vault stays reachable.
