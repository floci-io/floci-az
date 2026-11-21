package io.floci.az.services.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The HTTP calls floci-az makes to its Solr sidecar: the Configsets, Collections, Schema, update and select APIs. It
 * holds no state; every call names the sidecar's base URL. Solr runs in SolrCloud mode (the image's default), so an
 * index is a collection with a configset of its own.
 */
@ApplicationScoped
public class SearchSolrClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final String JSON = "application/json";
    static final int UPDATE_BATCH = 500;

    /** Solr answered with an error status. */
    static final class SolrError extends RuntimeException {
        private final int status;

        SolrError(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    /** Solr did not answer at all: the sidecar is gone or not reachable. */
    static final class SolrUnreachable extends RuntimeException {
        SolrUnreachable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** Whether Solr answers its system information, which it does once it can serve requests. */
    public boolean ready(URI base) {
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/solr/admin/info/system?wt=json"))
                .timeout(PROBE_TIMEOUT).GET().build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Uploads a zipped configset, replacing one of the same name. */
    public void uploadConfigSet(URI base, String name, byte[] zip) {
        send(HttpRequest.newBuilder(base.resolve("/solr/admin/configs?action=UPLOAD&overwrite=true&wt=json&name="
                        + encode(name)))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(zip)));
    }

    /** Creates a collection of one shard and one replica, with a configset of its own copied from {@code base}. */
    public void createCollection(URI base, String name, String baseConfigSet) {
        get(base, "/solr/admin/configs?action=CREATE&wt=json&name=" + encode(name) + "&baseConfigSet="
                + encode(baseConfigSet));
        get(base, "/solr/admin/collections?action=CREATE&wt=json&numShards=1&replicationFactor=1"
                + "&waitForFinalState=true&name=" + encode(name) + "&collection.configName=" + encode(name));
    }

    /** Deletes a collection and its configset; one that does not exist is no error. */
    public void deleteCollection(URI base, String name) {
        try {
            get(base, "/solr/admin/collections?action=DELETE&wt=json&name=" + encode(name));
        } catch (SolrError e) {
            if (e.status() != 400 && e.status() != 404) {
                throw e;
            }
        }
        try {
            get(base, "/solr/admin/configs?action=DELETE&wt=json&name=" + encode(name));
        } catch (SolrError e) {
            if (e.status() != 400 && e.status() != 404) {
                throw e;
            }
        }
    }

    /** Runs Schema API commands, such as {@code add-field-type} and {@code add-field}, on a collection. */
    public void schema(URI base, String collection, Map<String, Object> commands) {
        post(base, "/solr/" + encode(collection) + "/schema?wt=json", commands);
    }

    /**
     * Deletes and adds documents, in requests of at most {@value #UPDATE_BATCH} each, then commits softly once, so a
     * query that follows sees them. Nothing is sent when there is nothing to change.
     */
    public void update(URI base, String collection, List<Map<String, Object>> adds, Collection<String> deletes) {
        if (adds.isEmpty() && deletes.isEmpty()) {
            return;
        }
        String path = "/solr/" + encode(collection) + "/update?wt=json";
        List<String> ids = List.copyOf(deletes);
        for (int from = 0; from < ids.size(); from += UPDATE_BATCH) {
            post(base, path, Map.of("delete", ids.subList(from, Math.min(ids.size(), from + UPDATE_BATCH))));
        }
        for (int from = 0; from < adds.size(); from += UPDATE_BATCH) {
            post(base, path, adds.subList(from, Math.min(adds.size(), from + UPDATE_BATCH)));
        }
        post(base, path + "&softCommit=true&waitSearcher=true", List.of());
    }

    /** Runs a query against a collection's {@code /select} handler and answers Solr's JSON. */
    public JsonNode select(URI base, String collection, List<Map.Entry<String, String>> params) {
        List<String> pairs = new ArrayList<>();
        pairs.add("wt=json");
        for (Map.Entry<String, String> param : params) {
            pairs.add(encode(param.getKey()) + "=" + encode(param.getValue()));
        }
        return send(HttpRequest.newBuilder(base.resolve("/solr/" + encode(collection) + "/select"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(String.join("&", pairs), StandardCharsets.UTF_8)));
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private JsonNode get(URI base, String pathAndQuery) {
        return send(HttpRequest.newBuilder(base.resolve(pathAndQuery)).GET());
    }

    private JsonNode post(URI base, String pathAndQuery, Object body) {
        byte[] json;
        try {
            json = MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A Solr request could not be written as JSON", e);
        }
        return send(HttpRequest.newBuilder(base.resolve(pathAndQuery))
                .header("Content-Type", JSON)
                .POST(HttpRequest.BodyPublishers.ofByteArray(json)));
    }

    private JsonNode send(HttpRequest.Builder builder) {
        HttpRequest request = builder.timeout(REQUEST_TIMEOUT).build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpConnectTimeoutException e) {
            throw new SolrUnreachable("Solr did not accept a connection for " + request.uri().getPath(), e);
        } catch (HttpTimeoutException e) {
            // Slow, not gone: the container and what was built in it stay.
            throw new SolrError(504, "Solr did not answer " + request.method() + " " + request.uri().getPath()
                    + " within " + REQUEST_TIMEOUT.toSeconds() + " s");
        } catch (IOException e) {
            throw new SolrUnreachable("Solr did not answer " + request.method() + " " + request.uri().getPath(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SolrUnreachable("Interrupted while waiting for Solr", e);
        }
        JsonNode body = parse(response.body());
        if (response.statusCode() / 100 != 2) {
            throw new SolrError(response.statusCode(), message(body, response.statusCode()));
        }
        return body;
    }

    private static JsonNode parse(byte[] bytes) {
        try {
            return bytes.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(bytes);
        } catch (IOException e) {
            // Jetty answers some errors (an unknown collection) as HTML.
            return MAPPER.createObjectNode().put("raw", new String(bytes, StandardCharsets.UTF_8));
        }
    }

    /** Solr's own words for an error, with the details the Schema API gives. */
    private static String message(JsonNode body, int status) {
        JsonNode error = body.path("error");
        StringBuilder message = new StringBuilder(error.path("msg").asText("Solr answered " + status));
        for (JsonNode detail : error.path("details")) {
            for (JsonNode text : detail.path("errorMessages")) {
                message.append(' ').append(text.asText().trim());
            }
        }
        return message.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
