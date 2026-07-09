package io.floci.az.services.managedidentity;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory ARM resource state for Microsoft.ManagedIdentity — no StorageBackend needed.
 * All access goes through methods: the handler holds a normal-scoped client proxy, and
 * direct field reads on a proxy would hit the proxy's own (empty) maps instead of this
 * contextual instance.
 */
@ApplicationScoped
public class ManagedIdentityStore {

    private final Map<String, Map<String, Object>> identities = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> federatedCredentials = new ConcurrentHashMap<>();

    static String identityKey(String sub, String rg, String name) {
        return sub.toLowerCase() + "/" + rg.toLowerCase() + "/" + name.toLowerCase();
    }

    static String ficKey(String identityKey, String ficName) {
        return identityKey + "/fic/" + ficName.toLowerCase();
    }

    public Map<String, Object> getIdentity(String key) {
        return identities.get(key);
    }

    public void putIdentity(String key, Map<String, Object> resource) {
        identities.put(key, resource);
    }

    public Map<String, Object> removeIdentity(String key) {
        // Clean the children first: if the same key is re-created between these two ops,
        // the removeIf must not delete the new identity's freshly-added FICs.
        federatedCredentials.keySet().removeIf(k -> k.startsWith(key + "/fic/"));
        return identities.remove(key);
    }

    public List<Map<String, Object>> listIdentities() {
        return new ArrayList<>(identities.values());
    }

    public boolean identityExists(String key) {
        return identities.containsKey(key);
    }

    public Map<String, Object> getFederatedCredential(String key) {
        return federatedCredentials.get(key);
    }

    public boolean putFederatedCredential(String key, Map<String, Object> resource) {
        return federatedCredentials.put(key, resource) != null;
    }

    public Map<String, Object> removeFederatedCredential(String key) {
        return federatedCredentials.remove(key);
    }

    public List<Map<String, Object>> listFederatedCredentials(String identityKey) {
        String prefix = identityKey + "/fic/";
        List<Map<String, Object>> result = new ArrayList<>();
        federatedCredentials.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                result.add(v);
            }
        });
        return result;
    }

    public Optional<Map<String, Object>> findByClientId(String clientId) {
        return findByProperty("clientId", clientId);
    }

    public Optional<Map<String, Object>> findByPrincipalId(String principalId) {
        return findByProperty("principalId", principalId);
    }

    public Optional<Map<String, Object>> findByResourceId(String armId) {
        return identities.values().stream()
                .filter(r -> armId.equalsIgnoreCase(String.valueOf(r.get("id"))))
                .findFirst();
    }

    private Optional<Map<String, Object>> findByProperty(String key, String value) {
        return identities.values().stream()
                .filter(r -> r.get("properties") instanceof Map<?, ?> props
                        && value.equalsIgnoreCase(String.valueOf(props.get(key))))
                .findFirst();
    }

    public void clearAll() {
        identities.clear();
        federatedCredentials.clear();
    }
}
