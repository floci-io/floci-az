package io.floci.az.core.auth;

import io.floci.az.core.AzureErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

@ApplicationScoped
public class StorageSasAuthorization {

    private static final String HMAC_SHA256 = "HmacSHA256";

    public Optional<Response> authorizeRead(String account, String container, String path, StorageSasToken token) {
        return authorize(account, container, path, token, Operation.READ);
    }

    public Optional<Response> authorizeList(String account, String container, StorageSasToken token) {
        return authorize(account, container, null, token, Operation.LIST);
    }

    public Optional<Response> authorizeCreate(String account, String container, String path, StorageSasToken token) {
        return authorize(account, container, path, token, Operation.CREATE);
    }

    public Optional<Response> authorizeWrite(String account, String container, String path, StorageSasToken token) {
        return authorize(account, container, path, token, Operation.WRITE);
    }

    public Optional<Response> authorizeDelete(String account, String container, String path, StorageSasToken token) {
        return authorize(account, container, path, token, Operation.DELETE);
    }

    private Optional<Response> authorize(
            String account,
            String container,
            String path,
            StorageSasToken token,
            Operation operation
    ) {
        if (token.resource() == null || token.permissions() == null || token.expiryTime() == null) {
            return Optional.of(authenticationFailed());
        }
        if (!isSupportedResource(token.resource())) {
            return Optional.of(authenticationFailed());
        }
        if (!delegationKeyValid(token)) {
            return Optional.of(authenticationFailed());
        }
        if (!signatureMatches(account, container, path, token)) {
            return Optional.of(authenticationFailed());
        }
        if (!resourceCoversPath(token, path)) {
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

    private boolean signatureMatches(String account, String container, String path, StorageSasToken token) {
        String canonicalName = canonicalName(account, container, signedPath(token, path));
        String expected = hmac(UserDelegationKeyMaterial.signingKeyForAccount(account), stringToSign(token, canonicalName));
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                token.signature().getBytes(StandardCharsets.UTF_8)
        );
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

    private static String stringToSign(StorageSasToken token, String canonicalName) {
        return String.join("\n",
                value(token.permissions()),
                value(token.startTime()),
                value(token.expiryTime()),
                canonicalName,
                value(token.signedObjectId()),
                value(token.signedTenantId()),
                value(token.signedKeyStart()),
                value(token.signedKeyExpiry()),
                value(token.signedKeyService()),
                value(token.signedKeyVersion()),
                value(token.preauthorizedAgentObjectId()),
                value(token.agentObjectId()),
                value(token.correlationId()),
                value(token.ipRange()),
                value(token.protocol()),
                value(token.version()),
                value(token.resource()),
                "",
                value(token.encryptionScope()),
                value(token.cacheControl()),
                value(token.contentDisposition()),
                value(token.contentEncoding()),
                value(token.contentLanguage()),
                value(token.contentType())
        );
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

    private static boolean resourceCoversPath(StorageSasToken token, String path) {
        String normalizedPath = normalizePath(path);
        return switch (token.resource()) {
            case "c" -> true;
            case "b" -> normalizedPath != null && !normalizedPath.isBlank();
            case "d" -> signedDirectoryPath(token, normalizedPath) != null;
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
        return "c".equals(resource) || "b".equals(resource) || "d".equals(resource);
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
                return token.hasAnyPermission('c', 'a', 'w');
            }
        },
        WRITE {
            @Override
            boolean allowedBy(StorageSasToken token) {
                return token.hasAnyPermission('a', 'w');
            }
        },
        DELETE {
            @Override
            boolean allowedBy(StorageSasToken token) {
                return token.hasPermission('d');
            }
        };

        abstract boolean allowedBy(StorageSasToken token);
    }
}
