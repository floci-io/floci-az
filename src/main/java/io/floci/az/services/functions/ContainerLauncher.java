package io.floci.az.services.functions;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.Ports;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.DockerHostResolver;
import io.floci.az.services.functions.FunctionModels.FunctionDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Creates and destroys Docker containers for Azure Functions execution.
 *
 * Launch sequence:
 *   1. Ensure runtime image is present locally (pull if needed)
 *   2. Create container, binding port 80 → random host port
 *   3. Start container
 *   4. Inject code via TAR stream to /home/site/wwwroot (works even inside Docker)
 *   5. Poll /admin/host/status until the Functions host reports ready
 *   6. Return ContainerHandle with resolved host:port
 */
@ApplicationScoped
public class ContainerLauncher {

    private static final Logger LOG = Logger.getLogger(ContainerLauncher.class);

    private static final String WWWROOT = "/home/site/wwwroot";
    private static final int FUNCTIONS_PORT = 80;

    private static final Map<String, String> RUNTIME_IMAGES = Map.of(
            "node",   "mcr.microsoft.com/azure-functions/node:4",
            "python", "mcr.microsoft.com/azure-functions/python:4",
            "java",   "mcr.microsoft.com/azure-functions/java:4",
            "dotnet", "mcr.microsoft.com/azure-functions/dotnet-isolated:4"
    );

    private final DockerClient dockerClient;
    private final DockerHostResolver hostResolver;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final HttpClient httpClient;
    private volatile String cachedNetworkMode;

    @Inject
    public ContainerLauncher(DockerClient dockerClient,
                             DockerHostResolver hostResolver,
                             ContainerDetector containerDetector,
                             EmulatorConfig config) {
        this.dockerClient = dockerClient;
        this.hostResolver = hostResolver;
        this.containerDetector = containerDetector;
        this.config       = config;
        this.httpClient   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    public ContainerHandle launch(FunctionDefinition def) {
        LOG.infov("Launching container for function: {0}/{1}", def.appName(), def.funcName());

        if (def.codeLocalPath() != null && !Files.exists(Path.of(def.codeLocalPath()))) {
            throw new RuntimeException("Code directory not found for " + def.funcName()
                    + ": " + def.codeLocalPath());
        }

        String image = resolveImage(def.runtime());
        ensureImage(image);

        List<String> env = buildEnv(def);

        String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String containerName = "floci-az-fn-" + def.appName() + "-" + def.funcName() + "-" + shortId;

        // Create container with port 80 bound to a dynamic host port
        ExposedPort exposed  = ExposedPort.tcp(FUNCTIONS_PORT);
        Ports portBindings   = new Ports();
        portBindings.bind(exposed, Ports.Binding.bindPort(0));

        List<String> extraHosts = new ArrayList<>();
        if (hostResolver.isLinuxHost() && !hostResolver.resolve().equals("host.docker.internal")) {
            extraHosts.add("host.docker.internal:host-gateway");
        }

        LogConfig logConfig = new LogConfig(LogConfig.LoggingType.JSON_FILE, Map.of(
                "max-size", config.docker().logMaxSize(),
                "max-file", config.docker().logMaxFile()
        ));

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withPortBindings(portBindings)
                .withExtraHosts(extraHosts.toArray(new String[0]))
                .withLogConfig(logConfig);

        String networkMode = resolveNetworkMode();

        CreateContainerResponse created = dockerClient.createContainerCmd(image)
                .withName(containerName)
                .withExposedPorts(exposed)
                .withHostConfig(hostConfig)
                .withNetworkMode(networkMode)
                .withEnv(env)
                .exec();

        String containerId = created.getId();
        LOG.infov("Created container {0} ({1})", containerName, containerId.substring(0, 12));

        // Inject function code before starting so the Functions host finds it on startup
        if (def.codeLocalPath() != null) {
            copyCodeToContainer(containerId, Path.of(def.codeLocalPath()), def.funcName());
        }

        dockerClient.startContainerCmd(containerId).exec();

        // Discover the dynamically assigned host port
        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        int hostPort = resolveHostPort(inspect, exposed);

        String targetHost = "localhost";
        if (containerDetector.isRunningInContainer()) {
            // Use the container's IP on the shared network
            var networks = inspect.getNetworkSettings().getNetworks();
            if (!networks.isEmpty()) {
                targetHost = networks.values().iterator().next().getIpAddress();
                LOG.infov("Container {0} detected at IP {1} on network {2}",
                        containerId.substring(0, 12), targetHost, networks.keySet().iterator().next());
            }
        }

        LOG.infov("Container {0} listening on {1}:{2}", containerId.substring(0, 12), targetHost, hostPort);

        // Wait for Azure Functions host to become ready
        int targetPort = targetHost.equals("localhost") ? hostPort : 80;
        waitForReady(targetHost, targetPort, 60);

        return new ContainerHandle(containerId, def.functionKey(), targetHost, targetPort);
    }

    public void stop(ContainerHandle handle) {
        LOG.infov("Stopping container {0}", handle.containerId().substring(0, 12));
        handle.setState(ContainerHandle.State.STOPPED);
        try {
            dockerClient.stopContainerCmd(handle.containerId()).withTimeout(5).exec();
        } catch (NotFoundException e) {
            // Already gone
        } catch (Exception e) {
            LOG.warnv("Error stopping container {0}: {1}", handle.containerId(), e.getMessage());
        }
        try {
            dockerClient.removeContainerCmd(handle.containerId()).withForce(true).exec();
        } catch (Exception ignored) {}
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String resolveNetworkMode() {
        if (!containerDetector.isRunningInContainer()) return "bridge";
        if (cachedNetworkMode != null) return cachedNetworkMode;
        try {
            String selfContainerId = System.getenv("HOSTNAME");
            if (selfContainerId != null) {
                var networks = dockerClient.inspectContainerCmd(selfContainerId).exec()
                        .getNetworkSettings().getNetworks();
                if (!networks.isEmpty()) {
                    cachedNetworkMode = networks.keySet().iterator().next();
                    LOG.infov("Detected floci-az network: {0}", cachedNetworkMode);
                    return cachedNetworkMode;
                }
            }
        } catch (Exception e) {
            LOG.warnv("Failed to detect self network, falling back to bridge: {0}", e.getMessage());
        }
        return "bridge";
    }

    private String resolveImage(String runtime) {
        String image = RUNTIME_IMAGES.get(runtime != null ? runtime.toLowerCase() : "node");
        if (image == null) {
            throw new RuntimeException("Unsupported runtime: " + runtime
                    + ". Supported: " + RUNTIME_IMAGES.keySet());
        }
        return image;
    }

    private void ensureImage(String image) {
        try {
            dockerClient.inspectImageCmd(image).exec();
            LOG.debugv("Image already present: {0}", image);
        } catch (NotFoundException e) {
            LOG.infov("Pulling image: {0}", image);
            try {
                boolean pulled = dockerClient.pullImageCmd(image)
                        .exec(new PullImageResultCallback())
                        .awaitCompletion(10, TimeUnit.MINUTES);
                if (!pulled) throw new RuntimeException("Timed out pulling image: " + image);
                LOG.infov("Pulled image: {0}", image);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while pulling image: " + image, ie);
            }
        }
    }

    private List<String> buildEnv(FunctionDefinition def) {
        String flociHost = hostResolver.resolve();
        int flociPort    = config.port();
        String connStr   = "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==;"
                + "BlobEndpoint=http://" + flociHost + ":" + flociPort + "/devstoreaccount1;";

        List<String> env = new ArrayList<>();
        env.add("FUNCTIONS_WORKER_RUNTIME=" + def.runtime());
        env.add("FUNCTIONS_EXTENSION_VERSION=~4");
        env.add("AzureWebJobsStorage=" + connStr);
        env.add("WEBSITE_HOSTNAME=localhost");
        env.add("AzureWebJobsSecretStorageType=files");
        env.add("AZURE_FUNCTIONS_ENVIRONMENT=Development");

        if (def.environment() != null) {
            def.environment().forEach((k, v) -> env.add(k + "=" + v));
        }
        return env;
    }

    private static int resolveHostPort(InspectContainerResponse inspect, ExposedPort exposed) {
        var bindings = inspect.getNetworkSettings().getPorts().getBindings();
        var binding  = bindings.get(exposed);
        if (binding == null || binding.length == 0) {
            throw new RuntimeException("No host port binding found for container port " + exposed.getPort());
        }
        return Integer.parseInt(binding[0].getHostPortSpec());
    }

    private void copyCodeToContainer(String containerId, Path codeDir, String funcName) {
        try (PipedOutputStream pos = new PipedOutputStream();
             PipedInputStream  pis = new PipedInputStream(pos, 256 * 1024)) {

            Thread tarThread = new Thread(() -> {
                try (pos) {
                    createTarWithPrefix(codeDir, pos, "home/site/wwwroot/");
                } catch (IOException e) {
                    LOG.errorv("Failed to stream TAR for {0}: {1}", funcName, e.getMessage());
                }
            }, "tar-" + funcName);
            tarThread.setDaemon(true);
            tarThread.start();

            // Copy to "/" so Docker extracts the full path home/site/wwwroot/...
            // This avoids the 404 error when /home/site/wwwroot doesn't yet exist
            // in a stopped (created-but-not-started) container.
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withRemotePath("/")
                    .withTarInputStream(pis)
                    .exec();

            LOG.debugv("Injected code for {0} into {1}", funcName, WWWROOT);
        } catch (Exception e) {
            LOG.warnv("Failed to copy code for {0}: {1}", funcName, e.getMessage());
        }
    }

    private static void createTarWithPrefix(Path sourceDir, OutputStream out, String prefix) throws IOException {
        try (TarArchiveOutputStream tar = newTar(out);
             var stream = Files.walk(sourceDir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(path)) continue;
                String entryName = prefix + sourceDir.relativize(path).toString();
                TarArchiveEntry entry = new TarArchiveEntry(entryName);
                entry.setSize(Files.size(path));
                entry.setMode(0755);
                tar.putArchiveEntry(entry);
                try (var fis = Files.newInputStream(path)) {
                    fis.transferTo(tar);
                }
                tar.closeArchiveEntry();
            }
        }
    }

    private static TarArchiveOutputStream newTar(OutputStream out) {
        TarArchiveOutputStream tar = new TarArchiveOutputStream(out);
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR);
        return tar;
    }

    private void waitForReady(String targetHost, int hostPort, int timeoutSeconds) {
        String url = "http://" + targetHost + ":" + (targetHost.equals("localhost") ? hostPort : 80) + "/admin/host/status";
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        LOG.infov("Waiting for Azure Functions host on {0}...", url);

        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<Void> resp = httpClient.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .GET()
                                .timeout(Duration.ofSeconds(2))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() < 500) {
                    LOG.infov("Azure Functions host ready on {0} (status {1})", url, resp.statusCode());
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for container", e);
            } catch (Exception ignored) {}

            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for container", e);
            }
        }
        throw new RuntimeException(
                "Azure Functions container did not become ready within " + timeoutSeconds + "s on " + url);
    }
}
