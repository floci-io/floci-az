package io.floci.az.services.policy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One policy resource (a definition, set definition, assignment or exemption) as {@link PolicyStore} keeps it: the
 * ARM body the handler answers with, plus the internal {@code _scope} key the listings filter on. It serializes as
 * that body, so a stored entry reads as the resource itself.
 *
 * @param body the resource body; its {@code id} is the resource's ARM id
 */
@RegisterForReflection
public record PolicyResource(@JsonValue Map<String, Object> body) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public PolicyResource {
        body = new LinkedHashMap<>(body);
    }

    /** The resource's ARM id. */
    public String id() {
        return String.valueOf(body.get("id"));
    }
}
