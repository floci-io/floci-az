package io.floci.az.core;

import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The headers of a request floci-az makes to itself rather than receives over the transport. Header names
 * match case-insensitively, as they do on the wire. The underlying map is {@link #map() mutable}, because
 * routing stamps one header of its own on a request.
 */
public final class SyntheticHttpHeaders implements HttpHeaders {

    private final MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();

    public SyntheticHttpHeaders(Map<String, String> initial) {
        initial.forEach((name, value) -> {
            if (value != null) {
                headers.putSingle(name, value);
            }
        });
    }

    /** The mutable header map behind these headers. */
    MultivaluedMap<String, String> map() {
        return headers;
    }

    @Override
    public List<String> getRequestHeader(String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Override
    public String getHeaderString(String name) {
        List<String> values = getRequestHeader(name);
        return values == null || values.isEmpty() ? null : String.join(",", values);
    }

    @Override
    public MultivaluedMap<String, String> getRequestHeaders() {
        return headers;
    }

    @Override
    public List<MediaType> getAcceptableMediaTypes() {
        return List.of(MediaType.WILDCARD_TYPE);
    }

    @Override
    public List<Locale> getAcceptableLanguages() {
        return List.of();
    }

    @Override
    public MediaType getMediaType() {
        String contentType = getHeaderString(CONTENT_TYPE);
        return contentType == null ? null : MediaType.valueOf(contentType);
    }

    @Override
    public Locale getLanguage() {
        return null;
    }

    @Override
    public Map<String, Cookie> getCookies() {
        return Map.of();
    }

    @Override
    public Date getDate() {
        return null;
    }

    @Override
    public int getLength() {
        return -1;
    }
}
