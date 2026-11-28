package io.floci.az.services.apim;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiManagementProxyTest {

    @Test
    void headersTheBackendConnectionNamesAreNotForwarded() {
        Map<String, List<String>> backend = new LinkedHashMap<>();
        backend.put("Connection", List.of("keep-alive, X-Backend-Hop"));
        backend.put("X-Backend-Hop", List.of("hop"));
        backend.put("Transfer-Encoding", List.of("chunked"));
        backend.put("X-Kept", List.of("kept"));
        backend.put(":status", List.of("200"));

        Map<String, List<String>> forwarded = ApiManagementProxy.forwardedResponseHeaders(backend);

        assertEquals(Map.of("X-Kept", List.of("kept")), Map.copyOf(forwarded));
        assertTrue(forwarded.containsKey("x-kept"), "the forwarded headers are keyed case-insensitively");
    }

    @Test
    void appendAddsEveryValueAfterTheBackendOnes() {
        Map<String, List<String>> headers = ApiManagementProxy.forwardedResponseHeaders(
                Map.of("Set-Cookie", List.of("backend=0")));

        new ApiManagementProxy.ResponseHeaderAction("set-cookie", "append", List.of("a=1", "b=2")).applyTo(headers);

        assertEquals(List.of("backend=0", "a=1", "b=2"), headers.get("Set-Cookie"));
    }
}
