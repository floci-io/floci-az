package io.floci.az.core.auth;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageSasAuthorizationTest {

    private static final String DEFAULT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
    private static final String CUSTOM_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void unconfiguredAccountUsesDevelopmentKey() throws Exception {
        assertTrue(authorize("unconfigured", DEFAULT_KEY).isEmpty());
        try (Response response = authorize("unconfigured", CUSTOM_KEY).orElseThrow()) {
            assertEquals(403, response.getStatus());
            assertEquals("AuthenticationFailed", response.getHeaderString("x-ms-error-code"));
        }
    }

    @Test
    void configuredKeyTakesPrecedenceOverDevelopmentKey() throws Exception {
        assertTrue(authorize("customaccount", CUSTOM_KEY).isEmpty());
        try (Response response = authorize("customaccount", DEFAULT_KEY).orElseThrow()) {
            assertEquals(403, response.getStatus());
            assertEquals("AuthenticationFailed", response.getHeaderString("x-ms-error-code"));
        }
    }

    private Optional<Response> authorize(String account, String key) throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.auth().storageAccountKeys()).thenReturn(Map.of("customaccount", CUSTOM_KEY));
        StorageSasAuthorization authorization = new StorageSasAuthorization(new UserDelegationKeyMaterial(), config);
        String expiry = Instant.now().plusSeconds(3600).toString();
        String fields = "r\n\n" + expiry + "\n/blob/" + account + "/container/file\n\n\n\n2020-12-06\nb\n\n\n\n\n\n\n";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(key), "HmacSHA256"));
        String signature = Base64.getEncoder().encodeToString(mac.doFinal(fields.getBytes(StandardCharsets.UTF_8)));
        Map<String, String> query = Map.of("sv", "2020-12-06", "sp", "r", "sr", "b", "se", expiry, "sig", signature);
        AzureRequest request = new AzureRequest("GET", account, "blob", "container/file", null, null, query, null, false);
        return authorization.authorizeRead(request, "container", "file", StorageSasToken.from(query).orElseThrow());
    }
}
