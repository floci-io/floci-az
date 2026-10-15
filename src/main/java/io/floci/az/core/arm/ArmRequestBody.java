package io.floci.az.core.arm;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * The buffered body of a write request inside the {@link ArmRequestGate}, read once so that a rule can
 * inspect or amend it before the handler reads it again. {@link #json()} parses lazily and answers null
 * for a body that is not a JSON object, which the handler then rejects itself.
 */
public final class ArmRequestBody {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private byte[] bytes;
    private Map<String, Object> parsed;
    private boolean parseAttempted;

    private ArmRequestBody(byte[] bytes) {
        this.bytes = bytes;
    }

    /** Drains {@code stream} (null stands for an empty body) into a new body. */
    public static ArmRequestBody read(InputStream stream) {
        try {
            return new ArmRequestBody(stream == null ? new byte[0] : stream.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A body holding {@code content} serialised as JSON. */
    public static ArmRequestBody of(Map<String, Object> content) {
        ArmRequestBody body = new ArmRequestBody(new byte[0]);
        body.replace(content);
        return body;
    }

    public byte[] bytes() {
        return bytes;
    }

    public boolean isEmpty() {
        return bytes.length == 0;
    }

    /** The body as a JSON object, or null when it is empty or not a JSON object. */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> json() {
        if (!parseAttempted) {
            parseAttempted = true;
            if (bytes.length > 0) {
                try {
                    Object value = MAPPER.readValue(bytes, Object.class);
                    parsed = value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
                } catch (IOException e) {
                    parsed = null;
                }
            }
        }
        return parsed;
    }

    /** Replaces the body with {@code content}, which the handler then reads instead of the original. */
    public synchronized void replace(Map<String, Object> content) {
        try {
            bytes = MAPPER.writeValueAsBytes(content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        parsed = content;
        parseAttempted = true;
    }

    /** A fresh stream over the current bytes. */
    public InputStream stream() {
        return new ByteArrayInputStream(bytes);
    }
}
