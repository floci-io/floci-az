package io.floci.az.services.vm;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.services.vm.VmModels.VirtualMachine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Manages the Docker lifecycle of containers backing virtual machines in non-mocked mode
 * ({@code floci-az.services.vm.mocked=false}).
 *
 * <p>Each VM is backed by one long-lived Linux container kept alive with
 * {@code tail -f /dev/null} (mirroring the AWS sibling's EC2 backing). Azure power actions map
 * onto Docker operations:</p>
 * <ul>
 *   <li>{@code start} → docker start</li>
 *   <li>{@code powerOff} / {@code deallocate} → docker stop (container retained)</li>
 *   <li>{@code restart} / {@code redeploy} / {@code reapply} → docker restart</li>
 *   <li>delete → docker stop + remove</li>
 * </ul>
 *
 * <p>Per the floci-az sidecar rules this never calls {@code dockerClient} directly — it goes
 * through {@link ContainerBuilder} and {@link ContainerLifecycleManager} — and all Docker
 * failures are non-fatal so the service degrades gracefully when Docker is unavailable.</p>
 */
@ApplicationScoped
public class VmContainerManager {

    private static final Logger LOG = Logger.getLogger(VmContainerManager.class);

    /** Keep-alive command so a stock base image stays running as an emulated VM. */
    private static final List<String> KEEP_ALIVE = List.of("tail", "-f", "/dev/null");
    private static final int STOP_TIMEOUT_SECONDS = 10;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final VmImageResolver imageResolver;
    private final EmulatorConfig config;

    @Inject
    public VmContainerManager(ContainerBuilder containerBuilder,
                              ContainerLifecycleManager lifecycleManager,
                              VmImageResolver imageResolver,
                              EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.imageResolver = imageResolver;
        this.config = config;
    }

    /**
     * Launches a keep-alive container backing the VM and records its id on {@code vm}.
     * The VM stays in {@code provisioningState=Creating} until the readiness poller observes the
     * container running.
     */
    public void startVm(VirtualMachine vm) {
        String image = resolveImage(vm);
        String containerName = containerName(vm);

        LOG.infov("Starting container for VM {0} using image {1}", vm.getName(), image);

        lifecycleManager.removeIfExists(containerName);

        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(containerName)
                .withCmd(KEEP_ALIVE)
                .withDockerNetwork(config.services().dockerNetwork())
                .withLogRotation()
                .build();

        ContainerInfo info = lifecycleManager.createAndStart(spec);
        vm.setContainerId(info.containerId());

        LOG.infov("Container {0} started for VM {1}", info.containerId(), vm.getName());
    }

    /** True once the backing container is up — the readiness signal for a plain VM. */
    public boolean isRunning(VirtualMachine vm) {
        return vm.getContainerId() != null
                && lifecycleManager.isContainerRunning(vm.getContainerId());
    }

    /** Power-on: start the (stopped) backing container. */
    public void startContainer(VirtualMachine vm) {
        if (vm.getContainerId() != null) {
            lifecycleManager.start(vm.getContainerId());
        }
    }

    /** Power-off / deallocate: stop the backing container but keep it for a later start. */
    public void stopContainer(VirtualMachine vm) {
        if (vm.getContainerId() != null) {
            lifecycleManager.stop(vm.getContainerId(), STOP_TIMEOUT_SECONDS);
        }
    }

    /** Restart: bounce the backing container in place. */
    public void restartContainer(VirtualMachine vm) {
        if (vm.getContainerId() != null) {
            lifecycleManager.restart(vm.getContainerId(), STOP_TIMEOUT_SECONDS);
        }
    }

    /** Delete: stop and remove the backing container. */
    public void removeVm(VirtualMachine vm) {
        if (vm.getContainerId() != null) {
            lifecycleManager.stopAndRemove(vm.getContainerId(), null);
        } else {
            lifecycleManager.removeIfExists(containerName(vm));
        }
    }

    private String resolveImage(VirtualMachine vm) {
        Map<String, Object> imageReference = imageReference(vm);
        if (imageReference != null) {
            return imageResolver.resolve(imageReference);
        }
        return config.services().vm().defaultImage();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> imageReference(VirtualMachine vm) {
        if (vm.getProperties() == null) {
            return null;
        }
        Object storageProfile = vm.getProperties().get("storageProfile");
        if (storageProfile instanceof Map<?, ?> sp) {
            Object imageRef = ((Map<String, Object>) sp).get("imageReference");
            if (imageRef instanceof Map<?, ?> ir) {
                return (Map<String, Object>) ir;
            }
        }
        return null;
    }

    static String containerName(VirtualMachine vm) {
        String id = vm.getVmId() != null
                ? vm.getVmId().replace("-", "")
                : sanitize(vm.getName());
        if (id.length() > 12) {
            id = id.substring(0, 12);
        }
        return "floci-az-vm-" + id;
    }

    private static String sanitize(String name) {
        return name == null ? "unknown" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
