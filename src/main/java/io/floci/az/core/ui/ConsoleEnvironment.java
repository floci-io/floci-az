package io.floci.az.core.ui;

import io.floci.az.config.EmulatorConfig;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The environment floci-az gives the console container: the Floci Console Contract v2 core variables, then the
 * {@code az} cloud profile.
 *
 * <p>The core names one emulator ({@code FLOCI_CLOUD}), where it is ({@code FLOCI_ENDPOINT},
 * {@code FLOCI_HOSTNAME}), where its manifest is ({@code FLOCI_INFO_URL}) and which port to listen on
 * ({@code PORT}). The {@code az} profile repeats the endpoint under the name today's floci-ui reads and adds the
 * defaults it would otherwise guess, notably the subscription: floci-ui assumes the all-zero GUID, while
 * floci-az's default subscription ends in 1. No {@code AZURE_*} SDK variables are set, because a partial
 * {@code EnvironmentCredential} setup confuses {@code DefaultAzureCredential}.
 */
public final class ConsoleEnvironment {

    /** The emulator id this contract uses for floci-az. */
    public static final String CLOUD = "az";

    /** The path floci-az serves its manifest at. */
    public static final String INFO_PATH = "/_floci/info";

    /** The storage account every floci-az data-plane client uses by default. */
    public static final String DEFAULT_STORAGE_ACCOUNT = "devstoreaccount1";

    private ConsoleEnvironment() {
    }

    /**
     * @param config   the emulator configuration
     * @param endpoint the URL the console uses to reach floci-az
     * @param probe    the console's probe, which decides whether {@code FLOCI_ENDPOINT} is set
     * @param location the default location of floci-az's default cloud
     */
    public static Map<String, String> build(EmulatorConfig config, String endpoint, ConsoleProbe probe,
                                            String location) {
        String base = stripTrailingSlash(endpoint);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("FLOCI_CLOUD", CLOUD);
        if (probe.setsFlociEndpoint()) {
            env.put("FLOCI_ENDPOINT", base);
        }
        String host = hostOf(base);
        if (host != null) {
            env.put("FLOCI_HOSTNAME", host);
        }
        env.put("FLOCI_INFO_URL", base + INFO_PATH);
        env.put("PORT", String.valueOf(config.ui().internalPort()));

        env.put("FLOCI_AZURE_ENDPOINT", base);
        env.put("FLOCI_AZURE_SUBSCRIPTION_ID", config.services().arm().defaultSubscriptionId());
        env.put("FLOCI_AZURE_TENANT_ID", config.services().entra().defaultTenantId());
        if (location != null && !location.isBlank()) {
            env.put("FLOCI_AZURE_LOCATION", location);
        }
        env.put("FLOCI_AZURE_ACCOUNT_NAME", DEFAULT_STORAGE_ACCOUNT);
        return env;
    }

    static String stripTrailingSlash(String url) {
        String result = url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** The host part of {@code url} without URL brackets, or null when it does not parse. */
    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host != null && host.startsWith("[") && host.endsWith("]")) {
                return host.substring(1, host.length() - 1);
            }
            return host;
        } catch (IllegalArgumentException ignored) {
            // FLOCI_HOSTNAME is optional in the contract: an endpoint that does not parse just omits it.
            return null;
        }
    }
}
