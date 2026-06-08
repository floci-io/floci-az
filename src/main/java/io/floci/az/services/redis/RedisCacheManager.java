package io.floci.az.services.redis;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.PortAllocator;
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
import java.util.concurrent.TimeUnit;

/**
 * Manages the Docker lifecycle of Redis containers backing Azure Cache for Redis caches.
 * Not used when {@code floci-az.services.redis.mocked=true}.
 */
@ApplicationScoped
public class RedisCacheManager {

    private static final Logger LOG = Logger.getLogger(RedisCacheManager.class);
    private static final int REDIS_PORT = 6379;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;

    @Inject
    public RedisCacheManager(ContainerBuilder containerBuilder,
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
     * Starts a Redis container for the given cache. Sets containerId, hostName, port and the
     * internal endpoint on the cache. Status remains "Creating" until {@link #isReady} returns
     * true. Both the primary and secondary keys authenticate as the {@code default} user.
     */
    public void startCache(RedisCache cache) {
        EmulatorConfig.RedisConfig redisConfig = config.services().redis();
        String image = redisConfig.defaultImage();
        String containerName = containerName(cache);

        LOG.infov("Starting Redis container for cache: {0} using image {1}", cache.getName(), image);

        int hostPort = portAllocator.allocate(redisConfig.basePort(), redisConfig.maxPort());

        lifecycleManager.removeIfExists(containerName);

        List<String> cmd = new ArrayList<>(List.of(
                "valkey-server",
                "--requirepass", cache.getPrimaryKey(),
                "--maxmemory", redisConfig.maxMemory(),
                "--maxmemory-policy", "allkeys-lru"));

        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(containerName)
                .withCmd(cmd)
                .withPortBinding(REDIS_PORT, hostPort)
                .withDockerNetwork(config.services().dockerNetwork())
                .withLogRotation()
                .build();

        ContainerLifecycleManager.ContainerInfo info = lifecycleManager.createAndStart(spec);
        cache.setContainerId(info.containerId());

        ContainerLifecycleManager.EndpointInfo ep = info.getEndpoint(REDIS_PORT);
        if (containerDetector.isRunningInContainer()) {
            cache.setHostName(containerName);
            cache.setPort(REDIS_PORT);
            cache.setInternalEndpoint(ep != null
                    ? ep.host() + ":" + ep.port()
                    : containerName + ":" + REDIS_PORT);
        } else {
            cache.setHostName("localhost");
            cache.setPort(hostPort);
            cache.setInternalEndpoint(ep != null ? ep.host() + ":" + ep.port() : "localhost:" + hostPort);
        }

        LOG.infov("Redis container {0} started for cache {1} on {2}:{3} (internal: {4})",
                info.containerId(), cache.getName(), cache.getHostName(), cache.getPort(),
                cache.getInternalEndpoint());
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
        LOG.infov("Stopped Redis container for cache {0}", cache.getName());
    }

    private static String containerName(RedisCache cache) {
        return "floci-az-redis-" + cache.getInstanceId();
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
}
