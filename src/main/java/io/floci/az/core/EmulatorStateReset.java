package io.floci.az.core;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Wipes all emulator state across every service, for {@code POST /_admin/reset} and
 * {@code POST /_floci/state/reset}. Every state-holding component self-registers by implementing
 * {@link Resettable}; each {@code clear()} is self-contained (services with sidecar containers stop
 * them themselves). The reset is best-effort: a failing handler is logged and skipped so the
 * remaining services still get cleared, because CDI iteration order is non-deterministic and one
 * failure must not decide which services reset.
 */
@ApplicationScoped
public class EmulatorStateReset {

    private static final Logger LOG = Logger.getLogger(EmulatorStateReset.class);

    private final Instance<Resettable> resettables;

    @Inject
    public EmulatorStateReset(Instance<Resettable> resettables) {
        this.resettables = resettables;
    }

    public void resetAll() {
        for (Resettable resettable : resettables) {
            try {
                resettable.clear();
            } catch (Exception e) {
                LOG.errorf(e, "Reset failed for %s, continuing with remaining services",
                        resettable.getClass().getSimpleName());
            }
        }
    }
}
