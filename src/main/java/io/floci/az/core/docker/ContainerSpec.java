package io.floci.az.core.docker;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.Mount;

import java.util.List;
import java.util.Map;

/**
 * Immutable specification for a Docker container. Use {@link ContainerBuilder} to construct.
 */
public record ContainerSpec(
        String image,
        String name,
        List<String> env,
        List<String> cmd,
        List<String> entrypoint,
        Long memoryBytes,
        Map<Integer, Integer> portBindings,
        List<Integer> exposedPorts,
        String networkMode,
        List<Mount> mounts,
        List<Bind> binds,
        List<String> extraHosts,
        LogConfig logConfig,
        boolean privileged,
        List<String> dnsServers,
        String workingDir
) {
    public ContainerSpec(String image) {
        this(image, null, List.of(), null, null, null, Map.of(), List.of(), null,
                List.of(), List.of(), List.of(), null, false, List.of(), null);
    }

    public boolean hasPortBindings() {
        return portBindings != null && !portBindings.isEmpty();
    }

    public boolean hasMemoryLimit() {
        return memoryBytes != null && memoryBytes > 0;
    }

    public boolean hasLogConfig() {
        return logConfig != null;
    }
}
