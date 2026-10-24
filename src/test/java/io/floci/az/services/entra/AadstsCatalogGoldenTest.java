package io.floci.az.services.entra;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the token service's error catalogue. Clients key on {@code error}, {@code error_codes} and
 * the HTTP status, so a change to any entry here is a wire change and must be deliberate.
 */
class AadstsCatalogGoldenTest {

    private record Entry(int code, String oauthError, int status, String template) {
    }

    private static final Map<AadstsError, Entry> GOLDEN = new LinkedHashMap<>();

    static {
        GOLDEN.put(AadstsError.UNSUPPORTED_ENDPOINT,
                new Entry(90014, "invalid_request", 404, "Unsupported Entra endpoint: %s"));
        GOLDEN.put(AadstsError.MISSING_REDIRECT_URI,
                new Entry(90014, "invalid_request", 400, "redirect_uri is required"));
        GOLDEN.put(AadstsError.MISSING_CLIENT_ID,
                new Entry(90014, "invalid_request", 400, "client_id is required"));
        GOLDEN.put(AadstsError.NO_DEV_USER,
                new Entry(90014, "invalid_request", 400, "no dev user available to auto-approve the request"));
        GOLDEN.put(AadstsError.UNSUPPORTED_RESPONSE_TYPE,
                new Entry(90014, "unsupported_response_type", 400,
                        "response_type '%s' is not supported in this phase"));
        GOLDEN.put(AadstsError.UNSUPPORTED_GRANT_TYPE,
                new Entry(70003, "unsupported_grant_type", 400, "grant_type '%s' is not supported in this phase"));
        GOLDEN.put(AadstsError.MISSING_CODE,
                new Entry(90014, "invalid_request", 400, "code is required"));
        GOLDEN.put(AadstsError.CODE_INVALID,
                new Entry(70000, "invalid_grant", 400, "authorization code is invalid or has already been used"));
        GOLDEN.put(AadstsError.CODE_EXPIRED,
                new Entry(70000, "invalid_grant", 400, "authorization code has expired"));
        GOLDEN.put(AadstsError.CODE_TENANT_MISMATCH,
                new Entry(70000, "invalid_grant", 400, "authorization code was issued for a different tenant"));
        GOLDEN.put(AadstsError.REDIRECT_URI_MISMATCH,
                new Entry(70000, "invalid_grant", 400, "redirect_uri does not match the authorization request"));
        GOLDEN.put(AadstsError.CLIENT_ID_MISMATCH,
                new Entry(70000, "invalid_grant", 400, "client_id does not match the authorization request"));
        GOLDEN.put(AadstsError.PKCE_MISMATCH,
                new Entry(70000, "invalid_grant", 400, "code_verifier does not match the code_challenge"));
        GOLDEN.put(AadstsError.CODE_UNKNOWN_USER,
                new Entry(70000, "invalid_grant", 400, "authorization code does not resolve to a known user"));
        GOLDEN.put(AadstsError.MISSING_REQUIRED_PARAMETER,
                new Entry(900144, "invalid_request", 400,
                        "The request body must contain the following parameter: '%s'."));
        GOLDEN.put(AadstsError.APPLICATION_NOT_FOUND,
                new Entry(700016, "unauthorized_client", 400,
                        "Application with identifier '%s' was not found in the directory '%s'. This can happen if "
                                + "the application has not been installed by the administrator of the tenant or "
                                + "consented to by any user in the tenant. You may have sent your authentication "
                                + "request to the wrong tenant."));
        GOLDEN.put(AadstsError.INVALID_CLIENT_SECRET,
                new Entry(7000215, "invalid_client", 401,
                        "Invalid client secret provided. Ensure the secret being sent in the request is the client "
                                + "secret value, not the client secret ID, for a secret added to app '%s'."));
        GOLDEN.put(AadstsError.EXPIRED_CLIENT_SECRET,
                new Entry(7000222, "invalid_client", 401,
                        "The provided client secret keys for app '%s' are expired. Visit the Azure portal to create "
                                + "new keys for your app or consider using certificate credentials for added security."));
        GOLDEN.put(AadstsError.MISSING_CLIENT_CREDENTIAL,
                new Entry(7000218, "invalid_client", 401,
                        "The request body must contain the following parameter: 'client_assertion' or "
                                + "'client_secret'."));
        GOLDEN.put(AadstsError.SERIALISATION_FAILED,
                new Entry(50000, "server_error", 500, "serialisation failed"));
    }

    @Test
    void everyCatalogueEntryIsPinned() {
        assertEquals(List.copyOf(GOLDEN.keySet()), Arrays.asList(AadstsError.values()),
                "a new or reordered AadstsError needs a golden entry here");
    }

    @Test
    void everyEntryKeepsItsCodeStatusAndMessage() {
        for (Map.Entry<AadstsError, Entry> golden : GOLDEN.entrySet()) {
            AadstsError error = golden.getKey();
            Entry expected = golden.getValue();
            assertEquals(expected, new Entry(error.code(), error.oauthError(), error.httpStatus(),
                    error.messageTemplate()), error.name());
        }
    }

    @Test
    void templatedMessagesSubstituteTheirArgument() {
        assertEquals("grant_type 'refresh_token' is not supported in this phase",
                AadstsError.UNSUPPORTED_GRANT_TYPE.message("refresh_token"));
        assertEquals("code is required", AadstsError.MISSING_CODE.message());
    }
}
