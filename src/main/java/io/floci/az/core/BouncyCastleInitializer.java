package io.floci.az.core;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jboss.logging.Logger;

import java.security.Security;

/**
 * Ensures the BouncyCastle security provider is registered at application startup.
 * floci-az does not configure {@code quarkus.security.security-providers=BC}, so this
 * registration is required — BouncyCastle must be registered before any crypto
 * operations (e.g. TLS self-signed cert generation) attempt to use it.
 */
@ApplicationScoped
@Startup
public class BouncyCastleInitializer {

    private static final Logger LOG = Logger.getLogger(BouncyCastleInitializer.class);

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
            LOG.debug("Registered BouncyCastle security provider");
        } else {
            LOG.debug("BouncyCastle provider already registered");
        }
    }

    public BouncyCastleInitializer() {
    }
}
