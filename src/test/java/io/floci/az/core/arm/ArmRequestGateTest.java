package io.floci.az.core.arm;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.ScopeContext;
import io.floci.az.core.cloud.ScopeSource;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ArmRequestGate: entry, exit and pass-through")
class ArmRequestGateTest {

    private static final ScopeContext SCOPE = new ScopeContext(CloudCatalog.forNameOrPublic(null), "t", "s", "rg",
        null, ScopeSource.ARM_PATH);

    private static AzureRequest request(String method, String path) {
        return new AzureRequest(method, "arm", "arm", path, null, null, Map.of(), Map.of(), null, false,
            null, null, path, null).withScope(SCOPE);
    }

    /** Records the order of every hook call into a shared log. */
    private record LoggingRule(int order, List<String> log) implements ArmRequestRule {
        @Override
        public void enter(ArmCall call) {
            log.add("enter" + order + " " + call.method() + " " + call.id());
        }

        @Override
        public void exit(ArmCall call, Response response) {
            log.add("exit" + order + " " + (response == null ? "threw" : response.getStatus()));
        }
    }

    @Test
    void anEmptyGateHandsTheHandlersResponseBackUnchanged() {
        ArmRequestGate gate = new ArmRequestGate(List.of());
        Response response = Response.status(202).header("Location", "/op").build();

        Response served = gate.serve(request("PUT", "subscriptions/s/resourceGroups/rg"), req -> response);

        assertSame(response, served);
        assertTrue(gate.rules().isEmpty());
    }

    @Test
    void theHandlerSeesTheRequestInsideTheGate() {
        ArmRequestGate gate = new ArmRequestGate(List.of());
        AtomicReference<AzureRequest> seen = new AtomicReference<>();
        AzureRequest original = request("get", "subscriptions/s/resourceGroups/rg/providers/Microsoft.Cache/redis/c1?api-version=1");

        gate.serve(original, req -> {
            seen.set(req);
            return Response.ok().build();
        });

        ArmCall call = seen.get().armCall();
        assertNotNull(call);
        assertSame(SCOPE, call.scope());
        assertEquals("GET", call.method());
        assertEquals("/subscriptions/s/resourceGroups/rg/providers/Microsoft.Cache/redis/c1", call.id().id());
        assertNull(call.type());
        assertNull(call.normalizedLocation());
        assertSame(SCOPE, seen.get().scope());
        assertEquals(original.resourcePath(), seen.get().resourcePath());
        assertNull(original.armCall(), "the caller's request is not mutated");
    }

    @Test
    void rulesRunInOrderOnEntryAndExit() {
        List<String> log = new ArrayList<>();
        ArmRequestGate gate = new ArmRequestGate(List.of(new LoggingRule(20, log), new LoggingRule(10, log)));

        gate.serve(request("DELETE", "subscriptions/s"), req -> {
            log.add("handler");
            return Response.status(204).build();
        });

        assertEquals(List.of("enter10 DELETE /subscriptions/s", "enter20 DELETE /subscriptions/s", "handler",
            "exit10 204", "exit20 204"), log);
    }

    @Test
    void aRequestAlreadyInsideTheGateIsNotEnteredTwice() {
        List<String> log = new ArrayList<>();
        ArmRequestGate gate = new ArmRequestGate(List.of(new LoggingRule(1, log)));

        gate.serve(request("GET", "subscriptions/s"), outer -> gate.serve(outer, inner -> {
            assertSame(outer, inner);
            return Response.ok().build();
        }));

        assertEquals(List.of("enter1 GET /subscriptions/s", "exit1 200"), log);
    }

    @Test
    void exitRunsWhenTheHandlerThrows() {
        List<String> log = new ArrayList<>();
        ArmRequestGate gate = new ArmRequestGate(List.of(new LoggingRule(1, log)));

        assertThrows(IllegalStateException.class, () -> gate.serve(request("GET", "subscriptions/s"), req -> {
            throw new IllegalStateException("boom");
        }));

        assertEquals(List.of("enter1 GET /subscriptions/s", "exit1 threw"), log);
    }

    @Test
    void aPathOutsideTheArmRootsEntersWithoutAnId() {
        ArmRequestGate gate = new ArmRequestGate(List.of());

        ArmCall call = gate.enter(request("GET", "devstoreaccount1/c"));

        assertNull(call.id());
        assertSame(SCOPE, call.scope());
    }

    @Test
    void twoRulesClaimingOneOrderFailFast() {
        List<String> log = new ArrayList<>();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new ArmRequestGate(List.of(new LoggingRule(5, log), new LoggingRule(5, log))));

        assertTrue(failure.getMessage().contains("order 5"), failure.getMessage());
    }
}
