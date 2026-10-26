package io.floci.az.services.entra;

import java.util.Locale;

/**
 * The catalogue of token-service errors this emulator returns. Each entry fixes the {@code AADSTS}
 * number, the OAuth 2.0 {@code error} code, the HTTP status and the message template, so the same
 * failure always produces the same envelope. The body shape (error, error_description with the
 * {@code AADSTSnnn:} prefix, error_codes, timestamp, trace_id, correlation_id, error_uri) follows
 * {@code entra-docs/docs/identity-platform/reference-error-codes.md}; {@link EntraResponses#error}
 * renders it.
 */
public enum AadstsError {

    UNSUPPORTED_ENDPOINT(90014, "invalid_request", 404, "Unsupported Entra endpoint: %s"),
    MISSING_REDIRECT_URI(90014, "invalid_request", 400, "redirect_uri is required"),
    MISSING_CLIENT_ID(90014, "invalid_request", 400, "client_id is required"),
    NO_DEV_USER(90014, "invalid_request", 400, "no dev user available to auto-approve the request"),
    UNSUPPORTED_RESPONSE_TYPE(90014, "unsupported_response_type", 400,
            "response_type '%s' is not supported in this phase"),
    UNSUPPORTED_GRANT_TYPE(70003, "unsupported_grant_type", 400, "grant_type '%s' is not supported in this phase"),
    MISSING_CODE(90014, "invalid_request", 400, "code is required"),
    CODE_INVALID(70000, "invalid_grant", 400, "authorization code is invalid or has already been used"),
    CODE_EXPIRED(70000, "invalid_grant", 400, "authorization code has expired"),
    CODE_TENANT_MISMATCH(70000, "invalid_grant", 400, "authorization code was issued for a different tenant"),
    REDIRECT_URI_MISMATCH(70000, "invalid_grant", 400, "redirect_uri does not match the authorization request"),
    CLIENT_ID_MISMATCH(70000, "invalid_grant", 400, "client_id does not match the authorization request"),
    PKCE_MISMATCH(70000, "invalid_grant", 400, "code_verifier does not match the code_challenge"),
    CODE_UNKNOWN_USER(70000, "invalid_grant", 400, "authorization code does not resolve to a known user"),
    REFRESH_TOKEN_INVALID(70000, "invalid_grant", 400,
            "refresh token is invalid, revoked, or does not match the client, tenant or user"),
    REFRESH_TOKEN_EXPIRED(700082, "invalid_grant", 400, "refresh token has expired due to inactivity"),
    SPA_REFRESH_TOKEN_EXPIRED(700084, "invalid_grant", 400,
            "refresh token was issued to a single-page app and has reached its fixed lifetime"),
    INVALID_SCOPE(70011, "invalid_scope", 400, "The provided value for the input parameter 'scope' is not valid."),
    MISSING_REQUIRED_PARAMETER(900144, "invalid_request", 400,
            "The request body must contain the following parameter: '%s'."),
    APPLICATION_NOT_FOUND(700016, "unauthorized_client", 400,
            "Application with identifier '%s' was not found in the directory '%s'. This can happen if the "
                    + "application has not been installed by the administrator of the tenant or consented to by "
                    + "any user in the tenant. You may have sent your authentication request to the wrong tenant."),
    INVALID_CLIENT_SECRET(7000215, "invalid_client", 401,
            "Invalid client secret provided. Ensure the secret being sent in the request is the client secret "
                    + "value, not the client secret ID, for a secret added to app '%s'."),
    EXPIRED_CLIENT_SECRET(7000222, "invalid_client", 401,
            "The provided client secret keys for app '%s' are expired. Visit the Azure portal to create new keys "
                    + "for your app or consider using certificate credentials for added security."),
    MISSING_CLIENT_CREDENTIAL(7000218, "invalid_client", 401,
            "The request body must contain the following parameter: 'client_assertion' or 'client_secret'."),
    SERIALISATION_FAILED(50000, "server_error", 500, "serialisation failed");

    private final int code;
    private final String oauthError;
    private final int httpStatus;
    private final String messageTemplate;

    AadstsError(int code, String oauthError, int httpStatus, String messageTemplate) {
        this.code = code;
        this.oauthError = oauthError;
        this.httpStatus = httpStatus;
        this.messageTemplate = messageTemplate;
    }

    public int code() {
        return code;
    }

    public String oauthError() {
        return oauthError;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String messageTemplate() {
        return messageTemplate;
    }

    /** The human-readable description, without the {@code AADSTSnnn:} prefix and diagnostics. */
    public String message(Object... args) {
        return args.length == 0 ? messageTemplate : String.format(Locale.ROOT, messageTemplate, args);
    }
}
