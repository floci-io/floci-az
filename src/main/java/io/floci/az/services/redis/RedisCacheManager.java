package io.floci.az.services.redis;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.ContainerStorageHelper;
import io.floci.az.core.docker.PortAllocator;
import io.floci.az.core.tls.SidecarCertificates;
import io.floci.az.services.redis.RedisModels.RedisCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages the Docker lifecycle of Redis containers backing Azure Cache for Redis caches.
 * Not used when {@code floci-az.services.redis.mocked=true}.
 */
@ApplicationScoped
public class RedisCacheManager {

    private static final Logger LOG = Logger.getLogger(RedisCacheManager.class);
    private static final int REDIS_PORT = 6379;
    /** The container port TLS is served on: Azure's SSL port, so container mode reports it as is. */
    static final int REDIS_TLS_PORT = 6380;
    static final String TLS_CERT_FILE = "/tls/server.crt";
    static final String TLS_KEY_FILE = "/tls/server.key";
    static final String TLS_CA_FILE = "/tls/ca.crt";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final SidecarCertificates certificates;
    private final EmulatorConfig config;

    // Host ports each running container was given by the allocator (plain, then TLS when enabled), keyed by
    // container id, so they go back to the pool when the container is removed. Without it the
    // range drains over the process lifetime.
    private final Map<String, List<Integer>> hostPorts = new ConcurrentHashMap<>();

    @Inject
    public RedisCacheManager(ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             ContainerDetector containerDetector,
                             PortAllocator portAllocator,
                             SidecarCertificates certificates,
                             EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
        this.certificates = certificates;
        this.config = config;
    }

    /**
     * Starts a Redis container for the given cache. Sets containerId, hostName, port and the
     * internal endpoint on the cache, and sslPort when TLS is enabled. Status remains "Creating"
     * until {@link #isReady} returns true. Both the primary and secondary keys authenticate as the
     * {@code default} user.
     *
     * <p>The container serves plain RESP on 6379, published on a host port from the configured
     * range. With {@code floci-az.services.redis.tls-enabled} it also serves TLS on 6380, published
     * on a second host port from the same range, and sslPort reports that port, so the connection
     * strings Azure tooling builds ({@code host:sslPort,password=...,ssl=True}) connect.</p>
     */
    public void startCache(RedisCache cache) {
        EmulatorConfig.RedisConfig redisConfig = config.services().redis();
        boolean tlsEnabled = redisConfig.tlsEnabled();
        String image = redisConfig.defaultImage();
        String containerName = containerName(cache);

        LOG.infov("Starting Redis container for cache: {0} using image {1}", cache.getName(), image);

        SidecarCertificates.ServerCertificate tls = tlsEnabled ? certificates.issue(tlsHosts(containerName)) : null;

        releaseHostPorts(cache.getContainerId());
        int hostPort = portAllocator.allocate(redisConfig.basePort(), redisConfig.maxPort());
        int tlsHostPort = 0;
        if (tlsEnabled) {
            try {
                tlsHostPort = portAllocator.allocate(redisConfig.basePort(), redisConfig.maxPort());
            } catch (RuntimeException e) {
                portAllocator.release(hostPort);
                throw e;
            }
        }
        List<Integer> allocated = tlsEnabled ? List.of(hostPort, tlsHostPort) : List.of(hostPort);

        lifecycleManager.removeIfExists(containerName);

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withLabels(ContainerStorageHelper.resourceIdentityLabels("redis", cache.getName(),
                        cache.getSubscriptionId(), cache.getResourceGroup(), cache.getLocation()))
                .withCmd(command(cache, redisConfig))
                .withPortBinding(REDIS_PORT, hostPort);
        if (tlsEnabled) {
            specBuilder.withPortBinding(REDIS_TLS_PORT, tlsHostPort);
        }
        ContainerSpec spec = specBuilder
                .withDockerNetwork(config.services().dockerNetwork())
                .withLogRotation()
                .build();

        ContainerLifecycleManager.ContainerInfo info;
        try {
            info = tlsEnabled ? createWithCertificates(spec, tls) : lifecycleManager.createAndStart(spec);
        } catch (RuntimeException e) {
            allocated.forEach(portAllocator::release);
            throw e;
        }
        hostPorts.put(info.containerId(), allocated);
        cache.setContainerId(info.containerId());

        ContainerLifecycleManager.EndpointInfo ep = info.getEndpoint(REDIS_PORT);
        if (lifecycleManager.publishedEndpoints()) {
            cache.setHostName(lifecycleManager.daemonAddress());
            cache.setPort(hostPort);
            if (tlsEnabled) {
                cache.setSslPort(tlsHostPort);
            }
            cache.setInternalEndpoint(ep != null ? ep.host() + ":" + ep.port()
                    : lifecycleManager.daemonHost() + ":" + hostPort);
        } else if (containerDetector.isRunningInContainer()) {
            cache.setHostName(containerName);
            cache.setPort(REDIS_PORT);
            if (tlsEnabled) {
                cache.setSslPort(REDIS_TLS_PORT);
            }
            cache.setInternalEndpoint(
                    ContainerLifecycleManager.containerNetworkEndpoint(spec, info, REDIS_PORT).toString());
        } else {
            cache.setHostName("localhost");
            cache.setPort(hostPort);
            if (tlsEnabled) {
                cache.setSslPort(tlsHostPort);
            }
            cache.setInternalEndpoint(ep != null ? ep.host() + ":" + ep.port() : "localhost:" + hostPort);
        }

        if (tlsEnabled) {
            LOG.infov("Redis container {0} started for cache {1} on {2}:{3} (TLS {4}, internal: {5})",
                    info.containerId(), cache.getName(), cache.getHostName(), cache.getPort(),
                    cache.getSslPort(), cache.getInternalEndpoint());
        } else {
            LOG.infov("Redis container {0} started for cache {1} on {2}:{3} (internal: {4})",
                    info.containerId(), cache.getName(), cache.getHostName(), cache.getPort(),
                    cache.getInternalEndpoint());
        }
    }

    /**
     * Creates the container, copies the certificate files in and only then starts it, because
     * valkey-server reads them once, as it boots. A failure after the create removes the container.
     */
    private ContainerLifecycleManager.ContainerInfo createWithCertificates(
            ContainerSpec spec, SidecarCertificates.ServerCertificate tls) {
        String containerId = null;
        try {
            containerId = lifecycleManager.create(spec);
            lifecycleManager.copyFileToContainer(containerId, tls.chainPem(), TLS_CERT_FILE);
            lifecycleManager.copyFileToContainer(containerId, tls.privateKeyPem(), TLS_KEY_FILE);
            lifecycleManager.copyFileToContainer(containerId, tls.caPem(), TLS_CA_FILE);
            return lifecycleManager.startCreated(containerId, spec);
        } catch (RuntimeException e) {
            if (containerId != null) {
                removeQuietly(containerId);
            }
            throw e;
        }
    }

    /**
     * The valkey command line: the primary key as the password and, when TLS is enabled, TLS on
     * {@link #REDIS_TLS_PORT} with the certificate files copied in before the start. Clients are
     * not asked for a certificate, as on Azure, where the access key is the credential.
     */
    static List<String> command(RedisCache cache, EmulatorConfig.RedisConfig redisConfig) {
        List<String> cmd = new ArrayList<>(List.of(
                "valkey-server",
                "--requirepass", cache.getPrimaryKey(),
                "--maxmemory", redisConfig.maxMemory(),
                "--maxmemory-policy", "allkeys-lru"));
        if (redisConfig.tlsEnabled()) {
            cmd.addAll(List.of(
                    "--tls-port", String.valueOf(REDIS_TLS_PORT),
                    "--tls-cert-file", TLS_CERT_FILE,
                    "--tls-key-file", TLS_KEY_FILE,
                    "--tls-ca-cert-file", TLS_CA_FILE,
                    "--tls-auth-clients", "no"));
        }
        return cmd;
    }

    /**
     * Host names a TLS client may address the cache by besides the loopback names: its container
     * name and, in published endpoint mode, the Docker daemon's address.
     */
    private List<String> tlsHosts(String containerName) {
        return lifecycleManager.publishedEndpoints()
                ? List.of(containerName, lifecycleManager.daemonAddress())
                : List.of(containerName);
    }

    private void removeQuietly(String containerId) {
        try {
            lifecycleManager.removeIfExists(containerId);
        } catch (RuntimeException e) {
            LOG.warnv("Could not remove Redis container {0} after a failed start: {1}",
                    containerId, e.getMessage());
        }
    }

    /**
     * Allows both the primary and secondary keys to authenticate as the {@code default} user.
     * Redis {@code --requirepass} only sets a single password, so the secondary key is added via
     * an ACL update once the container is reachable. Also used to re-apply keys after rotation.
     *
     * @param authKey a password the server currently accepts. On first apply this is the primary
     *                key (set via {@code --requirepass}); when re-applying after a key rotation it
     *                must be a still-valid key, since the new keys are not yet active on the server.
     */
    public void applyAccessKeys(RedisCache cache, String authKey) {
        if (cache.getContainerId() == null) {
            return;
        }
        try {
            // resetpass clears the existing password set so rotated keys replace (not augment) it.
            execInContainer(cache.getContainerId(), new String[]{
                    "redis-cli", "-a", authKey, "--no-auth-warning",
                    "ACL", "SETUSER", "default", "on", "resetpass",
                    ">" + cache.getPrimaryKey(), ">" + cache.getSecondaryKey(), "~*", "+@all"});
        } catch (Exception e) {
            LOG.warnv("Could not apply access keys for Redis cache {0}: {1}",
                    cache.getName(), e.getMessage());
        }
    }

    /** Sends a RESP PING (authenticated) and checks for a PONG to detect readiness. */
    public boolean isReady(RedisCache cache) {
        String endpoint = cache.getInternalEndpoint();
        if (endpoint == null || cache.getContainerId() == null) {
            return false;
        }
        int sep = endpoint.lastIndexOf(':');
        if (sep < 0) {
            return false;
        }
        String host = endpoint.substring(0, sep);
        int port;
        try {
            port = Integer.parseInt(endpoint.substring(sep + 1));
        } catch (NumberFormatException e) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            socket.setSoTimeout(2000);
            OutputStream out = socket.getOutputStream();
            out.write(("AUTH " + cache.getPrimaryKey() + "\r\nPING\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[64];
            int read = in.read(buf);
            if (read <= 0) {
                return false;
            }
            return new String(buf, 0, read, StandardCharsets.UTF_8).contains("PONG");
        } catch (IOException e) {
            return false;
        }
    }

    /** Stops and removes the Redis container for the given cache. */
    public void stopCache(RedisCache cache) {
        if (cache.getContainerId() == null) {
            return;
        }
        lifecycleManager.stopAndRemove(cache.getContainerId(), null);
        releaseHostPorts(cache.getContainerId());
        LOG.infov("Stopped Redis container for cache {0}", cache.getName());
    }

    private void releaseHostPorts(String containerId) {
        if (containerId == null) {
            return;
        }
        List<Integer> ports = hostPorts.remove(containerId);
        if (ports != null) {
            ports.forEach(portAllocator::release);
        }
    }

    private String containerName(RedisCache cache) {
        return ContainerStorageHelper.dockerName(config, "redis-" + cache.getInstanceId());
    }

    private String execInContainer(String containerId, String[] cmd) throws Exception {
        DockerClient dockerClient = lifecycleManager.getDockerClient();
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
}
