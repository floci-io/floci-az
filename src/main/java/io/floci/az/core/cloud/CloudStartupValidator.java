package io.floci.az.core.cloud;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.StringJoiner;

/** Loads the cloud catalog and the configured per-cloud defaults at startup, and logs what they hold. */
@ApplicationScoped
public class CloudStartupValidator {

    private static final Logger LOG = Logger.getLogger(CloudStartupValidator.class);

    private final CloudDefaults defaults;

    @Inject
    public CloudStartupValidator(CloudDefaults defaults) {
        this.defaults = defaults;
    }

    void onStart(@Observes StartupEvent event) {
        // Fail fast, unlike sidecars: the catalog is bundled, so a malformed one is a build defect, not an environment problem.
        List<CloudProfile> clouds = CloudCatalog.all();
        StringJoiner summary = new StringJoiner(", ");
        for (CloudProfile cloud : clouds) {
            // Resolving the default validates a configured one, so a bad value fails here rather than on first use.
            summary.add(cloud.name() + " (" + cloud.locations().size() + " locations, default "
                + defaults.defaultLocation(cloud) + ")");
        }
        LOG.infov("Cloud catalog: {0}", summary);
    }
}
