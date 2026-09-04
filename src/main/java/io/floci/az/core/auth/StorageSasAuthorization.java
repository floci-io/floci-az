package io.floci.az.core.auth;

import io.floci.az.core.AzureErrorResponse;
import io.floci.az.core.AzureRequest;
import io.floci.az.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

@ApplicationScoped
public class StorageSasAuthorization {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final UserDelegationKeyMaterial keyMaterial;
    private final java.util.Map<String, String> storageAccountKeys;

    @Inject
    public StorageSasAuthorization(UserDelegationKeyMaterial keyMaterial, EmulatorConfig config) {
        this.keyMaterial = keyMaterial;
        this.storageAccountKeys = java.util.Map.copyOf(config.auth().storageAccountKeys());
        for (String key : storageAccountKeys.values()) {
            if (Base64.getDecoder().decode(key).length == 0) {
                throw new IllegalArgumentException("Storage account SAS signing keys must not be empty");
            }
        }
    }

    public Optional<Response> authorizeRead(AzureRequest request, String container, String path, StorageSasToken token) {
        return authorize(request, container, path, token, Operation.READ);
    }

    public Optional<Response> authorizeList(AzureRequest request, String container, StorageSasToken token) {
        return authorize(request, container, null, token, Operation.LIST);
    }

    public Optional<Response> authorizeList(AzureRequest request, String container, String path, StorageSasToken token) {
        return authorize(request, container, path, token, Operation.LIST);
    }

    public Optional<Response> authorizeCreate(AzureRequest request, String container, String path, StorageSasToken token) {
        return authorize(request, container, path, token, Operation.CREATE);
    }

    public Optional<Response> authorizeWrite(AzureRequest request, String container, String path, StorageSasToken token) {
        return authorize(request, container, path, token, Operation.WRITE);
    }

    public Optional<Response> authorizeDelete(AzureRequest request, String container, String path, StorageSasToken token) {
        return authorize(request, container, path, token, Operation.DELETE);
    }

    public Optional<Response> authorizeAppend(AzureRequest request, String container, String path, StorageSasToken token) {
        return authorize(request, container, path, token, Operation.APPEND);
    }

    private Optional<Response> authorize(
            AzureRequest request,
            String container,
            String path,
            StorageSasToken token,
            Operation operation
    ) {
        if (token.resource() == null || token.permissions() == null || token.expiryTime() == null) {
            return Optional.of(authenticationFailed());
        }
        if (!isSupportedResource(token.resource()) || layoutFor(token.version()) == null
                || token.delegatedUserTenantId() != null || token.delegatedUserObjectId() != null) {
            return Optional.of(authenticationFailed());
        }
        if (token.isUserDelegation() && !delegationKeyValid(token)) {
            return Optional.of(authenticationFailed());
        }
        if (!token.isUserDelegation() && token.identifier() != null) {
            // Stored access policies need their own lookup and revocation semantics.
            return Optional.of(authenticationFailed());
        }
        if (!token.isUserDelegation() && "d".equals(token.resource())
                && (token.directoryDepth() == null
                    || LocalDate.parse(token.version()).isBefore(LocalDate.parse("2020-02-10")))) {
            return Optional.of(authenticationFailed());
        }
        if (!signatureMatches(request, container, path, token)) {
            return Optional.of(authenticationFailed());
        }
        if (!resourceCoversPath(request, token, path)) {
            return Optional.of(authorizationPermissionMismatch());
        }
        if (!operation.allowedBy(token)) {
            return Optional.of(authorizationPermissionMismatch());
        }
        return Optional.empty();
    }

    private static boolean delegationKeyValid(StorageSasToken token) {
        if (!UserDelegationKeyMaterial.SIGNED_OBJECT_ID.equals(token.signedObjectId())
                || !UserDelegationKeyMaterial.SIGNED_TENANT_ID.equals(token.signedTenantId())
                || !"b".equals(token.signedKeyService())
                || token.signedKeyVersion() == null) {
            return false;
        }

        Optional<Instant> keyStart = token.parsedSignedKeyStart().map(OffsetDateTime::toInstant);
        Optional<Instant> keyExpiry = token.parsedSignedKeyExpiry().map(OffsetDateTime::toInstant);
        Optional<Instant> sasExpiry = token.parsedExpiryTime().map(OffsetDateTime::toInstant);
        if (keyStart.isEmpty() || keyExpiry.isEmpty() || sasExpiry.isEmpty()) {
            return false;
        }

        Instant now = Instant.now();
        return !keyStart.get().isAfter(now)
                && keyExpiry.get().isAfter(now)
                && !sasExpiry.get().isAfter(keyExpiry.get());
    }

    private boolean signatureMatches(AzureRequest request, String container, String path, StorageSasToken token) {
        String canonicalName = canonicalName(request.accountName(), container, signedPath(token, path));
        String key = token.isUserDelegation()
                ? keyMaterial.signingKeyForAccount(request.accountName())
                : storageAccountKeys.get(request.accountName());
        if (key == null) {
            return false;
        }
        String signedFields = token.isUserDelegation()
                ? stringToSign(request, token, canonicalName)
                : serviceStringToSign(token, canonicalName);
        String expected = hmac(key, signedFields);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                token.signature().getBytes(StandardCharsets.UTF_8)
        );
    }

    // Service SAS has its own layout; delegation-key and agent fields are not included.
    private static String serviceStringToSign(StorageSasToken token, String canonicalName) {
        var fields = new java.util.ArrayList<>(Arrays.asList(
                value(token.permissions()), value(token.startTime()), value(token.expiryTime()),
                canonicalName, value(token.identifier()), value(token.ipRange()), value(token.protocol()),
                value(token.version()), value(token.resource()), value(token.snapshotTime())));
        if (!LocalDate.parse(token.version()).isBefore(LocalDate.parse("2020-12-06"))) {
            fields.add(value(token.encryptionScope()));
        }
        fields.addAll(Arrays.asList(value(token.cacheControl()), value(token.contentDisposition()),
                value(token.contentEncoding()), value(token.contentLanguage()), value(token.contentType())));
        return String.join("\n", fields);
    }

    private static String signedPath(StorageSasToken token, String requestPath) {
        if ("c".equals(token.resource())) {
            return null;
        }
        if ("d".equals(token.resource())) {
            return signedDirectoryPath(token, requestPath);
        }
        return requestPath;
    }

    private static String canonicalName(String account, String container, String path) {
        if (path == null || path.isBlank()) {
            return "/blob/" + account + "/" + container;
        }
        return "/blob/" + account + "/" + container + "/" + normalizePath(path);
    }

    private static String stringToSign(AzureRequest request, StorageSasToken token, String canonicalName) {
        SasLayout layout = layoutFor(token.version());
        if (layout == null) {
            throw new IllegalArgumentException("Unsupported SAS version");
        }
        var fields = new java.util.ArrayList<>(Arrays.asList(
                value(token.permissions()),
                value(token.startTime()),
                value(token.expiryTime()),
                canonicalName,
                value(token.signedObjectId()),
                value(token.signedTenantId()),
                value(token.signedKeyStart()),
                value(token.signedKeyExpiry()),
                value(token.signedKeyService()),
                value(token.signedKeyVersion())
        ));
        if (layout.includesAgentFields()) {
            fields.add(value(token.preauthorizedAgentObjectId()));
            fields.add(value(token.agentObjectId()));
            fields.add(value(token.correlationId()));
        }
        if (layout.includesDelegatedUser()) {
            fields.add(value(token.delegatedUserTenantId()));
            fields.add(value(token.delegatedUserObjectId()));
        }
        fields.addAll(Arrays.asList(
                value(token.ipRange()),
                value(token.protocol()),
                value(token.version()),
                value(token.resource())
        ));
        if (layout.includesSnapshot()) {
            fields.add(value(token.snapshotTime()));
        }
        if (layout.includesEncryptionScope()) {
            fields.add(value(token.encryptionScope()));
        }
        if (layout.includesRequestConstraints()) {
            fields.add(canonicalizedHeaders(request, token.signedRequestHeaders()));
            fields.add(canonicalizedQueryParameters(request, token.signedRequestQueryParameters()));
        }
        fields.addAll(Arrays.asList(
                value(token.cacheControl()),
                value(token.contentDisposition()),
                value(token.contentEncoding()),
                value(token.contentLanguage()),
                value(token.contentType())
        ));
        return String.join("\n", fields);
    }

    private static String canonicalizedHeaders(AzureRequest request, String names) {
        if (names == null) {
            return "";
        }
        var result = new StringBuilder();
        for (String name : names.split(",", -1)) {
            String header = name.trim().toLowerCase(java.util.Locale.ROOT);
            if (header.isEmpty()) {
                return "__invalid__";
            }
            String value = request.headers().getHeaderString(header);
            if (value == null) {
                return "__missing__";
            }
            result.append(header).append(':').append(value).append('\n');
        }
        return result.toString();
    }

    private static String canonicalizedQueryParameters(AzureRequest request, String names) {
        if (names == null) {
            return "";
        }
        var result = new StringBuilder();
        for (String name : names.split(",", -1)) {
            String parameter = name.trim();
            var values = request.queryParamsMulti().get(parameter);
            if (parameter.isEmpty() || values == null || values.isEmpty()) {
                return "__missing__";
            }
            result.append('\n').append(parameter).append('=').append(String.join(",", values));
        }
        return result.toString();
    }

    private static SasLayout layoutFor(String version) {
        try {
            LocalDate parsed = LocalDate.parse(version);
            if (parsed.isBefore(LocalDate.parse("2018-11-09"))) {
                return null;
            }
            if (!parsed.isBefore(LocalDate.parse("2026-04-06"))) {
                return SasLayout.REQUEST_CONSTRAINTS;
            }
            if (!parsed.isBefore(LocalDate.parse("2025-07-05"))) {
                return SasLayout.DELEGATED_USER;
            }
            if (!parsed.isBefore(LocalDate.parse("2020-12-06"))) {
                return SasLayout.ENCRYPTION_SCOPE;
            }
            if (!parsed.isBefore(LocalDate.parse("2020-02-10"))) {
                return SasLayout.SNAPSHOT;
            }
            return SasLayout.LEGACY;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String hmac(String base64Key, String stringToSign) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(base64Key), HMAC_SHA256));
            return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute SAS signature", e);
        }
    }

    private static boolean resourceCoversPath(AzureRequest request, StorageSasToken token, String path) {
        String normalizedPath = normalizePath(path);
        return switch (token.resource()) {
            case "c" -> true;
            case "b" -> normalizedPath != null && !normalizedPath.isBlank();
            case "d" -> signedDirectoryPath(token, normalizedPath) != null;
            case "bs" -> normalizedPath != null && !normalizedPath.isBlank()
                    && token.snapshotTime() != null
                    && token.snapshotTime().equals(request.queryParams().get("snapshot"));
            default -> false;
        };
    }

    private static String signedDirectoryPath(StorageSasToken token, String path) {
        String normalizedPath = normalizePath(path);
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return null;
        }
        if (token.directoryDepth() == null) {
            return normalizedPath;
        }
        try {
            int depth = Integer.parseInt(token.directoryDepth());
            if (depth <= 0) {
                return null;
            }
            String[] segments = normalizedPath.split("/");
            if (segments.length < depth) {
                return null;
            }
            return String.join("/", Arrays.copyOf(segments, depth));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isSupportedResource(String resource) {
        return "c".equals(resource) || "b".equals(resource) || "d".equals(resource) || "bs".equals(resource);
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static Response authenticationFailed() {
        return new AzureErrorResponse("AuthenticationFailed",
                "Server failed to authenticate the request. Make sure the value of Authorization header "
                        + "is formed correctly including the signature.")
                .toXmlResponse(Response.Status.FORBIDDEN.getStatusCode());
    }

    private static Response authorizationPermissionMismatch() {
        return new AzureErrorResponse("AuthorizationPermissionMismatch",
                "This request is not authorized to perform this operation using this permission.")
                .toXmlResponse(Response.Status.FORBIDDEN.getStatusCode());
    }

    private enum Operation {
        READ {
            @Override
            boolean allowedBy(StorageSasToken token) {
                return token.hasPermission('r');
            }
        },
        LIST {
            @Override
            boolean allowedBy(StorageSasToken token) {
                return token.hasPermission('l');
            }
        },
        CREATE {
            @Override
            boolean allowedBy(StorageSasToken token) {
                return token.hasAnyPermission('c', 'w');
            }
        },
        WRITE {
            @Override
            boolean allowedBy(StorageSasToken token) {
                return token.hasPermission('w');
            }
        },
        DELETE {
            @Override
            boolean allowedBy(StorageSasToken token) {
                return token.hasPermission('d');
            }
        },
        APPEND {
            @Override
            boolean allowedBy(StorageSasToken token) {
                return token.hasPermission('a');
            }
        };

        abstract boolean allowedBy(StorageSasToken token);
    }

    private enum SasLayout {
        LEGACY(false, false, true, false),
        SNAPSHOT(true, false, true, false),
        ENCRYPTION_SCOPE(true, false, true, true),
        DELEGATED_USER(true, true, true, true),
        REQUEST_CONSTRAINTS(true, true, true, true);

        private final boolean agentFields;
        private final boolean delegatedUser;
        private final boolean snapshot;
        private final boolean encryptionScope;

        SasLayout(boolean agentFields, boolean delegatedUser, boolean snapshot, boolean encryptionScope) {
            this.agentFields = agentFields;
            this.delegatedUser = delegatedUser;
            this.snapshot = snapshot;
            this.encryptionScope = encryptionScope;
        }

        boolean includesAgentFields() { return agentFields; }
        boolean includesDelegatedUser() { return delegatedUser; }
        boolean includesSnapshot() { return snapshot; }
        boolean includesEncryptionScope() { return encryptionScope; }
        boolean includesRequestConstraints() { return this == REQUEST_CONSTRAINTS; }
    }
}
