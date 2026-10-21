package io.floci.az.services.blob;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * The blob a Copy Blob's {@code x-ms-copy-source} names, parsed as a URI the way Azurite parses it: the account
 * comes from the host when the URL is host-style ({@code {account}.blob.{host}}, as Azure's
 * {@code {account}.blob.core.windows.net} and the emulator's {@code devstoreaccount1.blob.localhost}), and from the
 * first path segment otherwise ({@code http://127.0.0.1:4577/{account}/...} or a bare {@code /{account}/...}).
 * The path is percent-decoded as a URI path, so a literal {@code +} stays a {@code +}, and the {@code snapshot}
 * query parameter is kept, so a snapshot is copied rather than the live blob.
 *
 * @param snapshot the source snapshot's timestamp, or null for the base blob
 */
record CopySource(String account, String container, String blob, String snapshot) {

    private static final String BLOB_SERVICE_MARKER = "blob";

    /** {@code header} as a copy source, or empty when it is not a URL or path naming a container and a blob. */
    static Optional<CopySource> parse(String header) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(header.trim());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String path = uri.getPath();
        if (path == null) {
            return Optional.empty();
        }
        String relative = path.startsWith("/") ? path.substring(1) : path;
        String hostAccount = hostStyleAccount(uri.getHost());
        String[] parts;
        String account;
        if (hostAccount != null) {
            account = hostAccount;
            parts = relative.split("/", 2);
        } else {
            String[] all = relative.split("/", 3);
            if (all.length < 3) {
                return Optional.empty();
            }
            account = all[0];
            parts = new String[] {all[1], all[2]};
        }
        if (parts.length < 2 || account.isEmpty() || parts[0].isEmpty() || parts[1].isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CopySource(account, parts[0], parts[1], queryParameter(uri.getRawQuery(), "snapshot")));
    }

    /** The account of a host-style URL, {@code {account}.blob.{at least one more label}}, or null. */
    private static String hostStyleAccount(String host) {
        if (host == null) {
            return null;
        }
        String[] labels = host.toLowerCase(Locale.ROOT).split("\\.");
        return labels.length >= 3 && BLOB_SERVICE_MARKER.equals(labels[1]) ? labels[0] : null;
    }

    private static String queryParameter(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (name.equals(key) && eq >= 0) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
