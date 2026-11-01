package io.floci.az.services.entra;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.RequestUrls;
import io.floci.az.core.SyntheticHttpHeaders;
import io.floci.az.core.arm.ArmCall;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmRequestRule;
import io.floci.az.core.arm.ArmVerdict;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Refuses an ARM request whose Bearer token {@link TokenValidator} rejects, when
 * {@link EmulatorConfig#strictTokens()} is on. Runs before every other rule, so a refused request registers no
 * subscription and writes no Activity Log entry.
 *
 * <p>A request floci-az makes to itself ({@code ArmRouter.dispatchInternal}) passes: its caller was checked when it
 * entered the gate, and a template deployment can outlive the token that started it. Those requests are the only
 * ones in the gate with {@link SyntheticHttpHeaders}; a client's headers never are.
 *
 * <p>The accepted audiences are the scope's cloud's two ARM audiences and the emulator's own base URL, which is the
 * audience the 2022-09-01 metadata advertises, each with or without a trailing slash. The base URL follows the caller's
 * {@code Host}, because the emulator's reachable names are not a known list, so it does not count when that host is a
 * real Azure or Microsoft one: otherwise a Graph token plus {@code Host: graph.microsoft.com} would pass as ARM.
 *
 * <p>The status, codes and messages have no local source apart from the {@code WWW-Authenticate} wording for a
 * missing header, which the Java, .NET and C++ SDK challenge parsers carry as their ARM fixture: they are an emulator
 * choice, recorded in {@code docs/services/arm.md}.
 */
@ApplicationScoped
public class TokenRule implements ArmRequestRule {

    /** Position in the gate: first, since no later rule should act for an unauthenticated caller. */
    public static final int ORDER = 0;

    private static final Logger LOG = Logger.getLogger(TokenRule.class);

    private final TokenValidator validator;
    private final EmulatorConfig config;
    private final boolean strict;

    @Inject
    public TokenRule(TokenValidator validator, EmulatorConfig config) {
        this(validator, config, config.strictTokens());
    }

    TokenRule(TokenValidator validator, EmulatorConfig config, boolean strict) {
        this.validator = validator;
        this.config = config;
        this.strict = strict;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public ArmVerdict enter(ArmCall call) {
        AzureRequest request = call.request();
        if (!strict || request == null || request.headers() instanceof SyntheticHttpHeaders) {
            return ArmVerdict.proceed();
        }
        String baseUrl = RequestUrls.resolveBaseUrl(request, config);
        List<String> audiences = CloudCatalog.isAzureHost(RequestUrls.resolveAuthority(request))
            ? cloud(call).audiences()
            : Stream.concat(cloud(call).audiences().stream(), Stream.of(baseUrl)).toList();
        String authorization = request.headers() == null ? null
            : request.headers().getHeaderString(HttpHeaders.AUTHORIZATION);
        Optional<TokenValidator.Rejection> rejection = validator.validate(authorization,
            audience -> accepted(audience, audiences));
        if (rejection.isEmpty()) {
            return ArmVerdict.proceed();
        }
        // The body reads as Azure's; the log is where the emulator says which switch refused it.
        LOG.infov("Refused {0} {1}: {2} (floci-az.parity-overrides.strict-tokens is on)", call.method(),
            request.resourcePath(), rejection.get().message());
        return ArmVerdict.respond(refusal(rejection.get(), baseUrl));
    }

    private static CloudProfile cloud(ArmCall call) {
        return call.scope() == null ? CloudCatalog.forNameOrPublic(null) : call.scope().cloud();
    }

    private static boolean accepted(String audience, List<String> audiences) {
        String wanted = withoutTrailingSlash(audience);
        return audiences.stream().map(TokenRule::withoutTrailingSlash).anyMatch(wanted::equalsIgnoreCase);
    }

    private static String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static Response refusal(TokenValidator.Rejection rejection, String baseUrl) {
        return switch (rejection.reason()) {
            case MISSING -> ArmErrors.unauthorized("AuthenticationFailed", rejection.message(), baseUrl,
                rejection.message());
            case INVALID -> ArmErrors.unauthorized("InvalidAuthenticationToken", rejection.message(), baseUrl,
                "The access token is invalid.");
            case AUDIENCE -> ArmErrors.unauthorized("InvalidAuthenticationTokenAudience", rejection.message(), baseUrl,
                "The access token audience is invalid.");
        };
    }
}
