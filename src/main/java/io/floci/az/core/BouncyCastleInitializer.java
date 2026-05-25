package io.floci.az.core;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jboss.logging.Logger;

import java.security.Security;

/**
 * Ensures the BouncyCastle security provider is registered at application startup.
 * Required when TLS self-signed cert generation is used — BouncyCastle must be
 * registered before any crypto operations attempt to use it.
 */
@ApplicationScoped
@Startup
public class BouncyCastleInitializer {

    private static final Logger LOG = Logger.getLogger(BouncyCastleInitializer.class);

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
            LOG.debug("Registered BouncyCastle security provider");
        }
    }

    public BouncyCastleInitializer() {
    }
}
