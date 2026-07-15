package io.floci.az.services.blob;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record DataLakePathListResponse(
        List<PathEntry> paths
) {
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

