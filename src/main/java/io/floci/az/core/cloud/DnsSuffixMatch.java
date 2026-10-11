package io.floci.az.core.cloud;

/**
 * A host recognised by {@link CloudCatalog#stripKnownDnsSuffix(String)}.
 *
 * @param cloud    the cloud whose suffix matched
 * @param service  which service the suffix belongs to
 * @param suffix   the matched DNS suffix, without a leading dot, e.g. {@code blob.core.usgovcloudapi.net}
 * @param name     what precedes the suffix, e.g. the account in {@code acct.blob.core.windows.net}; for a
 *                 location-qualified suffix it keeps the extra labels ({@code dns-1a2b.hcp.eastus} for AKS)
 */
public record DnsSuffixMatch(CloudProfile cloud, CloudSuffix service, String suffix, String name) {
}
