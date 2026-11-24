package io.floci.az.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Turns on {@code floci-az.parity-overrides.strict-names}, so a resource name outside Azure's naming
 * rule for its type is refused with {@code 400 InvalidResourceName}, with every other setting at its
 * default.
 *
 * <p>Used by the {@code *StrictNamesTest} class of each service whose default mode needs no
 * further override: ACI, API Management, Container Apps, Email, Event Grid, IoT Hub, Key Vault,
 * Managed Identity, Monitor, Network, Policy, Search, Storage accounts, VM and Web. ACI, VM and
 * Container Apps used to restate {@code mocked=true}, which is already their default.
 *
 * <p>Quarkus builds one application per distinct profile class during test discovery and keeps
 * each for the rest of the test JVM, so classes that need the same overrides share this profile
 * rather than each declaring their own: every extra profile costs every CI run about 4 seconds.
 * Add a class here only when it needs exactly these overrides; a class that needs one more
 * setting gets its own profile.
 */
public class StrictNamesProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci-az.parity-overrides.strict-names", "true");
    }
}
