package io.floci.az.services.containerapps;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The secret a Container App secret's {@code keyVaultUrl} points at: {@code https://{vault}.vault.azure.net/secrets/
 * {name}[/{version}]} (Microsoft.App 2026-01-01, {@code Secret.keyVaultUrl}), or the same path under floci-az's own
 * account form, {@code {base}/{vault}-keyvault/secrets/{name}[/{version}]}. The vault name is read the way the
 * routing filter reads it, so the reference finds the vault a client wrote the secret to.
 *
 * @param version the secret's version, or null for the current one
 */
record KeyVaultSecretReference(String vault, String name, String version) {

    private static final String VAULT_HOST_SUFFIX = ".vault.azure.net";
    private static final String ACCOUNT_SUFFIX = "-keyvault";

    static Optional<KeyVaultSecretReference> parse(String keyVaultUrl) {
        URI uri;
        try {
            uri = URI.create(keyVaultUrl.strip());
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        if (uri.getHost() == null || uri.getPath() == null) {
            return Optional.empty();
        }
        List<String> segments = Arrays.stream(uri.getPath().split("/")).filter(s -> !s.isEmpty()).toList();
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.endsWith(VAULT_HOST_SUFFIX) && host.length() > VAULT_HOST_SUFFIX.length()) {
            // Hostnames are case-insensitive, and the routing filter lowercases them before taking the vault label.
            return secretPath(host.substring(0, host.indexOf('.')), segments);
        }
        if (!segments.isEmpty() && segments.getFirst().endsWith(ACCOUNT_SUFFIX)
                && segments.getFirst().length() > ACCOUNT_SUFFIX.length()) {
            String account = segments.getFirst();
            return secretPath(account.substring(0, account.length() - ACCOUNT_SUFFIX.length()),
                    segments.subList(1, segments.size()));
        }
        return Optional.empty();
    }

    /** {@code secrets/{name}} or {@code secrets/{name}/{version}}. */
    private static Optional<KeyVaultSecretReference> secretPath(String vault, List<String> segments) {
        if (segments.size() < 2 || segments.size() > 3 || !"secrets".equalsIgnoreCase(segments.getFirst())) {
            return Optional.empty();
        }
        return Optional.of(new KeyVaultSecretReference(vault, segments.get(1),
                segments.size() == 3 ? segments.get(2) : null));
    }
}
