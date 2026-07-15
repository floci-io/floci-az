package io.floci.az.services.blob;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record DataLakePathListResponse(
        List<PathEntry> paths
) {
    @RegisterForReflection
    public record PathEntry(
            String name,
            @JsonProperty("isDirectory") boolean directory,
            String lastModified,
            long contentLength,
            String owner,
            String group,
            String permissions,
            String etag
    ) {
    }
}
