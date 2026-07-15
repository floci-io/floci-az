package io.floci.az.core.auth;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageSasTokenTest {

    @Test
    void parsesUserDelegationFields() {
        StorageSasToken token = StorageSasToken.from(Map.ofEntries(
                Map.entry("sv", "2024-11-04"),
                Map.entry("sig", "abc+/="),
                Map.entry("sp", "racwdl"),
                Map.entry("sr", "b"),
                Map.entry("st", "2026-07-15T10:00:00Z"),
                Map.entry("se", "2026-07-15T11:00:00Z"),
                Map.entry("skoid", UserDelegationKeyMaterial.SIGNED_OBJECT_ID),
                Map.entry("sktid", UserDelegationKeyMaterial.SIGNED_TENANT_ID),
                Map.entry("skt", "2026-07-15T10:00:00Z"),
                Map.entry("ske", "2026-07-15T11:00:00Z"),
                Map.entry("sks", "b"),
                Map.entry("skv", "2024-11-04"),
                Map.entry("sdd", "2"),
                Map.entry("ses", "scope")
        )).orElseThrow();

        assertThat(token.version(), equalTo("2024-11-04"));
        assertThat(token.signature(), equalTo("abc+/="));
        assertThat(token.permissions(), equalTo("racwdl"));
        assertThat(token.resource(), equalTo("b"));
        assertThat(token.signedObjectId(), equalTo(UserDelegationKeyMaterial.SIGNED_OBJECT_ID));
        assertThat(token.directoryDepth(), equalTo("2"));
        assertThat(token.encryptionScope(), equalTo("scope"));
        assertTrue(token.parsedStartTime().isPresent());
        assertTrue(token.parsedExpiryTime().isPresent());
        assertTrue(token.parsedSignedKeyStart().isPresent());
        assertTrue(token.parsedSignedKeyExpiry().isPresent());
    }

    @Test
    void ignoresQueriesWithoutSasMarkerFields() {
        assertTrue(StorageSasToken.from(Map.of("sv", "2024-11-04")).isEmpty());
        assertTrue(StorageSasToken.from(Map.of("sig", "abc")).isEmpty());
    }

    @Test
    void exposesMalformedDatesAsEmptyParsedValues() {
        StorageSasToken token = StorageSasToken.from(Map.of(
                "sv", "2024-11-04",
                "sig", "abc",
                "st", "not-a-date",
                "se", "also-not-a-date"
        )).orElseThrow();

        assertTrue(token.parsedStartTime().isEmpty());
        assertTrue(token.parsedExpiryTime().isEmpty());
    }
}
