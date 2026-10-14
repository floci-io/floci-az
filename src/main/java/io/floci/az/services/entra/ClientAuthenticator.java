package io.floci.az.services.entra;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.services.entra.DirectoryReader.DirectoryApplication;
import io.floci.az.services.entra.DirectoryReader.PasswordCredential;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks a token request's client against the directory. Enforced only when
 * {@code entra.validate-client-credentials} is {@code true}; otherwise every client is accepted,
 * and the first unregistered {@code client_id} is logged once at WARN.
 *
 * <p>When enforced:
 * <ul>
 *   <li>no {@code client_id}: AADSTS900144</li>
 *   <li>a {@code client_id} not registered in the tenant's directory: AADSTS700016</li>
 *   <li>a secret that matches none of the application's secrets: AADSTS7000215</li>
 *   <li>a grant that needs a confidential client (client_credentials) with neither a secret nor
 *       an assertion: AADSTS7000218</li>
 * </ul>
 * A {@code client_assertion} is accepted without verification: certificate and federated
 * credentials are not modelled yet. Public-client grants without a secret only need a known
 * {@code client_id}.
 */
@ApplicationScoped
public class ClientAuthenticator {

    private static final Logger LOG = Logger.getLogger(ClientAuthenticator.class);

    /** Why a client was rejected: the catalogue entry and its message arguments. */
    public record Rejection(AadstsError error, List<Object> args) {

        public Object[] argArray() {
            return args.toArray();
        }
    }

    private final EmulatorConfig config;
    private final DirectoryReader directory;
    private final AtomicBoolean warnedUnregistered = new AtomicBoolean();

    @Inject
    public ClientAuthenticator(EmulatorConfig config, DirectoryReader directory) {
        this.config = config;
        this.directory = directory;
    }

    /** Whether client credentials are enforced ({@code entra.validate-client-credentials}). */
    public boolean enforced() {
        return config.services().entra().validateClientCredentials();
    }

    /**
     * Authenticates the client under the configured mode.
     *
     * @param needsCredential whether the grant requires a confidential client
     * @return the rejection, or empty when the client is accepted
     */
    public Optional<Rejection> authenticate(ClientCredentials credentials, TenantRef tenant, boolean needsCredential) {
        if (enforced()) {
            return check(credentials, tenant, needsCredential);
        }
        warnIfUnregistered(credentials, tenant);
        return Optional.empty();
    }

    /** The enforcing check, regardless of configuration. */
    Optional<Rejection> check(ClientCredentials credentials, TenantRef tenant, boolean needsCredential) {
        String clientId = credentials.clientId();
        if (clientId == null) {
            return reject(AadstsError.MISSING_REQUIRED_PARAMETER, "client_id");
        }
        Optional<DirectoryApplication> app = directory.applicationByAppId(tenant.directoryTenantId(), clientId);
        if (app.isEmpty()) {
            return reject(AadstsError.APPLICATION_NOT_FOUND, clientId, tenant.tenantId());
        }
        String secret = credentials.clientSecret();
        if (secret != null) {
            for (PasswordCredential credential : app.get().passwordCredentials()) {
                if (credential.matches(secret)) {
                    return Optional.empty();
                }
            }
            return reject(AadstsError.INVALID_CLIENT_SECRET, clientId);
        }
        if (credentials.assertion()) {
            LOG.debugv("Entra: accepting an unverified client_assertion for {0}", clientId);
            return Optional.empty();
        }
        if (needsCredential) {
            return reject(AadstsError.MISSING_CLIENT_CREDENTIAL);
        }
        return Optional.empty();
    }

    private void warnIfUnregistered(ClientCredentials credentials, TenantRef tenant) {
        String clientId = credentials.clientId();
        if (clientId == null || warnedUnregistered.get()
                || directory.applicationByAppId(tenant.directoryTenantId(), clientId).isPresent()) {
            return;
        }
        if (warnedUnregistered.compareAndSet(false, true)) {
            LOG.warnv("Entra: issuing a token to unregistered client_id {0}; client credentials are not checked "
                    + "(set floci-az.services.entra.validate-client-credentials=true to enforce them). "
                    + "Logged once.", clientId);
        }
    }

    private static Optional<Rejection> reject(AadstsError error, Object... args) {
        return Optional.of(new Rejection(error, List.of(args)));
    }
}
