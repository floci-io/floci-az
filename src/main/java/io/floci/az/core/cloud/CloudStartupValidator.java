package io.floci.az.core.cloud;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.StringJoiner;

/** Loads the cloud catalog at startup and logs what it holds. */
@ApplicationScoped
public class CloudStartupValidator {

    private static final Logger LOG = Logger.getLogger(CloudStartupValidator.class);

    void onStart(@Observes StartupEvent event) {
        // Fail fast, unlike sidecars: the catalog is bundled, so a malformed one is a build defect, not an environment problem.
        List<CloudProfile> clouds = CloudCatalog.all();
        StringJoiner summary = new StringJoiner(", ");
        for (CloudProfile cloud : clouds) {
            summary.add(cloud.name() + " (" + cloud.locations().size() + " locations)");
        }
        LOG.infov("Cloud catalog: {0}", summary);
    }
}
