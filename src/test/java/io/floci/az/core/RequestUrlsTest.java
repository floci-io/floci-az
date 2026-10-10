package io.floci.az.core;

import io.floci.az.config.EmulatorConfig;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestUrlsTest {

    private static final String CONFIGURED = "http://configured.example:4577";

    @Test
    void hostHeaderAloneBuildsTheBaseUrl() {
        AzureRequest request = request(headers(Map.of("Host", "localhost:4577")), false, null);

        assertEquals("http://localhost:4577", RequestUrls.resolveBaseUrl(request, config()));
    }

    @Test
    void authorityAloneBuildsTheBaseUrl() {
        AzureRequest request = request(headers(Map.of()), true, "floci.test:4577");

        assertEquals("https://floci.test:4577", RequestUrls.resolveBaseUrl(request, config()));
    }

    @Test
    void authorityWinsOverTheHostHeader() {
        AzureRequest request = request(headers(Map.of("Host", "stale:1")), false, "floci.test:4577");

        assertEquals("http://floci.test:4577", RequestUrls.resolveBaseUrl(request, config()));
    }

    @Test
    void forwardedProtoSetsTheSchemeOfAnAuthorityUrl() {
        AzureRequest request = request(headers(Map.of("X-Forwarded-Proto", "https, http")), false,
                "floci.test:4577");

        assertEquals("https://floci.test:4577", RequestUrls.resolveBaseUrl(request, config()));
    }

    @Test
    void noAuthorityFallsBackToTheConfiguredBaseUrl() {
        AzureRequest request = request(headers(Map.of()), true, null);

        assertEquals(CONFIGURED, RequestUrls.resolveBaseUrl(request, config()));
        assertNull(RequestUrls.resolveAuthority(request));
    }

    @Test
    void http1AuthorityIsTheHostHeaderVerbatim() {
        HttpServerRequest serverRequest = mock(HttpServerRequest.class);
        when(serverRequest.getHeader("Host")).thenReturn("LocalHost:04577");

        assertEquals("LocalHost:04577", AzureRoutingFilter.requestAuthority(serverRequest));
    }

    @Test
    void http2AuthorityKeepsThePort() {
        HttpServerRequest serverRequest = mock(HttpServerRequest.class);
        when(serverRequest.authority()).thenReturn(HostAndPort.create("floci.test", 4577));

        assertEquals("floci.test:4577", AzureRoutingFilter.requestAuthority(serverRequest));
    }

    @Test
    void http2AuthorityWithoutAPortHasNoPort() {
        HttpServerRequest serverRequest = mock(HttpServerRequest.class);
        when(serverRequest.authority()).thenReturn(HostAndPort.create("floci.test", -1));

        assertEquals("floci.test", AzureRoutingFilter.requestAuthority(serverRequest));
    }

    private static AzureRequest request(HttpHeaders headers, boolean secure, String authority) {
        return new AzureRequest("GET", "entra", "entra", "", headers, null, Map.of(), Map.of(), null,
                secure, null, null, "", null, authority);
    }

    private static HttpHeaders headers(Map<String, String> values) {
        HttpHeaders headers = mock(HttpHeaders.class);
        values.forEach((name, value) -> when(headers.getHeaderString(name)).thenReturn(value));
        return headers;
    }

    private static EmulatorConfig config() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.effectiveBaseUrl()).thenReturn(CONFIGURED);
        return config;
    }
}
