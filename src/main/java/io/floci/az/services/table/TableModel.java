package io.floci.az.services.table;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.floci.az.core.StoredObject;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Map;

public class TableModel {

    @RegisterForReflection
    public record TableListResponse(
        @JsonProperty("value") List<TableItem> value
    ) {}

    @RegisterForReflection
    public record TableItem(
        @JsonProperty("TableName") String TableName
    ) {}

    @RegisterForReflection
    public record EntityListResponse(
        @JsonProperty("value") List<Map<String, Object>> value
    ) {}

    @RegisterForReflection
    public record TableCreateRequest(
        @JsonProperty("TableName") String TableName
    ) {}

    // Carries both the deserialized entity map and its StoredObject (for etag)
    // Used internally in the query pipeline — NOT serialized to JSON
    public record EntityWithMeta(java.util.Map<String, Object> entity,
                                  StoredObject stored) {}

    // Result of a single batch operation — used to build the multipart response
    @RegisterForReflection
    public record BatchOpResult(int status, String statusText,
                                 java.util.Map<String, Object> body,
                                 String etag) {}
}
