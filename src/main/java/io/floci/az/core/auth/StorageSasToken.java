package io.floci.az.core.auth;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

public record StorageSasToken(
        String version,
        String signature,
        String permissions,
        String resource,
        String startTime,
        String expiryTime,
        String signedObjectId,
        String signedTenantId,
        String signedKeyStart,
        String signedKeyExpiry,
        String signedKeyService,
        String signedKeyVersion,
        String protocol,
        String ipRange,
        String identifier,
        String preauthorizedAgentObjectId,
        String agentObjectId,
        String correlationId,
        String directoryDepth,
        String cacheControl,
        String contentDisposition,
        String contentEncoding,
        String contentLanguage,
        String contentType,
        String encryptionScope,
        String snapshotTime,
        String delegatedUserTenantId,
        String delegatedUserObjectId,
        String signedRequestHeaders,
        String signedRequestQueryParameters,
        String services,
        String resourceTypes
) {

    public static Optional<StorageSasToken> from(Map<String, String> query) {
        String signature = blankToNull(query.get("sig"));
        String version = blankToNull(query.get("sv"));
        if (signature == null || version == null) {
            return Optional.empty();
        }
        return Optional.of(new StorageSasToken(
                version,
                signature,
                blankToNull(query.get("sp")),
                blankToNull(query.get("sr")),
                blankToNull(query.get("st")),
                blankToNull(query.get("se")),
                blankToNull(query.get("skoid")),
                blankToNull(query.get("sktid")),
                blankToNull(query.get("skt")),
                blankToNull(query.get("ske")),
                blankToNull(query.get("sks")),
                blankToNull(query.get("skv")),
                blankToNull(query.get("spr")),
                blankToNull(query.get("sip")),
                blankToNull(query.get("si")),
                blankToNull(query.get("saoid")),
                blankToNull(query.get("suoid")),
                blankToNull(query.get("scid")),
                blankToNull(query.get("sdd")),
                blankToNull(query.get("rscc")),
                blankToNull(query.get("rscd")),
                blankToNull(query.get("rsce")),
                blankToNull(query.get("rscl")),
                blankToNull(query.get("rsct")),
                blankToNull(query.get("ses")),
                blankToNull(query.get("snapshot")),
                blankToNull(query.get("skdutid")),
                blankToNull(query.get("sduoid")),
                blankToNull(query.get("srh")),
                blankToNull(query.get("srq")),
                blankToNull(query.get("ss")),
                blankToNull(query.get("srt"))
        ));
    }

    public Optional<OffsetDateTime> parsedStartTime() {
        return parseDate(startTime);
    }

    public Optional<OffsetDateTime> parsedExpiryTime() {
        return parseDate(expiryTime);
    }

    public Optional<OffsetDateTime> parsedSignedKeyStart() {
        return parseDate(signedKeyStart);
    }

    public Optional<OffsetDateTime> parsedSignedKeyExpiry() {
        return parseDate(signedKeyExpiry);
    }

    public boolean hasPermission(char permission) {
        return permissions != null && permissions.indexOf(permission) >= 0;
    }

    public boolean isUserDelegation() {
        return signedObjectId != null || signedTenantId != null || signedKeyStart != null
                || signedKeyExpiry != null || signedKeyService != null || signedKeyVersion != null;
    }

    /** An account SAS: it names the services ({@code ss}) and resource types ({@code srt}) it grants. */
    public boolean isAccountSas() {
        return services != null && resourceTypes != null;
    }

    /**
     * The string an account SAS signs (Create an account SAS): version 2020-12-06 added the signed encryption scope,
     * and every field ends with a newline.
     */
    public String accountStringToSign(String account) {
        StringBuilder fields = new StringBuilder();
        for (String field : new String[] {account, permissions, services, resourceTypes, startTime, expiryTime,
                ipRange, protocol, version}) {
            fields.append(field == null ? "" : field).append('\n');
        }
        if (version.compareTo("2020-12-06") >= 0) {
            fields.append(encryptionScope == null ? "" : encryptionScope).append('\n');
        }
        return fields.toString();
    }

    public boolean hasAnyPermission(char... candidates) {
        for (char candidate : candidates) {
            if (hasPermission(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<OffsetDateTime> parseDate(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(value));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
