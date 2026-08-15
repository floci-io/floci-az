package io.floci.az.core;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

// Raises Jackson's string-length limit so large payloads (blob uploads, function ZIPs) are accepted.
// Azure Storage allows PutBlob up to 5000 MiB; Functions ZIPs can reach several hundred MB.
@Singleton
public class JacksonConfig implements ObjectMapperCustomizer {

    private static final int MAX_STRING_LENGTH = 512 * 1024 * 1024; // 512 MB

    @Override
    public void customize(ObjectMapper mapper) {
        applyLargePayloadConstraints(mapper);
    }

    /**
     * Apply the large-payload stream-read constraints to a mapper.
     *
     * <p>The customizer above only reaches the CDI-managed {@link ObjectMapper}. Handlers that
     * construct their own mapper keep Jackson's 20 MB default string limit unless they call this,
     * which would reject payloads real Azure accepts.
     */
    public static void applyLargePayloadConstraints(ObjectMapper mapper) {
        mapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder()
                        .maxStringLength(MAX_STRING_LENGTH)
                        .build());
    }
}
