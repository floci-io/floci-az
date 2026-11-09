package io.floci.az.services.apim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The child resources of API Management services, kept through the storage layer under the store name
 * {@code apim-children}, keyed by the lowercased path below the service ({@code sub/rg/apim/service/...}), so paths
 * that differ only in case address one entry. The services themselves are in {@link ApiManagementService}'s resource
 * store. An {@code ArmResourceStore} does not fit here: a product's link to an API is not a resource, and the gateway
 * reads by this path.
 */
@ApplicationScoped
public class ApiManagementStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {
    };
    private static final TypeReference<String> TEXT = new TypeReference<>() {
    };

    private final Map<String, Map<String, Object>> apis;
    private final Map<String, Map<String, Object>> operations;
    private final Map<String, Map<String, Object>> policies;
    private final Map<String, Map<String, Object>> products;
    private final Map<String, Map<String, Object>> subscriptions;
    private final Map<String, Map<String, Object>> namedValues;
    private final Map<String, Map<String, Object>> backends;
    private final Map<String, String> productApis;

    @Inject
    public ApiManagementStore(StorageFactory storageFactory) {
        StorageBackend<String, StoredObject> backend = storageFactory.create("apim-children");
        this.apis = new StoredMap<>(backend, "apis|", DOCUMENT);
        this.operations = new StoredMap<>(backend, "operations|", DOCUMENT);
        this.policies = new StoredMap<>(backend, "policies|", DOCUMENT);
        this.products = new StoredMap<>(backend, "products|", DOCUMENT);
        this.subscriptions = new StoredMap<>(backend, "subscriptions|", DOCUMENT);
        this.namedValues = new StoredMap<>(backend, "namedValues|", DOCUMENT);
        this.backends = new StoredMap<>(backend, "backends|", DOCUMENT);
        this.productApis = new StoredMap<>(backend, "productApis|", TEXT);
    }

    Map<String, Map<String, Object>> apis() {
        return apis;
    }

    Map<String, Map<String, Object>> operations() {
        return operations;
    }

    Map<String, Map<String, Object>> policies() {
        return policies;
    }

    Map<String, Map<String, Object>> products() {
        return products;
    }

    Map<String, Map<String, Object>> subscriptions() {
        return subscriptions;
    }

    Map<String, Map<String, Object>> namedValues() {
        return namedValues;
    }

    Map<String, Map<String, Object>> backends() {
        return backends;
    }

    Map<String, String> productApis() {
        return productApis;
    }

    /**
     * One kind of child as a map over the shared backend, each key carrying the kind's prefix. Reads decode a fresh
     * value, so a change to one is only kept by putting it back.
     */
    private static final class StoredMap<V> extends AbstractMap<String, V> {

        private final StorageBackend<String, StoredObject> backend;
        private final String prefix;
        private final TypeReference<V> type;

        StoredMap(StorageBackend<String, StoredObject> backend, String prefix, TypeReference<V> type) {
            this.backend = backend;
            this.prefix = prefix;
            this.type = type;
        }

        @Override
        public V get(Object key) {
            return backend.get(prefix + key).map(this::decode).orElse(null);
        }

        @Override
        public boolean containsKey(Object key) {
            return backend.get(prefix + key).isPresent();
        }

        @Override
        public V put(String key, V value) {
            V previous = get(key);
            String stored = prefix + key;
            backend.put(stored, new StoredObject(stored, encode(value), Map.of(), Instant.now(),
                UUID.randomUUID().toString()));
            return previous;
        }

        @Override
        public V remove(Object key) {
            Optional<StoredObject> removed = backend.remove(prefix + key);
            return removed.map(this::decode).orElse(null);
        }

        @Override
        public void clear() {
            keys().forEach(key -> backend.delete(prefix + key));
        }

        @Override
        public Set<Entry<String, V>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, V>> iterator() {
                    Iterator<String> keys = keys().iterator();
                    return new Iterator<>() {
                        private Entry<String, V> upcoming;
                        private String current;

                        /**
                         * Reads ahead to the next key that still has a value: the keys are a snapshot, so one
                         * deleted since the iteration started is skipped rather than handed out with a null value.
                         */
                        @Override
                        public boolean hasNext() {
                            while (upcoming == null && keys.hasNext()) {
                                String key = keys.next();
                                V value = get(key);
                                if (value != null) {
                                    upcoming = new SimpleImmutableEntry<>(key, value);
                                }
                            }
                            return upcoming != null;
                        }

                        @Override
                        public Entry<String, V> next() {
                            if (!hasNext()) {
                                throw new NoSuchElementException();
                            }
                            Entry<String, V> entry = upcoming;
                            upcoming = null;
                            current = entry.getKey();
                            return entry;
                        }

                        @Override
                        public void remove() {
                            if (current == null) {
                                throw new IllegalStateException();
                            }
                            backend.delete(prefix + current);
                            current = null;
                        }
                    };
                }

                @Override
                public int size() {
                    return keys().size();
                }
            };
        }

        /** The keys of this kind, without its prefix: a snapshot, so an entry can be removed while iterating. */
        private List<String> keys() {
            List<String> keys = new ArrayList<>();
            for (String key : List.copyOf(backend.keys())) {
                if (key.startsWith(prefix)) {
                    keys.add(key.substring(prefix.length()));
                }
            }
            return keys;
        }

        private byte[] encode(V value) {
            try {
                return MAPPER.writeValueAsBytes(value);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Could not encode an API Management child resource", e);
            }
        }

        private V decode(StoredObject stored) {
            try {
                return MAPPER.readValue(new String(stored.data(), StandardCharsets.UTF_8), type);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Could not decode API Management child resource " + stored.key(), e);
            }
        }
    }
}
