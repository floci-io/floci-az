package io.floci.az.services.signalr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

final class SignalRTokens {
    private static final ObjectMapper JSON = new ObjectMapper();
    record Claims(List<Map.Entry<String, String>> values) {
        String userId() {
            return value("asrs.s.uid");
        }
        String value(String name) {
            return values.stream().filter(value -> value.getKey().equals(name))
                    .map(Map.Entry::getValue).findFirst().orElse("");
        }
    }

    static Claims verify(String token, String key, String audience) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) { throw new IllegalArgumentException("Invalid token"); }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            Map<String, Object> header = JSON.readValue(decoder.decode(parts[0]), new TypeReference<>() {});
            if (!"HS256".equals(header.get("alg"))) { throw new IllegalArgumentException("Unsupported token algorithm"); }
            Mac mac = Mac.getInstance("HmacSHA256");
            // The Azure SDK signs with the UTF-8 AccessKey string, not its Base64-decoded bytes.
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (!MessageDigest.isEqual(mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8)), decoder.decode(parts[2]))) {
                throw new IllegalArgumentException("Invalid token signature");
            }
            Map<String, Object> body = JSON.readValue(decoder.decode(parts[1]), new TypeReference<>() {});
            long now = Instant.now().getEpochSecond();
            if (!(body.get("exp") instanceof Number expiry) || expiry.longValue() <= now
                    || (body.get("nbf") instanceof Number start && start.longValue() > now)
                    || !audience.equalsIgnoreCase(String.valueOf(body.get("aud")))) {
                throw new IllegalArgumentException("Invalid token audience or lifetime");
            }
            List<Map.Entry<String, String>> claims = new ArrayList<>();
            body.forEach((name, value) -> {
                if (value instanceof List<?> list) {
                    list.forEach(item -> claims.add(Map.entry(name, String.valueOf(item))));
                } else { claims.add(Map.entry(name, String.valueOf(value))); }
            });
            return new Claims(List.copyOf(claims));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid SignalR access token", e);
        }
    }
}
