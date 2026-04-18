package io.floci.az.core;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.Map;

@RegisterForReflection
public record StoredObject(
    String key,
    byte[] data,
    Map<String, String> metadata,
    Instant lastModified,
    String etag
) {}
