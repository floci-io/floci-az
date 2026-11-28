package io.floci.az.services.monitor;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

/** The ARM resources {@link MonitorHandler} keeps. */
public final class MonitorModels {

    private MonitorModels() {
    }

    /**
     * A workspace, a data collection endpoint or rule, or a diagnostic setting, as its wire body. This is also the
     * shape the handler stored before the resource store, so entries written then decode unchanged. A diagnostic
     * setting has no location and no tags, which the body leaves out, as it does the tags of an entry stored before
     * they were kept.
     */
    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MonitorResource(String id, String name, String type, String location,
                                  Map<String, Object> tags, Map<String, Object> properties) {

        /** A string property, or null when it is missing or not a string. */
        public String property(String key) {
            return properties != null && properties.get(key) instanceof String text ? text : null;
        }
    }

    /**
     * A workspace deleted without {@code force}: recoverable by a PUT on its id until the retention ends.
     *
     * @param id        the record's key, the workspace id with {@code deletedWorkspaces} for {@code workspaces}, so
     *                  it shares the {@code monitor} backend without being listed as a workspace
     * @param deletedAt when the workspace was deleted
     * @param workspace the workspace as it was, which {@code DeletedWorkspaces_List} answers with
     */
    @RegisterForReflection
    public record DeletedWorkspace(String id, Instant deletedAt, MonitorResource workspace) {
    }

    /**
     * An Application Insights component's billing features ({@code .../components/{name}/currentbillingfeatures}).
     *
     * @param id       the record's key, the component id with {@code /currentbillingfeatures} appended
     * @param features the document {@code ComponentCurrentBillingFeatures_Get} answers with
     */
    @RegisterForReflection
    public record BillingFeatures(String id, Map<String, Object> features) {
    }
}
