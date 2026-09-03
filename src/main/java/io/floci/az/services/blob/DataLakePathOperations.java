package io.floci.az.services.blob;

import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class DataLakePathOperations {

    private static final DateTimeFormatter RFC1123_DATE_TIME = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneId.of("GMT"));
    static final String DEFAULT_OWNER = "00000000-0000-0000-0000-000000000000";
    static final String DEFAULT_GROUP = "00000000-0000-0000-0000-000000000000";
    static final String DEFAULT_FILE_PERMISSIONS = "rw-r-----";
    static final String DEFAULT_DIRECTORY_PERMISSIONS = "rwxr-x---";
    static final String RESOURCE_TYPE = "DataLakeResourceType";
    static final String OWNER_KEY = "DataLakeOwner";
    static final String GROUP_KEY = "DataLakeGroup";
    static final String PERMISSIONS_KEY = "DataLakePermissions";
    static final String ACL_KEY = "DataLakeAcl";
    static final String PROPERTIES_KEY = "DataLakeProperties";
    private static final String BLOCK_PREFIX = "__blk__:";
    private static final String NAMESPACE_PREFIX = "__ns__:";

    private final StorageBackend<String, StoredObject> store;

    public DataLakePathOperations(StorageBackend<String, StoredObject> store) {
        this.store = store;
    }

    public List<DataLakePathListResponse.PathEntry> list(
            String account,
            String filesystem,
            String directory,
            boolean recursive
    ) {
        String normalizedDirectory = normalizeDirectory(directory);

        // Hadoop ABFS implements FileSystem.listStatus(path) through the ADLS
        // List Paths endpoint, even when path is an exact file. ABFS expects
        // that existing file to be represented as a singleton PathList. Handle
        // that case before scanning directory descendants; objectPrefix()
        // intentionally appends '/', so an exact file would otherwise produce
        // an empty result.
        if (normalizedDirectory != null) {
            String exactKey = account + "/" + filesystem + "/" + normalizedDirectory;
            var exactPath = store.get(exactKey);
            if (exactPath.isPresent()
                    && !"directory".equals(exactPath.get().metadata().get(RESOURCE_TYPE))) {
                return List.of(fileEntry(normalizedDirectory, exactPath.get()));
            }
        }

        String objectPrefix = objectPrefix(account, filesystem, normalizedDirectory);
        List<DataLakePathListResponse.PathEntry> entries = new ArrayList<>();
        Set<String> emittedNames = new HashSet<>();

        store.scan(key -> key.startsWith(objectPrefix) && !isInternalKey(key)).forEach(object -> {
            String name = object.metadata().getOrDefault("Name", object.key());
            if (!isUnderDirectory(name, normalizedDirectory)) {
                return;
            }

            String relativeName = relativeName(name, normalizedDirectory);
            if (relativeName.isBlank()) {
                return;
            }

            int slash = relativeName.indexOf('/');
            if (!recursive && slash >= 0) {
                String directoryName = joinPath(normalizedDirectory, relativeName.substring(0, slash));
                if (emittedNames.add(directoryName)) {
                    entries.add(directoryEntry(directoryName, object));
                }
                return;
            }

            if (emittedNames.add(name)) {
                entries.add(fileEntry(name, object));
            }
        });

        entries.sort(Comparator.comparing(DataLakePathListResponse.PathEntry::name));
        return entries;
    }


    public DeletePlan planDelete(String account, String filesystem, String path) {
        String normalizedPath = normalizeDirectory(path);
        if (normalizedPath == null || normalizedPath.isEmpty()) {
            return new DeletePlan(null, null, List.of(), false);
        }

        String exactKey = account + "/" + filesystem + "/" + normalizedPath;
        var exact = store.get(exactKey);
        String descendantPrefix = exactKey + "/";
        List<String> descendants = store.keys().stream()
                .filter(key -> key.startsWith(descendantPrefix) && !isInternalKey(key))
                .sorted()
                .toList();

        boolean directory = exact
                .map(object -> "directory".equals(object.metadata().get(RESOURCE_TYPE)))
                .orElse(!descendants.isEmpty());

        return new DeletePlan(exactKey, exact.orElse(null), descendants, directory);
    }

    public record DeletePlan(
            String exactKey,
            StoredObject exactObject,
            List<String> descendantKeys,
            boolean directory
    ) {
        public boolean exists() {
            return exactObject != null || !descendantKeys.isEmpty();
        }

        public boolean nonEmptyDirectory() {
            return directory && !descendantKeys.isEmpty();
        }
    }


    public RenamePlan planRename(
            String account,
            String sourceFilesystem,
            String sourcePath,
            String destinationFilesystem,
            String destinationPath
    ) {
        String normalizedSource = normalizeDirectory(sourcePath);
        String normalizedDestination = normalizeDirectory(destinationPath);
        if (normalizedSource == null || normalizedDestination == null) {
            return new RenamePlan(null, null, null, null, null, List.of(), false);
        }

        String sourceKey = account + "/" + sourceFilesystem + "/" + normalizedSource;
        String destinationKey = account + "/" + destinationFilesystem + "/" + normalizedDestination;
        var exact = store.get(sourceKey);
        String descendantPrefix = sourceKey + "/";
        List<String> descendants = store.keys().stream()
                .filter(key -> key.startsWith(descendantPrefix) && !isInternalKey(key))
                .sorted()
                .toList();

        boolean directory = exact
                .map(object -> "directory".equals(object.metadata().get(RESOURCE_TYPE)))
                .orElse(!descendants.isEmpty());

        return new RenamePlan(
                sourceKey,
                destinationKey,
                exact.orElse(null),
                normalizedSource,
                normalizedDestination,
                descendants,
                directory
        );
    }

    public record RenamePlan(
            String sourceKey,
            String destinationKey,
            StoredObject exactObject,
            String sourcePath,
            String destinationPath,
            List<String> descendantKeys,
            boolean directory
    ) {
        public boolean exists() {
            return exactObject != null || !descendantKeys.isEmpty();
        }

        public List<String> sourceKeys() {
            List<String> result = new ArrayList<>();
            if (exactObject != null) {
                result.add(sourceKey);
            }
            result.addAll(descendantKeys);
            return result;
        }

        public String destinationKeyFor(String sourceObjectKey) {
            if (sourceObjectKey.equals(sourceKey)) {
                return destinationKey;
            }
            return destinationKey + sourceObjectKey.substring(sourceKey.length());
        }
    }

    public boolean pathExists(String account, String filesystem, String path) {
        String normalizedPath = normalizeDirectory(path);
        if (normalizedPath == null || normalizedPath.isEmpty()) {
            return true;
        }
        String exactKey = account + "/" + filesystem + "/" + normalizedPath;
        var exactPath = store.get(exactKey);
        if (exactPath.isPresent()) {
            return true;
        }
        String descendantPrefix = exactKey + "/";
        return store.keys().stream().anyMatch(key -> key.startsWith(descendantPrefix) && !isInternalKey(key));
    }

    private static DataLakePathListResponse.PathEntry fileEntry(String name, StoredObject object) {
        if ("directory".equals(object.metadata().get(RESOURCE_TYPE))) {
            return directoryEntry(name, object);
        }
        return new DataLakePathListResponse.PathEntry(
                name,
                false,
                RFC1123_DATE_TIME.format(object.lastModified()),
                object.data().length,
                object.metadata().getOrDefault(OWNER_KEY, DEFAULT_OWNER),
                object.metadata().getOrDefault(GROUP_KEY, DEFAULT_GROUP),
                object.metadata().getOrDefault(PERMISSIONS_KEY, DEFAULT_FILE_PERMISSIONS),
                quoteEtag(object.etag())
        );
    }

    private static DataLakePathListResponse.PathEntry directoryEntry(String name, StoredObject source) {
        boolean explicitDirectory = "directory".equals(source.metadata().get(RESOURCE_TYPE));
        return new DataLakePathListResponse.PathEntry(
                name,
                true,
                RFC1123_DATE_TIME.format(source.lastModified()),
                0,
                explicitDirectory ? source.metadata().getOrDefault(OWNER_KEY, DEFAULT_OWNER) : DEFAULT_OWNER,
                explicitDirectory ? source.metadata().getOrDefault(GROUP_KEY, DEFAULT_GROUP) : DEFAULT_GROUP,
                explicitDirectory ? source.metadata().getOrDefault(PERMISSIONS_KEY, DEFAULT_DIRECTORY_PERMISSIONS)
                        : DEFAULT_DIRECTORY_PERMISSIONS,
                explicitDirectory ? quoteEtag(source.etag())
                        : quoteEtag("implicit-" + Integer.toHexString(name.hashCode()))
        );
    }

    private static String objectPrefix(String account, String filesystem, String directory) {
        String prefix = account + "/" + filesystem + "/";
        if (directory == null || directory.isEmpty()) {
            return prefix;
        }
        return prefix + directory + "/";
    }

    private static boolean isInternalKey(String key) {
        return key.startsWith(BLOCK_PREFIX) || key.startsWith(NAMESPACE_PREFIX);
    }

    private static boolean isUnderDirectory(String name, String directory) {
        return directory == null || directory.isEmpty() || name.startsWith(directory + "/");
    }

    private static String relativeName(String name, String directory) {
        if (directory == null || directory.isEmpty()) {
            return name;
        }
        return name.substring(directory.length() + 1);
    }

    private static String joinPath(String left, String right) {
        if (left == null || left.isEmpty()) {
            return right;
        }
        return left + "/" + right;
    }

    private static String normalizeDirectory(String directory) {
        if (directory == null) {
            return null;
        }
        String normalized = directory;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private static String quoteEtag(String etag) {
        if (etag == null || etag.isBlank()) {
            return "\"\"";
        }
        if (etag.startsWith("\"") && etag.endsWith("\"")) {
            return etag;
        }
        return "\"" + etag + "\"";
    }
}
