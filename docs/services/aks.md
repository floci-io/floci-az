# Azure Kubernetes Service (AKS)

Compatible with the `azure-mgmt-containerservice` SDK, the `az aks` CLI, Terraform's `azurerm_kubernetes_cluster`, and any ARM-speaking client.

> **Requires Docker** (in real mode) — each AKS cluster maps to one `rancher/k3s` container.
> Set `FLOCI_AZ_SERVICES_AKS_MOCKED=true` for a lightweight mock that skips Docker entirely.

---

## Features

- **Clusters** — CreateOrUpdate, Get, Delete, List (by subscription and by resource group), UpdateTags
- **Agent pools** — List, Get, CreateOrUpdate, Delete
- **Credentials** — `listClusterAdminCredential`, `listClusterUserCredential` return a base64-encoded kubeconfig
- **Real k3s mode** — a privileged k3s container starts per cluster; kubeconfig with real CA is extracted and returned
- **Mocked mode** — clusters transition immediately to `Succeeded` with a synthetic kubeconfig; no Docker required
- **instanceId-based naming** — each cluster gets an 8-char UUID prefix, preventing container name collisions across resource groups

---

## Endpoints

All operations use ARM paths:

```
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}
PATCH  /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters
GET    /subscriptions/{sub}/providers/Microsoft.ContainerService/managedClusters
POST   .../managedClusters/{name}/listClusterAdminCredential
POST   .../managedClusters/{name}/listClusterUserCredential
GET    .../managedClusters/{name}/agentPools
GET    .../managedClusters/{name}/agentPools/{poolName}
PUT    .../managedClusters/{name}/agentPools/{poolName}
DELETE .../managedClusters/{name}/agentPools/{poolName}
```

---

## Quickstart

### 1 — Create a cluster

```bash
curl -s -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.ContainerService/managedClusters/my-cluster?api-version=2024-04-01" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "kubernetesVersion": "1.29",
      "dnsPrefix": "my-cluster-dns",
      "agentPoolProfiles": [
        {
          "name": "nodepool1",
          "count": 1,
          "vmSize": "Standard_DS2_v2",
          "osType": "Linux",
          "mode": "System"
        }
      ]
    }
  }'
```

In real mode, `provisioningState` is `"Creating"` until k3s is ready (30–90 s). Poll with GET until `"Succeeded"`.
In mocked mode, the response immediately shows `"Succeeded"`.

### 2 — Poll until ready (real mode only)

```bash
while true; do
  STATE=$(curl -s "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.ContainerService/managedClusters/my-cluster?api-version=2024-04-01" \
          | python3 -c "import sys,json; print(json.load(sys.stdin)['properties']['provisioningState'])")
  echo "provisioningState: $STATE"
  [ "$STATE" = "Succeeded" ] && break
  sleep 5
done
```

### 3 — Get the kubeconfig

```bash
KUBECONFIG_B64=$(curl -s -X POST \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.ContainerService/managedClusters/my-cluster/listClusterAdminCredential?api-version=2024-04-01" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['kubeconfigs'][0]['value'])")

echo "$KUBECONFIG_B64" | base64 -d > ~/.kube/my-cluster.yaml
kubectl --kubeconfig ~/.kube/my-cluster.yaml get nodes
```

In real mode the kubeconfig points to the live k3s API server. In mocked mode it points to `https://localhost:6443` with `insecure-skip-tls-verify: true`.

### 4 — Delete the cluster

```bash
curl -s -X DELETE \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.ContainerService/managedClusters/my-cluster?api-version=2024-04-01"
# returns 202 Accepted; the k3s container and its volume are removed immediately
```

---

## SDK Integration

=== "Java"

    ```java
    // pom.xml:
    // <dependency>
    //   <groupId>com.azure.resourcemanager</groupId>
    //   <artifactId>azure-resourcemanager-containerservice</artifactId>
    //   <version>2.40.0</version>
    // </dependency>

    import com.azure.core.credential.TokenCredential;
    import com.azure.core.management.AzureEnvironment;
    import com.azure.core.management.profile.AzureProfile;
    import com.azure.identity.DefaultAzureCredentialBuilder;
    import com.azure.resourcemanager.containerservice.ContainerServiceManager;
    import com.azure.resourcemanager.containerservice.models.KubernetesCluster;

    AzureProfile profile = new AzureProfile(
        "tenant-id", "subscription-id", AzureEnvironment.AZURE);
    TokenCredential credential = new DefaultAzureCredentialBuilder()
        .authorityHost("http://localhost:4577/")  // point to floci-az
        .build();

    ContainerServiceManager manager = ContainerServiceManager
        .authenticate(credential, profile);

    KubernetesCluster cluster = manager.kubernetesClusters()
        .define("my-cluster")
        .withRegion("eastus")
        .withExistingResourceGroup("my-rg")
        .withDefaultVersion()
        .withSystemAssignedManagedServiceIdentity()
        .defineAgentPool("nodepool1")
            .withVirtualMachineSize(ContainerServiceVMSizeTypes.STANDARD_DS2_V2)
            .withAgentPoolMode(AgentPoolMode.SYSTEM)
            .withAgentPoolType(AgentPoolType.VIRTUAL_MACHINE_SCALE_SETS)
            .withOSType(OSType.LINUX)
            .withAgentPoolVirtualMachineCount(1)
            .attach()
        .create();
    ```

=== "Python"

    ```python
    from azure.identity import DefaultAzureCredential
    from azure.mgmt.containerservice import ContainerServiceClient
    from azure.mgmt.containerservice.models import (
        ManagedCluster, ManagedClusterAgentPoolProfile, ContainerServiceVMSizeTypes
    )

    credential = DefaultAzureCredential()
    client = ContainerServiceClient(
        credential=credential,
        subscription_id="my-sub",
        base_url="http://localhost:4577",
    )

    poller = client.managed_clusters.begin_create_or_update(
        resource_group_name="my-rg",
        resource_name="my-cluster",
        parameters=ManagedCluster(
            location="eastus",
            kubernetes_version="1.29",
            dns_prefix="my-cluster-dns",
            agent_pool_profiles=[
                ManagedClusterAgentPoolProfile(
                    name="nodepool1",
                    count=1,
                    vm_size=ContainerServiceVMSizeTypes.STANDARD_DS2_V2,
                    mode="System",
                )
            ],
        ),
    )
    cluster = poller.result()
    print(cluster.provisioning_state)
    ```

=== "Azure CLI"

    ```bash
    az aks create \
      --subscription my-sub \
      --resource-group my-rg \
      --name my-cluster \
      --location eastus \
      --node-count 1 \
      --generate-ssh-keys \
      --output table

    az aks get-credentials \
      --subscription my-sub \
      --resource-group my-rg \
      --name my-cluster \
      --file ~/.kube/my-cluster.yaml
    ```

---

## Real vs Mocked Mode

| | Real mode (`mocked=false`) | Mocked mode (`mocked=true`) |
|---|---|---|
| Docker required | Yes | No |
| `provisioningState` after create | `Creating` → polled to `Succeeded` | Immediately `Succeeded` |
| Kubeconfig | Extracted from k3s — real CA, real server URL | Synthetic — `insecure-skip-tls-verify: true` |
| `kubectl` connectivity | Yes — points at live k3s API server | No — k3s not running |
| Container | `floci-az-aks-{instanceId}` (privileged k3s) | None |
| Use case | Local development, integration tests | Unit tests, CI without Docker |

---

## Configuration

```yaml
floci-az:
  services:
    aks:
      enabled: true
      mocked: false             # true = no Docker; false = real k3s (default)
      default-image: "rancher/k3s:latest"
      api-server-base-port: 6443
      api-server-max-port: 7443
      keep-running-on-shutdown: false
```

| Environment Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_AKS_ENABLED` | `true` | Enable or disable the AKS service |
| `FLOCI_AZ_SERVICES_AKS_MOCKED` | `false` | `true` = skip Docker, synthetic kubeconfig |
| `FLOCI_AZ_SERVICES_AKS_DEFAULT_IMAGE` | `rancher/k3s:latest` | k3s Docker image |
| `FLOCI_AZ_SERVICES_AKS_API_SERVER_BASE_PORT` | `6443` | Start of host port range for k3s API servers |
| `FLOCI_AZ_SERVICES_AKS_API_SERVER_MAX_PORT` | `7443` | End of host port range for k3s API servers |
| `FLOCI_AZ_SERVICES_AKS_KEEP_RUNNING_ON_SHUTDOWN` | `false` | Leave k3s containers running when floci-az stops |

---

## Docker Compose

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
      - "6443-6450:6443-6450"   # k3s API server ports (one per cluster)
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock   # required for real k3s mode
    environment:
      FLOCI_AZ_SERVICES_AKS_MOCKED: "false"
      # k3s containers bind to host ports 6443–7443 via Docker daemon.
      # Publish the range you need above.
```

> **Sidecar ports:** k3s containers bind a host port in the `6443–7443` range directly via the Docker daemon.
> Publish the port range on the `floci-az` service if your application needs to reach the k3s API server from outside Docker.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Your App                                                    │
│                                                              │
│  ARM REST calls ──────► floci-az :4577 ──► AksHandler        │
│  (create cluster,                         (state, routing)   │
│   list clusters,                                             │
│   get credentials)                                           │
│                                                              │
│  kubectl / k8s client ────────────────────────────────────►  │
│                               k3s container :6443            │
│                               (floci-az-aks-{instanceId})    │
└──────────────────────────────────────────────────────────────┘
```

The management plane (ARM API) goes through floci-az on port 4577.
The data plane (kubectl, Kubernetes API) connects **directly** to the k3s container on its allocated port — floci-az is not in the data path.
