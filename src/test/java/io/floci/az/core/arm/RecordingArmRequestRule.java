package io.floci.az.core.arm;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * A test-only {@link ArmRequestRule} that records every call the gate sees. It exists only in an application
 * built with {@value #ENABLED_BY} set to {@code true}, which a test profile sets; every other test runs with the
 * production gate alone. It is not an {@code @Alternative}: selecting one would drop the production rules from
 * the gate's {@code @All} injection. Its order, 0, puts it ahead of every production rule, so it records each
 * request before a production rule can answer it.
 */
@IfBuildProperty(name = RecordingArmRequestRule.ENABLED_BY, stringValue = "true")
@ApplicationScoped
public class RecordingArmRequestRule implements ArmRequestRule {

    /** The build property that adds this rule to the application. */
    public static final String ENABLED_BY = "floci-az-test.recording-arm-request-rule";

    /** One gate passage: the call built on entry, and the status seen on exit (null until exit, or when the handler threw). */
    public record Passage(ArmCall call, Integer exitStatus) {}

    private final List<ArmCall> entered = new ArrayList<>();
    private final List<Passage> exited = new ArrayList<>();

    @Override
    public int order() {
        return 0;
    }

    @Override
    public synchronized ArmVerdict enter(ArmCall call) {
        entered.add(call);
        return ArmVerdict.proceed();
    }

    @Override
    public synchronized void exit(ArmCall call, Response response) {
        exited.add(new Passage(call, response == null ? null : response.getStatus()));
    }

    public synchronized List<ArmCall> entered() {
        return List.copyOf(entered);
    }

    public synchronized List<Passage> exited() {
        return List.copyOf(exited);
    }

    public synchronized void clear() {
        entered.clear();
        exited.clear();
    }
}
