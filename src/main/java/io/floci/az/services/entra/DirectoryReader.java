package io.floci.az.services.entra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.entra.SecretHasher.SecretHash;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Typed, read-only views of {@link GraphDirectoryStore} for the token service and Graph. Callers
 * get records with named fields; the JSON property names live here and in the store only.
 */
@ApplicationScoped
public class DirectoryReader {

    private static final Logger LOG = Logger.getLogger(DirectoryReader.class);

    private final GraphDirectoryStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public DirectoryReader(GraphDirectoryStore store) {
        this.store = store;
    }

    public record DirectoryUser(String id, String tenantId, String userPrincipalName, String displayName,
                                String mail) {
    }

    public record DirectoryGroup(String id, String tenantId, String displayName, boolean securityEnabled) {
    }

    public record DirectoryApplication(String id, String tenantId, String appId, String displayName,
                                       List<PasswordCredential> passwordCredentials) {
    }

    public record DirectoryServicePrincipal(String id, String tenantId, String appId, String displayName) {
    }

    /**
     * One client secret of an application: a PBKDF2 hash, or the plaintext a legacy flat entry
     * still holds until the application is next written.
     */
    public record PasswordCredential(String keyId, String hint, SecretHash hash, String legacySecretText) {

        /** Whether {@code candidate} is this credential's secret. */
        public boolean matches(String candidate) {
            if (hash != null) {
                return SecretHasher.matches(hash, candidate);
            }
            return SecretHasher.matchesPlaintext(legacySecretText, candidate);
        }
    }

    /** The tenant whose directory requests without a tenant of their own act in. */
    public String defaultTenantId() {
        return store.defaultTenantId();
    }

    public Optional<DirectoryUser> user(String tenantId, String id) {
        return store.get(tenantId, DirectoryEntitySet.USERS, id).map(DirectoryReader::toUser);
    }

    public Optional<DirectoryUser> userByPrincipalName(String tenantId, String userPrincipalName) {
        return store.findByAlternateKey(tenantId, DirectoryEntitySet.USERS, "userPrincipalName", userPrincipalName)
                .map(DirectoryReader::toUser);
    }

    public Optional<DirectoryGroup> group(String tenantId, String id) {
        return store.get(tenantId, DirectoryEntitySet.GROUPS, id).map(DirectoryReader::toGroup);
    }

    /** The groups {@code memberId} directly belongs to. No nested-group transitivity yet. */
    public Set<String> memberGroupIds(String tenantId, String memberId) {
        return store.linkSources(tenantId, GraphDirectoryStore.MEMBERS, memberId);
    }

    public Optional<DirectoryApplication> applicationByAppId(String tenantId, String appId) {
        return store.findByAlternateKey(tenantId, DirectoryEntitySet.APPLICATIONS, "appId", appId)
                .map(this::toApplication);
    }

    public Optional<DirectoryServicePrincipal> servicePrincipalByAppId(String tenantId, String appId) {
        return store.findByAlternateKey(tenantId, DirectoryEntitySet.SERVICE_PRINCIPALS, "appId", appId)
                .map(DirectoryReader::toServicePrincipal);
    }

    private static DirectoryUser toUser(DirectoryObject object) {
        return new DirectoryUser(object.id(), object.tenantId(), object.text("userPrincipalName"),
                object.text("displayName"), object.text("mail"));
    }

    private static DirectoryGroup toGroup(DirectoryObject object) {
        return new DirectoryGroup(object.id(), object.tenantId(), object.text("displayName"),
                object.props().path("securityEnabled").asBoolean(false));
    }

    private DirectoryApplication toApplication(DirectoryObject object) {
        List<PasswordCredential> credentials = new ArrayList<>();
        for (JsonNode credential : object.props().path(GraphDirectoryStore.PASSWORD_CREDENTIALS)) {
            credentials.add(new PasswordCredential(textOrNull(credential, "keyId"), textOrNull(credential, "hint"),
                    secretHash(object, credential.get(GraphDirectoryStore.SECRET_HASH)),
                    textOrNull(credential, GraphDirectoryStore.SECRET_TEXT)));
        }
        return new DirectoryApplication(object.id(), object.tenantId(), object.text("appId"),
                object.text("displayName"), List.copyOf(credentials));
    }

    private static DirectoryServicePrincipal toServicePrincipal(DirectoryObject object) {
        return new DirectoryServicePrincipal(object.id(), object.tenantId(), object.text("appId"),
                object.text("displayName"));
    }

    private SecretHash secretHash(DirectoryObject application, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return mapper.treeToValue(node, SecretHash.class);
        } catch (JsonProcessingException e) {
            LOG.warnv(e, "Entra: application {0} has an unreadable secret hash; that secret will never match",
                    application.id());
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String property) {
        JsonNode value = node.get(property);
        return value == null || value.isNull() ? null : value.asText();
    }
}
