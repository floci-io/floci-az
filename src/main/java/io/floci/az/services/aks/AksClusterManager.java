package io.floci.az.services.aks;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.PortAllocator;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages the Docker lifecycle of k3s containers backing AKS clusters.
 * Not used when {@code floci-az.services.aks.mocked=true}.
 */
@ApplicationScoped
public class AksClusterManager {

    private static final Logger LOG = Logger.getLogger(AksClusterManager.class);
    private static final int K3S_API_SERVER_PORT = 6443;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;

    @Inject
    public AksClusterManager(ContainerBuilder containerBuilder,
                              ContainerLifecycleManager lifecycleManager,
                              ContainerDetector containerDetector,
                              PortAllocator portAllocator,
                              EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
        this.config = config;
    }

    /**
     * Starts a k3s container for the given cluster. Sets containerId and endpoint on the cluster.
     * Status remains "Creating" until {@link #isReady} returns true and {@link #finalizeCluster} is called.
     */
    public void startCluster(ManagedCluster cluster) {
        String image = config.services().aks().defaultImage();
        String containerName = containerName(cluster);

        LOG.infov("Starting k3s container for AKS cluster: {0} using image {1}", cluster.getName(), image);

        int hostPort = portAllocator.allocate(
                config.services().aks().apiServerBasePort(),
                config.services().aks().apiServerMaxPort());

        lifecycleManager.removeIfExists(containerName);

        // Named volume for k3s data — prevents macOS APFS chmod(EINVAL) that crashes kine.
        String volumeName = containerName;
        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(containerName)
                .withCmd(List.of("server",
                        "--disable=traefik",
                        "--tls-san=localhost"))
                .withEnv("K3S_KUBECONFIG_MODE", "644")
                .withPortBinding(K3S_API_SERVER_PORT, hostPort)
                .withNamedVolume(volumeName, "/var/lib/rancher/k3s")
                .withDockerNetwork(config.services().dockerNetwork())
                .withPrivileged(true)
                .withLogRotation()
                .build();

        ContainerLifecycleManager.ContainerInfo info = lifecycleManager.createAndStart(spec);
        cluster.setContainerId(info.containerId());

        if (containerDetector.isRunningInContainer()) {
            cluster.setEndpoint("https://" + containerName + ":" + K3S_API_SERVER_PORT);
            ContainerLifecycleManager.EndpointInfo ep = info.getEndpoint(K3S_API_SERVER_PORT);
            cluster.setInternalEndpoint(ep != null
                    ? "https://" + ep.host() + ":" + ep.port()
                    : cluster.getEndpoint());
        } else {
            cluster.setEndpoint("https://localhost:" + hostPort);
            cluster.setInternalEndpoint(cluster.getEndpoint());
        }

        // FQDN reflects the actual API server endpoint for kubectl to use
        cluster.setFqdn(cluster.getEndpoint().replace("https://", ""));

        LOG.infov("k3s container {0} started for cluster {1} on port {2} (internal: {3})",
                info.containerId(), cluster.getName(), hostPort, cluster.getInternalEndpoint());
    }

    /** Polls the k3s /livez endpoint to detect readiness. */
    public boolean isReady(ManagedCluster cluster) {
        String endpoint = cluster.getInternalEndpoint() != null
                ? cluster.getInternalEndpoint()
                : cluster.getEndpoint();
        if (endpoint == null || cluster.getContainerId() == null) {
            return false;
        }
        String livezUrl = endpoint + "/livez";
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(livezUrl).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            if (conn instanceof javax.net.ssl.HttpsURLConnection https) {
                disableSslVerification(https);
            }
            int code = conn.getResponseCode();
            return code == 200 || code == 401 || code == 403;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extracts the kubeconfig from the running k3s container,
     * storing it base64-encoded on the cluster and updating the CA data.
     */
    public void finalizeCluster(ManagedCluster cluster) {
        String containerId = cluster.getContainerId();
        if (containerId == null) {
            return;
        }
        try {
            String kubeconfigYaml = execInContainer(containerId,
                    new String[]{"cat", "/etc/rancher/k3s/k3s.yaml"});

            // Rewrite the server URL to the public endpoint
            String publicEndpoint = cluster.getEndpoint();
            String rewritten = kubeconfigYaml.replaceAll(
                    "server: https://[^\\n]+",
                    "server: " + publicEndpoint);

            cluster.setKubeconfig(Base64.getEncoder().encodeToString(
                    rewritten.getBytes(StandardCharsets.UTF_8)));

            String caData = extractYamlField(kubeconfigYaml, "certificate-authority-data");
            if (caData != null) {
                cluster.setCaData(caData.trim());
            }

            LOG.infov("Finalized AKS cluster {0} with kubeconfig and CA data", cluster.getName());
        } catch (Exception e) {
            LOG.warnv("Could not extract kubeconfig for cluster {0}: {1}",
                    cluster.getName(), e.getMessage());
        }
    }

    /** Stops and removes the k3s container for the given cluster. */
    public void stopCluster(ManagedCluster cluster) {
        if (cluster.getContainerId() == null) {
            return;
        }
        if (config.services().aks().keepRunningOnShutdown()) {
            LOG.infov("Leaving k3s container for AKS cluster {0} running", cluster.getName());
            return;
        }
        lifecycleManager.stopAndRemove(cluster.getContainerId(), null);
        lifecycleManager.removeVolume(containerName(cluster));
        LOG.infov("Stopped k3s container for AKS cluster {0}", cluster.getName());
    }

    private static String containerName(ManagedCluster cluster) {
        return "floci-az-aks-" + cluster.getInstanceId();
    }

    private String execInContainer(String containerId, String[] cmd) throws Exception {
        var dockerClient = lifecycleManager.getDockerClient();
        ExecCreateCmdResponse exec = dockerClient
                .execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        StringBuilder output = new StringBuilder();
        boolean completed = dockerClient.execStartCmd(exec.getId())
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }
                })
                .awaitCompletion(10, TimeUnit.SECONDS);

        if (!completed) {
            throw new RuntimeException("exec timed out in container " + containerId);
        }
        return output.toString();
    }

    private static String extractYamlField(String yaml, String fieldName) {
        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(fieldName + ":")) {
                return trimmed.substring(fieldName.length() + 1).trim();
            }
        }
        return null;
    }

    @SuppressWarnings("java:S4830")
    private void disableSslVerification(javax.net.ssl.HttpsURLConnection conn) {
        try {
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            conn.setSSLSocketFactory(sc.getSocketFactory());
            conn.setHostnameVerifier((h, s) -> true);
        } catch (Exception e) {
            LOG.debugv("Could not disable SSL verification: {0}", e.getMessage());
        }
    }
}
