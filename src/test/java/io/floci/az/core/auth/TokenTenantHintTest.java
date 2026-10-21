package io.floci.az.core.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("TokenTenantHint: the tid of a Bearer JWT, decoded and never verified")
class TokenTenantHintTest {

    /** An unsigned JWT with {@code payload} as its claims; the hint never looks at the signature. */
    static String bearer(String payload) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
            + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".c2lnbmF0dXJl";
    }

    @Test
    void theTidOfABearerJwtIsTheHint() {
        assertEquals(Optional.of("11111111-0000-0000-0000-000000000000"),
            TokenTenantHint.of(bearer("{\"tid\":\"11111111-0000-0000-0000-000000000000\",\"oid\":\"x\"}")));
        assertEquals(Optional.of("abc"), TokenTenantHint.of(bearer("{\"tid\":\"abc\"}").replace("Bearer", "bearer")));
    }

    @Test
    void anythingElseGivesNoHint() {
        assertEquals(Optional.empty(), TokenTenantHint.of(null));
        assertEquals(Optional.empty(), TokenTenantHint.of("SharedKey account:signature"));
        assertEquals(Optional.empty(), TokenTenantHint.of("Bearer opaque-token"));
        assertEquals(Optional.empty(), TokenTenantHint.of("Bearer a.!!!.c"));
        assertEquals(Optional.empty(), TokenTenantHint.of(bearer("not json")));
        assertEquals(Optional.empty(), TokenTenantHint.of(bearer("{\"oid\":\"x\"}")));
        assertEquals(Optional.empty(), TokenTenantHint.of(bearer("{\"tid\":42}")));
        assertEquals(Optional.empty(), TokenTenantHint.of(bearer("{\"tid\":\" \"}")));
    }
}
