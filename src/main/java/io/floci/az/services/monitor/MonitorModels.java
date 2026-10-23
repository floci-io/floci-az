package io.floci.az.services.monitor;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/** The ARM resources {@link MonitorHandler} keeps. */
public final class MonitorModels {

    private MonitorModels() {
    }

    /**
     * A workspace, a data collection endpoint or rule, or a diagnostic setting, as its wire body. This is also the
     * shape the handler stored before the resource store, so entries written then decode unchanged. A diagnostic
     * setting has no location, which the body leaves out.
     */
    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MonitorResource(String id, String name, String type, String location,
                                  Map<String, Object> properties) {

        /** A string property, or null when it is missing or not a string. */
        public String property(String key) {
            return properties != null && properties.get(key) instanceof String text ? text : null;
        }
    }
}
