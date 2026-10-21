package io.floci.az.core.arm;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Objects;

/**
 * The second pass of {@link LocationRule}, after policy evaluation. {@link LocationRule} runs before policy, so policy
 * conditions see the canonical location; a policy {@code append} can then add or replace the body's
 * {@code location}, and that value would otherwise reach the handler unchecked. When the body's location is no
 * longer the one the first pass settled ({@link ArmCall#normalizedLocation()}), this rule runs
 * {@link LocationRule}'s check again, so an appended location is normalized under {@code location-normalize} and
 * refused under {@code strict-locations} like one the client sent.
 */
@ApplicationScoped
public class AppendedLocationRule implements ArmRequestRule {

    /** Position in the gate: right after policy evaluation (order 40), which is what may append a location. */
    public static final int ORDER = 45;

    private final LocationRule location;

    @Inject
    public AppendedLocationRule(LocationRule location) {
        this.location = location;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public boolean readsBody() {
        return location.readsBody();
    }

    @Override
    public ArmVerdict enter(ArmCall call) {
        if (!location.readsBody() || call.body() == null) {
            return ArmVerdict.proceed();
        }
        Map<String, Object> body = call.body().json();
        Object sent = body == null ? null : body.get("location");
        if (!(sent instanceof String) || Objects.equals(sent, call.normalizedLocation())) {
            return ArmVerdict.proceed();
        }
        return location.enter(call);
    }
}
