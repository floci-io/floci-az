package io.floci.az.services.table;

import com.fasterxml.jackson.annotation.JsonProperty;
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
}
