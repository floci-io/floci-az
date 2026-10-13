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
