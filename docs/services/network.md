# Azure Virtual Network

Compatible with ARM-speaking clients, Terraform's AzureRM provider, and OpenTofu resources that provision basic `Microsoft.Network` dependencies for local VM workflows.

> **No Docker required.** Network is an in-process ARM control-plane emulator. It stores resource state and returns Azure-shaped responses, but it does not create real routing, packet isolation, firewall enforcement, or IP allocation.

---

## Features

- **Virtual networks** — CreateOrUpdate, Get, Delete, and List by resource group
- **Subnets** — CreateOrUpdate, Get, Delete, and List under a virtual network
- **Network interfaces** — CreateOrUpdate, Get, Delete, and List by resource group
- **Public IP addresses** — CreateOrUpdate, Get, Delete, and List by resource group
- **Network security groups** — CreateOrUpdate, Get, Delete, and List by resource group
- **Private DNS zones** — CreateOrUpdate, Get, Delete, and List, with record sets (A, AAAA, CNAME, MX, PTR, SOA, SRV, TXT); a default SOA record set is seeded on creation, record/link counts are tracked, and ETag (`If-Match` / `If-None-Match`) concurrency is enforced
- **Private DNS virtual network links** — CreateOrUpdate, Get, Delete, and List under a private DNS zone; links report `virtualNetworkLinkState = "Completed"`
- **Private endpoints** — CreateOrUpdate, Get, Delete, and List; `privateLinkServiceConnections` are auto-approved, a backing network interface with a synthesized private IP is created, and nested `privateDnsZoneGroups` are supported
- **Private link services** — CreateOrUpdate, Get, Delete, and List, with a synthesized `alias`
- **Terraform/OpenTofu compatibility** — supports the Network resources needed by `azurerm_linux_virtual_machine`, `azurerm_private_dns_zone`, `azurerm_private_dns_zone_virtual_network_link`, and `azurerm_private_endpoint`
- **Resource group listing** — Network resources appear in ARM resource group resource listings

Created resources return `properties.provisioningState = "Succeeded"`. NICs synthesize a dynamic private IP (`10.0.0.4`) when no private IP is supplied, and public IP resources synthesize a dynamic public IP (`20.0.0.4`) when no address is supplied. Creating a private endpoint likewise synthesizes a backing network interface with a `10.0.0.4` private IP.

---

## Endpoints

All operations use ARM paths:

```text
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/virtualNetworks
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/virtualNetworks/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/virtualNetworks/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/virtualNetworks/{name}

GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/virtualNetworks/{vnet}/subnets
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/virtualNetworks/{vnet}/subnets/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/virtualNetworks/{vnet}/subnets/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/virtualNetworks/{vnet}/subnets/{name}

PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/networkInterfaces/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/networkInterfaces/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/networkInterfaces/{name}

PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/publicIPAddresses/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/publicIPAddresses/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/publicIPAddresses/{name}

PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/networkSecurityGroups/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/networkSecurityGroups/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/networkSecurityGroups/{name}

PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateDnsZones/{zone}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateDnsZones/{zone}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateDnsZones/{zone}
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateDnsZones/{zone}/{recordType}/{record}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateDnsZones/{zone}/recordsets
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateDnsZones/{zone}/virtualNetworkLinks/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateDnsZones/{zone}/virtualNetworkLinks/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateDnsZones/{zone}/virtualNetworkLinks/{name}

PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateEndpoints/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateEndpoints/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateEndpoints/{name}
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateEndpoints/{pe}/privateDnsZoneGroups/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateEndpoints/{pe}/privateDnsZoneGroups/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateEndpoints/{pe}/privateDnsZoneGroups/{name}

PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateLinkServices/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateLinkServices/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/privateLinkServices/{name}
```

---

## Terraform And OpenTofu

The compatibility suites exercise Network through the same local emulator path used in CI:

```bash
make test-terraform-compat
make test-opentofu-compat
```

If port `4577` is already in use, run the suites against another emulator port:

```bash
make PORT=4578 test-terraform
make PORT=4578 test-opentofu
```

The Network coverage lives in:

- `compatibility-tests/compat-terraform`
- `compatibility-tests/compat-opentofu`

The current Network scope is enough for Terraform/OpenTofu to create and destroy a resource group with VNet, subnet, NIC, public IP, NSG, a VM that references the NIC, and a Private Link stack (private DNS zone + virtual network link + private endpoint with a private DNS zone group).

---

## Configuration

```yaml
floci-az:
  services:
    network:
      enabled: true       # Microsoft.Network — VNet, subnets, NIC, public IP, NSG, and DNS zones
    arm:
      enabled: true       # central management plane; disabling it turns OFF all ARM-based services
```

| Environment Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_NETWORK_ENABLED` | `true` | Enable/disable all of Microsoft.Network (VNet, subnets, NIC, public IP, NSG, **and DNS zones**). When disabled, `/providers/Microsoft.Network/...` calls return `404 ResourceNotFound`; the rest of ARM keeps working. |
| `FLOCI_AZ_SERVICES_ARM_ENABLED` | `true` | Enable/disable the ARM management plane itself (`/providers`, `/subscriptions`, resource groups). **Disabling it turns off every ARM-based service** (vm, aks, sql, redis, acr, servicebus, apim, monitor, network, storage/keyvault ARM) — use only to fully shut down the management plane. |

## Scope And Limitations

- No real L2/L3 networking, routing, peering, DNS, packet forwarding, or service endpoints
- No NSG rule enforcement; NSG resources are stored as ARM state only
- No route table, NAT gateway, load balancer, or application gateway behavior
- Private endpoints and private DNS zones are ARM-state only — a backing NIC with a synthesized `10.0.0.4` is created and connections are auto-approved, but there is no real private-link traffic, name registration, or DNS resolution against the private zone records
- No real IP address management; default private and public IPs are synthesized for SDK and provider compatibility
- Deletes are state-only; deleting a VNet also removes its child subnets from the in-memory store

The goal is API parity for local provisioning workflows, especially SDK, Azure CLI, Terraform, and OpenTofu flows that need Network dependencies before creating other Azure resources.
