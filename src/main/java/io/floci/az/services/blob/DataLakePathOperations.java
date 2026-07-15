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
    private static final String OWNER = "00000000-0000-0000-0000-000000000000";
    private static final String GROUP = "00000000-0000-0000-0000-000000000000";
    private static final String FILE_PERMISSIONS = "rw-r-----";
    private static final String DIRECTORY_PERMISSIONS = "rwxr-x---";
    private static final String RESOURCE_TYPE = "DataLakeResourceType";
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

    public boolean pathExists(String account, String filesystem, String path) {
        String normalizedPath = normalizeDirectory(path);
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return true;
        }
        String exactKey = account + "/" + filesystem + "/" + normalizedPath;
        if (store.get(exactKey).isPresent()) {
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
                OWNER,
                GROUP,
                FILE_PERMISSIONS,
                quoteEtag(object.etag())
        );
    }

    private static DataLakePathListResponse.PathEntry directoryEntry(String name, StoredObject source) {
        return new DataLakePathListResponse.PathEntry(
                name,
                true,
                RFC1123_DATE_TIME.format(source.lastModified()),
                0,
                OWNER,
                GROUP,
                DIRECTORY_PERMISSIONS,
                quoteEtag(source.etag())
        );
    }

    private static String objectPrefix(String account, String filesystem, String directory) {
        String prefix = account + "/" + filesystem + "/";
        if (directory == null || directory.isBlank()) {
            return prefix;
        }
        return prefix + directory + "/";
    }

    private static boolean isInternalKey(String key) {
        return key.startsWith(BLOCK_PREFIX) || key.startsWith(NAMESPACE_PREFIX);
    }

    private static boolean isUnderDirectory(String name, String directory) {
        return directory == null || directory.isBlank() || name.startsWith(directory + "/");
    }

    private static String relativeName(String name, String directory) {
        if (directory == null || directory.isBlank()) {
            return name;
        }
        return name.substring(directory.length() + 1);
    }

    private static String joinPath(String left, String right) {
        if (left == null || left.isBlank()) {
            return right;
        }
        return left + "/" + right;
    }

    private static String normalizeDirectory(String directory) {
        if (directory == null) {
            return null;
        }
        String normalized = directory.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? null : normalized;
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
