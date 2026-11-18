package io.floci.az.services.containerapps;

import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudSuffix;
import io.floci.az.core.cloud.DnsSuffixMatch;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The secret a Container App secret's {@code keyVaultUrl} points at: {@code https://{vault}.vault.azure.net/secrets/
 * {name}[/{version}]} (Microsoft.App 2026-01-01, {@code Secret.keyVaultUrl}), with the Key Vault suffix of any cloud
 * in the catalog, or the same path under floci-az's own account form,
 * {@code {base}/{vault}-keyvault/secrets/{name}[/{version}]}. The vault name is read the way the routing filter reads
 * it, so the reference finds the vault a client wrote the secret to.
 *
 * @param version the secret's version, or null for the current one
 */
record KeyVaultSecretReference(String vault, String name, String version) {

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
        Optional<DnsSuffixMatch> vaultHost = CloudCatalog.stripKnownDnsSuffix(uri.getHost())
                .filter(match -> match.service() == CloudSuffix.KEYVAULT && !match.name().contains("."));
        if (vaultHost.isPresent()) {
            // The catalog lowercases the host, as the routing filter does before taking the vault label.
            return secretPath(vaultHost.get().name(), segments);
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
