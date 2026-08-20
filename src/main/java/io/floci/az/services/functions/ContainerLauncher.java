package io.floci.az.services.functions;

import com.github.dockerjava.api.DockerClient;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.az.core.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.ContainerStorageHelper;
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
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Creates and destroys Docker containers for Azure Functions execution.
 *
 * Launch sequence:
 *   1. Build the container spec ({@link ContainerBuilder}; image pull handled by the core layer)
 *   2. Create container, binding port 80 → random host port
 *   3. Inject host.json and code via TAR stream to /home/site/wwwroot (works even inside Docker)
 *   4. Start container
 *   5. Poll /admin/host/status until the Functions host reports ready
 *   6. Return ContainerHandle with resolved host:port
 */
@ApplicationScoped
public class ContainerLauncher {

    private static final Logger LOG = Logger.getLogger(ContainerLauncher.class);

    private static final String WWWROOT = "/home/site/wwwroot";
    private static final int FUNCTIONS_PORT = 80;

    private final DockerClient dockerClient;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final DockerHostResolver hostResolver;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final HttpClient httpClient;
    private volatile String cachedNetworkMode;

    @Inject
    public ContainerLauncher(DockerClient dockerClient,
                             ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             DockerHostResolver hostResolver,
                             ContainerDetector containerDetector,
                             EmulatorConfig config) {
        this.dockerClient    = dockerClient;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.hostResolver    = hostResolver;
        this.containerDetector = containerDetector;
        this.config          = config;
        this.httpClient      = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * Launches a single container for an entire function app.
     * All deployed functions in {@code appDefs} have their code injected into
     * the container under {@code /home/site/wwwroot/{funcName}/} so the
     * Azure Functions host discovers and loads them all at startup.
     */
    public ContainerHandle launch(List<FunctionDefinition> appDefs) {
        FunctionDefinition primary = appDefs.get(0);
        LOG.infov("Launching app container for: {0} ({1} function(s))",
                primary.appName(), appDefs.size());

        String image = FunctionRuntime.resolveImage(primary.runtime(), primary.linuxFxVersion());
        List<String> env = buildEnv(primary);

        String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String containerName = ContainerStorageHelper.dockerName(config,
                "fn-" + primary.appName() + "-" + shortId);

        ContainerBuilder.Builder builder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withDynamicPort(FUNCTIONS_PORT)
                .withEnv(env)
                .withHostDockerInternalOnLinux()
                .withEmbeddedDns()
                .withLogRotation();
        String networkMode = resolveNetworkMode();
        if (!"bridge".equals(networkMode)) {
            // "bridge" is Docker's default; setting it explicitly would make startCreated try to
            // re-connect the container to a network it is already on.
            builder.withNetworkMode(networkMode);
        }
        ContainerSpec spec = builder.build();

        String containerId = lifecycleManager.create(spec);
        LOG.infov("Created container {0} ({1})", containerName, containerId.substring(0, 12));

        boolean hasRootLayout = appDefs.stream().anyMatch(FunctionDefinition::packageRootLayout);
        if (!hasRootLayout) {
            // The v1 layout has no app-level host.json in the stored package.
            injectHostJson(containerId);
        }

        // v1 functions are isolated by name; Python v2 packages are app roots.
        for (FunctionDefinition fn : appDefs) {
            if (fn.codeLocalPath() != null && Files.exists(Path.of(fn.codeLocalPath()))) {
                Path codePath = Path.of(fn.codeLocalPath());
                if (fn.packageRootLayout()) {
                    copyCodeRootToContainer(containerId, codePath);
                } else {
                    copyCodeToContainer(containerId, codePath, fn.funcName());
                }
            }
        }

        ContainerInfo info = lifecycleManager.startCreated(containerId, spec);
        EndpointInfo endpoint = info.getEndpoint(FUNCTIONS_PORT);

        LOG.infov("Container {0} listening on {1}", containerId.substring(0, 12), endpoint);

        waitForReady(endpoint.host(), endpoint.port(), 60);

        return new ContainerHandle(containerId, primary.appKey(), endpoint.host(), endpoint.port());
    }

    public void stop(ContainerHandle handle) {
        LOG.infov("Stopping container {0}", handle.containerId().substring(0, 12));
        handle.setState(ContainerHandle.State.STOPPED);
        lifecycleManager.stopAndRemove(handle.containerId(), null);
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

        if (def.packageRootLayout()) {
            env.add("AzureWebJobsScriptRoot=" + WWWROOT);
            env.add("PYTHONPATH=" + WWWROOT + "/.python_packages/lib/site-packages:" + WWWROOT);
        }

        if (def.environment() != null) {
            def.environment().forEach((k, v) -> env.add(k + "=" + v));
        }
        return env;
    }

    private void injectHostJson(String containerId) {
        byte[] content = "{\"version\":\"2.0\"}".getBytes(StandardCharsets.UTF_8);
        try (PipedOutputStream pos = new PipedOutputStream();
             PipedInputStream pis = new PipedInputStream(pos, 65536)) {
            Thread tarThread = new Thread(() -> {
                try (pos; TarArchiveOutputStream tar = newTar(pos)) {
                    TarArchiveEntry entry = new TarArchiveEntry("home/site/wwwroot/host.json");
                    entry.setSize(content.length);
                    entry.setMode(0644);
                    tar.putArchiveEntry(entry);
                    tar.write(content);
                    tar.closeArchiveEntry();
                } catch (IOException e) {
                    LOG.errorv("Failed to stream host.json TAR: {0}", e.getMessage());
                }
            }, "tar-host-json");
            tarThread.setDaemon(true);
            tarThread.start();
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withRemotePath("/")
                    .withTarInputStream(pis)
                    .exec();
            LOG.debugv("Injected host.json into {0}", WWWROOT);
        } catch (Exception e) {
            LOG.warnv("Failed to inject host.json: {0}", e.getMessage());
        }
    }

    private void copyCodeToContainer(String containerId, Path codeDir, String funcName) {
        try (PipedOutputStream pos = new PipedOutputStream();
             PipedInputStream  pis = new PipedInputStream(pos, 256 * 1024)) {

            // Each function lives at /home/site/wwwroot/{funcName}/ so the Azure
            // Functions host discovers all functions in the app from one container.
            String prefix = "home/site/wwwroot/" + funcName + "/";
            Thread tarThread = new Thread(() -> {
                try (pos) {
                    createTarWithPrefix(codeDir, pos, prefix);
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

    private void copyCodeRootToContainer(String containerId, Path codeDir) {
        try (PipedOutputStream pos = new PipedOutputStream();
             PipedInputStream pis = new PipedInputStream(pos, 256 * 1024)) {
            Thread tarThread = new Thread(() -> {
                try (pos) {
                    createTarWithPrefix(codeDir, pos, "home/site/wwwroot/");
                } catch (IOException e) {
                    LOG.errorv("Failed to stream root-layout TAR: {0}", e.getMessage());
                }
            }, "tar-function-app");
            tarThread.setDaemon(true);
            tarThread.start();

            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withRemotePath("/")
                    .withTarInputStream(pis)
                    .exec();
            LOG.debugv("Injected root-layout code into {0}", WWWROOT);
        } catch (Exception e) {
            LOG.warnv("Failed to copy root-layout code: {0}", e.getMessage());
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

    private void waitForReady(String targetHost, int targetPort, int timeoutSeconds) {
        String url = "http://" + targetHost + ":" + targetPort + "/admin/host/status";
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
