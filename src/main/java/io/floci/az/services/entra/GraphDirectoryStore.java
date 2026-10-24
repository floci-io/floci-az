package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.Resettable;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.entra.EntraModels.AppRegistration;
import io.floci.az.services.entra.EntraModels.ClientSecret;
import io.floci.az.services.entra.EntraModels.Group;
import io.floci.az.services.entra.EntraModels.GroupMembership;
import io.floci.az.services.entra.EntraModels.ServicePrincipal;
import io.floci.az.services.entra.EntraModels.User;
import io.floci.az.services.entra.SecretHasher.SecretHash;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The directory behind Entra and Microsoft Graph: users, groups, applications, service principals
 * and the links between them, kept per tenant in the {@code entra} {@link StorageFactory} store.
 *
 * <p>Key layout:
 * <ul>
 *   <li>{@code obj:{tenant}:{set}:{id}}: a {@link DirectoryObject}</li>
 *   <li>{@code alt:{tenant}:{set}:{property}:{lower-cased value}}: the id an alternate key resolves to</li>
 *   <li>{@code link:{tenant}:{source}:{navigation}}: a {@link DirectoryLinks}</li>
 * </ul>
 *
 * <p>Stores written before this layout hold flat {@code user:}, {@code group:}, {@code app:},
 * {@code sp:} and {@code membership:} entries. Those are never rewritten in bulk: every read
 * looks for the tenant-keyed entry first and then for the flat one, which it presents as an object
 * of the default tenant. A flat entry moves to its tenant-keyed key only when that object is
 * written or deleted, and there is no startup migration.
 *
 * <p>Plaintext client secrets are never written: {@link #put} replaces any {@code secretText} in an
 * application's {@code passwordCredentials} with a {@link SecretHash}.
 */
@ApplicationScoped
public class GraphDirectoryStore implements Resettable {

    private static final Logger LOG = Logger.getLogger(GraphDirectoryStore.class);

    static final String OBJECT_PREFIX = "obj:";
    static final String ALTERNATE_KEY_PREFIX = "alt:";
    static final String LINK_PREFIX = "link:";
    static final String LEGACY_MEMBERSHIP_PREFIX = "membership:";

    /** The group navigation that holds direct members. */
    public static final String MEMBERS = "members";

    /** Application property holding the client secrets. */
    public static final String PASSWORD_CREDENTIALS = "passwordCredentials";
    /** A plaintext secret inside a password credential; hashed on write, never persisted. */
    public static final String SECRET_TEXT = "secretText";
    /** The stored {@link SecretHash} inside a password credential. Internal, never served by Graph. */
    public static final String SECRET_HASH = "secretHash";

    /**
     * The hash of {@link EntraStore#DEV_CLIENT_SECRET}. The dev secret is public, so its salt is a
     * fixed value (the first 16 bytes of SHA-256 of {@code "floci-az dev app"}) and the hash is
     * precomputed: seeding, which runs on every reset, then costs no key derivation.
     */
    static final SecretHash DEV_SECRET_HASH = new SecretHash(SecretHasher.ALGORITHM, 600_000,
            "RIahgjp9P1hdqKQEaDskeQ==", "D9yvBQ+VkATyOJeqvmQrbnAxL5zxsTu8qaNFsYI2VIQ=");

    private static final List<String> DIRECTORY_PREFIXES = List.of(
            OBJECT_PREFIX, ALTERNATE_KEY_PREFIX, LINK_PREFIX, LEGACY_MEMBERSHIP_PREFIX,
            DirectoryEntitySet.USERS.legacyPrefix(), DirectoryEntitySet.GROUPS.legacyPrefix(),
            DirectoryEntitySet.APPLICATIONS.legacyPrefix(), DirectoryEntitySet.SERVICE_PRINCIPALS.legacyPrefix());

    private final StorageBackend<String, StoredObject> store;
    private final String defaultTenantId;
    private final String seedFile;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Inject
    public GraphDirectoryStore(StorageFactory storageFactory, EmulatorConfig config) {
        this(storageFactory.create("entra"), config.services().entra().defaultTenantId(),
                config.services().entra().seedFile().orElse(null));
    }

    GraphDirectoryStore(StorageBackend<String, StoredObject> store, String defaultTenantId) {
        this(store, defaultTenantId, null);
    }

    GraphDirectoryStore(StorageBackend<String, StoredObject> store, String defaultTenantId, String seedFile) {
        this.store = store;
        this.defaultTenantId = defaultTenantId;
        this.seedFile = seedFile;
    }

    /** A located object and the key it was read from. */
    private record Located(DirectoryObject object, String key, boolean legacy) {
    }

    /** A located link set and the key it was read from. */
    private record LocatedLinks(DirectoryLinks links, String key, boolean legacy) {
    }

    /**
     * Seeds the zero-setup dev app, service principal, user and group into the default tenant,
     * each only when no entry for it exists under either layout.
     */
    @PostConstruct
    void seed() {
        String tenantId = defaultTenantId;
        if (findByAlternateKey(tenantId, DirectoryEntitySet.APPLICATIONS, "appId", EntraStore.DEV_CLIENT_ID).isEmpty()) {
            ObjectNode credential = mapper.createObjectNode();
            credential.put("keyId", "dev");
            credential.put("hint", "flo");
            credential.set(SECRET_HASH, mapper.valueToTree(DEV_SECRET_HASH));
            put(application(tenantId, EntraStore.DEV_APP_OBJECT_ID, EntraStore.DEV_CLIENT_ID,
                    "floci-az dev app", List.of(credential)));
            put(servicePrincipal(tenantId, EntraStore.DEV_SP_OBJECT_ID, EntraStore.DEV_CLIENT_ID, "floci-az dev app"));
            LOG.infov("Seeded Entra dev app registration (client_id={0})", EntraStore.DEV_CLIENT_ID);
        }
        if (get(tenantId, DirectoryEntitySet.USERS, EntraStore.DEV_USER_OBJECT_ID).isEmpty()) {
            put(user(tenantId, EntraStore.DEV_USER_OBJECT_ID, EntraStore.DEV_USER_UPN, "floci-az dev user",
                    EntraStore.DEV_USER_UPN));
            put(group(tenantId, EntraStore.DEV_GROUP_OBJECT_ID, EntraStore.DEV_GROUP_NAME, true));
            addLink(tenantId, EntraStore.DEV_GROUP_OBJECT_ID, MEMBERS, EntraStore.DEV_USER_OBJECT_ID);
            LOG.infov("Seeded Entra dev user/group (upn={0}, group={1})",
                    EntraStore.DEV_USER_UPN, EntraStore.DEV_GROUP_NAME);
        }
        if (seedFile != null) {
            DirectorySeed.load(Path.of(seedFile), defaultTenantId, this);
        }
    }

    /** Wipes every directory entry, in both layouts, then reseeds the dev fixtures. */
    @Override
    public synchronized void clear() {
        Set<String> doomed = new HashSet<>();
        for (String key : Set.copyOf(store.keys())) {
            if (isDirectoryKey(key)) {
                doomed.add(key);
            }
        }
        store.applyBatch(Map.of(), doomed);
        seed();
    }

    /** The tenant whose directory the flat legacy entries belong to. */
    public String defaultTenantId() {
        return defaultTenantId;
    }

    // ── Objects ──────────────────────────────────────────────────────────────────

    public Optional<DirectoryObject> get(String tenantId, DirectoryEntitySet set, String id) {
        return locate(tenantId, set, id).map(Located::object);
    }

    /** Looks an object up by one of its set's alternate keys, ignoring case. */
    public Optional<DirectoryObject> findByAlternateKey(String tenantId, DirectoryEntitySet set, String property,
                                                        String value) {
        return locateByAlternateKey(tenantId, set, property, value).map(Located::object);
    }

    /** Every object in a set, tenant-keyed entries first; a flat entry shows only when not yet moved. */
    public List<DirectoryObject> list(String tenantId, DirectoryEntitySet set) {
        Map<String, DirectoryObject> byId = new LinkedHashMap<>();
        String prefix = objectPrefix(tenantId, set);
        for (StoredObject stored : store.scan(key -> key.startsWith(prefix))) {
            DirectoryObject object = parse(stored, DirectoryObject.class);
            byId.put(object.id(), object);
        }
        if (isDefaultTenant(tenantId)) {
            legacyEntries(set).forEach(located -> byId.putIfAbsent(located.object().id(), located.object()));
        }
        return List.copyOf(byId.values());
    }

    /**
     * Creates or replaces an object under its tenant-keyed key, refreshing its alternate-key index.
     * When the object was still held by a flat legacy entry, that entry is deleted in the same
     * batch: this is the only way a legacy entry moves.
     */
    public synchronized void put(DirectoryObject object) {
        DirectoryEntitySet set = entitySet(object.entitySet());
        Optional<Located> previous = locate(object.tenantId(), set, object.id());
        Instant created = previous.map(located -> located.object().created())
                .or(() -> Optional.ofNullable(object.created()))
                .orElseGet(Instant::now);
        DirectoryObject toWrite = new DirectoryObject(object.id(), object.tenantId(), set.setName(),
                withHashedSecrets(object.props()), created);

        Map<String, StoredObject> puts = new LinkedHashMap<>();
        Set<String> deletes = new LinkedHashSet<>();
        String key = objectKey(toWrite.tenantId(), set, toWrite.id());
        puts.put(key, stored(key, toWrite));
        for (String property : set.alternateKeys()) {
            String value = toWrite.text(property);
            if (value != null) {
                String indexKey = alternateKey(toWrite.tenantId(), set, property, value);
                puts.put(indexKey, storedText(indexKey, toWrite.id()));
            }
        }
        previous.ifPresent(located -> deletes.addAll(staleKeys(located, set, puts.keySet())));
        store.applyBatch(puts, deletes);
    }

    /** Deletes an object (from whichever layout holds it), its alternate keys and its outgoing links. */
    public synchronized boolean delete(String tenantId, DirectoryEntitySet set, String id) {
        Optional<Located> found = locate(tenantId, set, id);
        if (found.isEmpty()) {
            return false;
        }
        Set<String> deletes = new LinkedHashSet<>(staleKeys(found.get(), set, Set.of()));
        String linkPrefix = LINK_PREFIX + tenantId + ":" + id + ":";
        for (String key : Set.copyOf(store.keys())) {
            if (key.startsWith(linkPrefix)) {
                deletes.add(key);
            }
        }
        if (isDefaultTenant(tenantId) && set == DirectoryEntitySet.GROUPS) {
            deletes.add(LEGACY_MEMBERSHIP_PREFIX + id);
        }
        store.applyBatch(Map.of(), deletes);
        return true;
    }

    // ── Links ────────────────────────────────────────────────────────────────────

    /** The targets of {@code navigation} from {@code sourceId}, in insertion order. */
    public Set<String> links(String tenantId, String sourceId, String navigation) {
        List<String> targets = locateLinks(tenantId, sourceId, navigation)
                .map(located -> located.links().targetIds())
                .orElse(List.of());
        return new LinkedHashSet<>(targets);
    }

    public synchronized void addLink(String tenantId, String sourceId, String navigation, String targetId) {
        mutateLinks(tenantId, sourceId, navigation, targets -> targets.add(targetId));
    }

    public synchronized void removeLink(String tenantId, String sourceId, String navigation, String targetId) {
        mutateLinks(tenantId, sourceId, navigation, targets -> targets.remove(targetId));
    }

    /** The ids of every object whose {@code navigation} links to {@code targetId}, for example a member's groups. */
    public Set<String> linkSources(String tenantId, String navigation, String targetId) {
        Set<String> sources = new LinkedHashSet<>();
        Set<String> tenantKeyedSources = new HashSet<>();
        String prefix = LINK_PREFIX + tenantId + ":";
        for (StoredObject stored : store.scan(key -> key.startsWith(prefix))) {
            DirectoryLinks links = parse(stored, DirectoryLinks.class);
            if (!navigation.equals(links.navigation())) {
                continue;
            }
            tenantKeyedSources.add(links.sourceId());
            if (links.targetIds().contains(targetId)) {
                sources.add(links.sourceId());
            }
        }
        if (isDefaultTenant(tenantId) && MEMBERS.equals(navigation)) {
            for (StoredObject stored : store.scan(key -> key.startsWith(LEGACY_MEMBERSHIP_PREFIX))) {
                GroupMembership membership = parse(stored, GroupMembership.class);
                if (!tenantKeyedSources.contains(membership.groupId())
                        && membership.memberObjectIds().contains(targetId)) {
                    sources.add(membership.groupId());
                }
            }
        }
        return sources;
    }

    private void mutateLinks(String tenantId, String sourceId, String navigation, Consumer<Set<String>> mutation) {
        Optional<LocatedLinks> current = locateLinks(tenantId, sourceId, navigation);
        Set<String> targets = new LinkedHashSet<>(
                current.map(located -> located.links().targetIds()).orElse(List.of()));
        mutation.accept(targets);
        String key = linkKey(tenantId, sourceId, navigation);
        Map<String, StoredObject> puts = Map.of(key,
                stored(key, new DirectoryLinks(tenantId, sourceId, navigation, List.copyOf(targets))));
        Set<String> deletes = current.filter(LocatedLinks::legacy).map(located -> Set.of(located.key()))
                .orElse(Set.of());
        store.applyBatch(puts, deletes);
    }

    private Optional<LocatedLinks> locateLinks(String tenantId, String sourceId, String navigation) {
        String key = linkKey(tenantId, sourceId, navigation);
        Optional<StoredObject> current = store.get(key);
        if (current.isPresent()) {
            return Optional.of(new LocatedLinks(parse(current.get(), DirectoryLinks.class), key, false));
        }
        if (!isDefaultTenant(tenantId) || !MEMBERS.equals(navigation)) {
            return Optional.empty();
        }
        String legacyKey = LEGACY_MEMBERSHIP_PREFIX + sourceId;
        return store.get(legacyKey).map(stored -> {
            GroupMembership membership = parse(stored, GroupMembership.class);
            return new LocatedLinks(new DirectoryLinks(tenantId, sourceId, navigation,
                    List.copyOf(membership.memberObjectIds())), legacyKey, true);
        });
    }

    // ── Object builders ──────────────────────────────────────────────────────────

    public static DirectoryObject user(String tenantId, String id, String userPrincipalName, String displayName,
                                       String mail) {
        ObjectNode props = JsonNodeFactory.instance.objectNode();
        props.put("userPrincipalName", userPrincipalName);
        props.put("displayName", displayName);
        if (mail != null) {
            props.put("mail", mail);
        }
        return new DirectoryObject(id, tenantId, DirectoryEntitySet.USERS.setName(), props, null);
    }

    public static DirectoryObject group(String tenantId, String id, String displayName, boolean securityEnabled) {
        ObjectNode props = JsonNodeFactory.instance.objectNode();
        props.put("displayName", displayName);
        props.put("securityEnabled", securityEnabled);
        return new DirectoryObject(id, tenantId, DirectoryEntitySet.GROUPS.setName(), props, null);
    }

    /** An application; each credential carries {@code keyId}, {@code hint} and a {@code secretText} or {@code secretHash}. */
    public static DirectoryObject application(String tenantId, String id, String appId, String displayName,
                                              List<ObjectNode> passwordCredentials) {
        ObjectNode props = JsonNodeFactory.instance.objectNode();
        props.put("appId", appId);
        props.put("displayName", displayName);
        ArrayNode credentials = props.putArray(PASSWORD_CREDENTIALS);
        passwordCredentials.forEach(credentials::add);
        return new DirectoryObject(id, tenantId, DirectoryEntitySet.APPLICATIONS.setName(), props, null);
    }

    public static DirectoryObject servicePrincipal(String tenantId, String id, String appId, String displayName) {
        ObjectNode props = JsonNodeFactory.instance.objectNode();
        props.put("appId", appId);
        props.put("displayName", displayName);
        return new DirectoryObject(id, tenantId, DirectoryEntitySet.SERVICE_PRINCIPALS.setName(), props, null);
    }

    // ── Lookup ───────────────────────────────────────────────────────────────────

    private Optional<Located> locate(String tenantId, DirectoryEntitySet set, String id) {
        if (id == null) {
            return Optional.empty();
        }
        String key = objectKey(tenantId, set, id);
        Optional<StoredObject> current = store.get(key);
        if (current.isPresent()) {
            return Optional.of(new Located(parse(current.get(), DirectoryObject.class), key, false));
        }
        if (!isDefaultTenant(tenantId)) {
            return Optional.empty();
        }
        if (set.legacyKeyedByAppId()) {
            return legacyEntries(set).filter(located -> id.equals(located.object().id())).findFirst();
        }
        String legacyKey = set.legacyPrefix() + id;
        return store.get(legacyKey).map(stored -> legacy(set, stored));
    }

    private Optional<Located> locateByAlternateKey(String tenantId, DirectoryEntitySet set, String property,
                                                   String value) {
        if (value == null || !set.alternateKeys().contains(property)) {
            return Optional.empty();
        }
        Optional<StoredObject> index = store.get(alternateKey(tenantId, set, property, value));
        if (index.isPresent()) {
            String id = new String(index.get().data(), StandardCharsets.UTF_8);
            String key = objectKey(tenantId, set, id);
            Optional<StoredObject> current = store.get(key);
            if (current.isPresent()) {
                return Optional.of(new Located(parse(current.get(), DirectoryObject.class), key, false));
            }
        }
        if (!isDefaultTenant(tenantId)) {
            return Optional.empty();
        }
        if (set.legacyKeyedByAppId() && "appId".equals(property)) {
            String legacyKey = set.legacyPrefix() + value;
            return store.get(legacyKey).map(stored -> legacy(set, stored));
        }
        return legacyEntries(set).filter(located -> value.equalsIgnoreCase(located.object().text(property)))
                .findFirst();
    }

    private Stream<Located> legacyEntries(DirectoryEntitySet set) {
        String prefix = set.legacyPrefix();
        return store.scan(key -> key.startsWith(prefix)).stream().map(stored -> legacy(set, stored));
    }

    /** Presents a flat legacy entry as an object of the default tenant, without touching it. */
    private Located legacy(DirectoryEntitySet set, StoredObject stored) {
        String tenantId = defaultTenantId;
        DirectoryObject object = switch (set) {
            case USERS -> {
                User user = parse(stored, User.class);
                yield user(tenantId, user.objectId(), user.upn(), user.displayName(), user.email());
            }
            case GROUPS -> {
                Group group = parse(stored, Group.class);
                yield group(tenantId, group.objectId(), group.displayName(), group.securityEnabled());
            }
            case APPLICATIONS -> {
                AppRegistration app = parse(stored, AppRegistration.class);
                yield application(tenantId, app.objectId(), app.appId(), app.displayName(),
                        legacyCredentials(app.secrets()));
            }
            case SERVICE_PRINCIPALS -> {
                ServicePrincipal sp = parse(stored, ServicePrincipal.class);
                yield servicePrincipal(tenantId, sp.objectId(), sp.appId(), sp.displayName());
            }
        };
        DirectoryObject dated = new DirectoryObject(object.id(), object.tenantId(), object.entitySet(),
                object.props(), stored.lastModified());
        return new Located(dated, stored.key(), true);
    }

    private List<ObjectNode> legacyCredentials(List<ClientSecret> secrets) {
        if (secrets == null) {
            return List.of();
        }
        return secrets.stream().map(secret -> {
            ObjectNode credential = mapper.createObjectNode();
            credential.put("keyId", secret.keyId());
            credential.put("hint", secret.hint());
            credential.put(SECRET_TEXT, secret.value());
            return credential;
        }).toList();
    }

    /** Keys {@link #put} or {@link #delete} must drop for the object as it was located. */
    private Set<String> staleKeys(Located located, DirectoryEntitySet set, Set<String> rewritten) {
        Set<String> stale = new LinkedHashSet<>();
        if (located.legacy()) {
            stale.add(located.key());
            return stale;
        }
        if (!rewritten.contains(located.key())) {
            stale.add(located.key());
        }
        DirectoryObject object = located.object();
        for (String property : set.alternateKeys()) {
            String value = object.text(property);
            if (value != null) {
                String indexKey = alternateKey(object.tenantId(), set, property, value);
                if (!rewritten.contains(indexKey) && indexPointsAt(indexKey, object.id())) {
                    stale.add(indexKey);
                }
            }
        }
        return stale;
    }

    /** Whether an alternate-key index entry still names {@code id}: another object may have taken the value since. */
    private boolean indexPointsAt(String indexKey, String id) {
        return store.get(indexKey)
                .map(stored -> id.equals(new String(stored.data(), StandardCharsets.UTF_8)))
                .orElse(false);
    }

    /** A copy of {@code props} whose password credentials carry hashes, never plaintext. */
    private ObjectNode withHashedSecrets(ObjectNode props) {
        ObjectNode copy = props.deepCopy();
        JsonNode credentials = copy.get(PASSWORD_CREDENTIALS);
        if (credentials instanceof ArrayNode array) {
            for (JsonNode element : array) {
                if (element instanceof ObjectNode credential && credential.hasNonNull(SECRET_TEXT)) {
                    SecretHash hash = SecretHasher.hash(credential.get(SECRET_TEXT).asText());
                    credential.remove(SECRET_TEXT);
                    credential.set(SECRET_HASH, mapper.valueToTree(hash));
                }
            }
        }
        return copy;
    }

    // ── Keys and serialisation ───────────────────────────────────────────────────

    private boolean isDefaultTenant(String tenantId) {
        return defaultTenantId.equals(tenantId);
    }

    private static boolean isDirectoryKey(String key) {
        for (String prefix : DIRECTORY_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static DirectoryEntitySet entitySet(String setName) {
        for (DirectoryEntitySet set : DirectoryEntitySet.values()) {
            if (set.setName().equals(setName)) {
                return set;
            }
        }
        throw new IllegalArgumentException("Unknown directory entity set: " + setName);
    }

    private static String objectPrefix(String tenantId, DirectoryEntitySet set) {
        return OBJECT_PREFIX + tenantId + ":" + set.setName() + ":";
    }

    static String objectKey(String tenantId, DirectoryEntitySet set, String id) {
        return objectPrefix(tenantId, set) + id;
    }

    static String alternateKey(String tenantId, DirectoryEntitySet set, String property, String value) {
        return ALTERNATE_KEY_PREFIX + tenantId + ":" + set.setName() + ":" + property + ":"
                + value.toLowerCase(Locale.ROOT);
    }

    static String linkKey(String tenantId, String sourceId, String navigation) {
        return LINK_PREFIX + tenantId + ":" + sourceId + ":" + navigation;
    }

    private <T> T parse(StoredObject stored, Class<T> type) {
        try {
            return mapper.readValue(stored.data(), type);
        } catch (IOException e) {
            throw new UncheckedIOException(new IOException("Corrupt directory record: " + stored.key(), e));
        }
    }

    private StoredObject stored(String key, Object value) {
        try {
            return storedBytes(key, mapper.writeValueAsBytes(value));
        } catch (IOException e) {
            throw new UncheckedIOException(new IOException("Failed to write directory record: " + key, e));
        }
    }

    private static StoredObject storedText(String key, String value) {
        return storedBytes(key, value.getBytes(StandardCharsets.UTF_8));
    }

    private static StoredObject storedBytes(String key, byte[] data) {
        return new StoredObject(key, data, Map.of(), Instant.now(),
                Integer.toHexString(new String(data, StandardCharsets.UTF_8).hashCode()));
    }
}
