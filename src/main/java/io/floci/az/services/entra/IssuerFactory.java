package io.floci.az.services.entra;

import io.floci.az.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The one place that decides what the token service calls itself: the {@code iss} claim, the
 * discovery {@code issuer} and the login host error links point to. Everything here is derived
 * from the request base URL (see {@code RequestUrls.resolveBaseUrl}), so a caller reaching the
 * emulator over a Docker bridge or a TLS proxy sees URLs it can follow.
 */
@ApplicationScoped
public class IssuerFactory {

    private static final String PUBLIC_LOGIN_BASE = "https://login.microsoftonline.com";

    private final EmulatorConfig config;

    @Inject
    public IssuerFactory(EmulatorConfig config) {
        this.config = config;
    }

    /**
     * The {@code iss} claim for a token issued in {@code tenantId}: the configured
     * {@code entra.issuer} when set, otherwise {@code {base}/{tenant}/v2.0} for v2.0 tokens and
     * {@code {base}/{tenant}/} for v1.0 tokens.
     */
    public String issuer(String baseUrl, String tenantId, boolean v2) {
        return config.services().entra().issuer().orElse(
                v2 ? baseUrl + "/" + tenantId + "/v2.0" : baseUrl + "/" + tenantId + "/");
    }

    /**
     * The {@code issuer} a discovery document reports for the tenant segment it was requested
     * under. It echoes the segment (so {@code /common} reports {@code .../common/v2.0}) because
     * msal4j's {@code OidcAuthority} requires the discovery issuer to equal the authority URL.
     */
    public String discoveryIssuer(String baseUrl, String tenantSegment) {
        return baseUrl + "/" + tenantSegment + "/v2.0";
    }

    /** The login host that {@code error_uri} links point to. */
    public String loginBase() {
        return PUBLIC_LOGIN_BASE;
    }

    /** The {@code error_uri} for an {@code AADSTS} code. */
    public String errorUri(int aadstsCode) {
        return loginBase() + "/error?code=" + aadstsCode;
    }
}
