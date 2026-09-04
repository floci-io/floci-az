package io.floci.az.services.cosmos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Logical partition scope applied before SQL evaluation, including aggregates and paging. */
final class CosmosQueryPartition {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CosmosQueryPartition() {}

    static Predicate<Map<String, Object>> parse(String header, Map<String, Object> container) {
        if (header == null) {
            return document -> true;
        }
        final JsonNode values;
        try {
            values = MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(header);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid x-ms-documentdb-partitionkey JSON", e);
        }
        JsonNode paths = MAPPER.valueToTree(container).path("partitionKey").path("paths");
        if (values == null || !values.isArray() || values.isEmpty() || values.size() != paths.size()) {
            throw new IllegalArgumentException("Partition key must contain one value for each configured path");
        }
        for (JsonNode value : values) {
            if (!(value.isValueNode() || (value.isObject() && value.isEmpty()))) {
                throw new IllegalArgumentException("Invalid partition key value");
            }
        }
        List<String> pointers = MAPPER.convertValue(paths,
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        return document -> {
            JsonNode item = MAPPER.valueToTree(document);
            for (int i = 0; i < pointers.size(); i++) {
                JsonNode actual = item.at(pointers.get(i));
                JsonNode expected = values.get(i);
                boolean matches;
                if (expected.isObject()) {
                    matches = actual.isMissingNode();
                } else if (actual.isNumber() && expected.isNumber()) {
                    matches = actual.decimalValue().compareTo(expected.decimalValue()) == 0;
                } else {
                    matches = actual.equals(expected);
                }
                if (!matches) {
                    return false;
                }
            }
            return true;
        };
    }
}
