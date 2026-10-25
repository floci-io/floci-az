package io.floci.az.core.arm;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.ScopeContext;
import io.floci.az.core.cloud.ScopeSource;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        public ArmVerdict enter(ArmCall call) {
            log.add("enter" + order + " " + call.method() + " " + call.id());
            return ArmVerdict.proceed();
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
    void anExitHookThatThrowsDoesNotSkipTheLaterOnes() {
        List<String> log = new ArrayList<>();
        ArmRequestRule failing = new ArmRequestRule() {
            @Override
            public int order() {
                return 1;
            }

            @Override
            public void exit(ArmCall call, Response response) {
                throw new IllegalStateException("exit1");
            }
        };
        ArmRequestGate gate = new ArmRequestGate(List.of(failing, new LoggingRule(2, log)));

        assertThrows(IllegalStateException.class,
            () -> gate.serve(request("GET", "subscriptions/s"), req -> Response.ok().build()));

        assertEquals(List.of("enter2 GET /subscriptions/s", "exit2 200"), log);
    }

    @Test
    void aPathOutsideTheArmRootsEntersWithoutAnId() {
        ArmRequestGate gate = new ArmRequestGate(List.of());

        ArmCall call = gate.call(request("GET", "devstoreaccount1/c"));

        assertNull(call.id());
        assertSame(SCOPE, call.scope());
    }

    /** Answers every request itself with {@code status}, logging its hooks like {@link LoggingRule}. */
    private record RespondingRule(int order, int status, List<String> log) implements ArmRequestRule {
        @Override
        public ArmVerdict enter(ArmCall call) {
            log.add("enter" + order + " respond " + status);
            return ArmVerdict.respond(Response.status(status).build());
        }

        @Override
        public void exit(ArmCall call, Response response) {
            log.add("exit" + order + " " + (response == null ? "threw" : response.getStatus()));
        }
    }

    @Test
    void aRuleThatRespondsStandsInForTheHandlerAndTheLaterRules() {
        List<String> log = new ArrayList<>();
        ArmRequestGate gate = new ArmRequestGate(List.of(new LoggingRule(1, log), new RespondingRule(2, 404, log),
            new LoggingRule(3, log)));

        Response served = gate.serve(request("PUT", "subscriptions/s/resourceGroups/rg"), req -> {
            log.add("handler");
            return Response.ok().build();
        });

        assertEquals(404, served.getStatus());
        assertEquals(List.of("enter1 PUT /subscriptions/s/resourceGroups/rg", "enter2 respond 404",
            "exit1 404", "exit2 404"), log);
    }

    @Test
    void anEntryCheckThatThrowsStillExitsTheRulesEnteredSoFar() {
        List<String> log = new ArrayList<>();
        ArmRequestRule throwing = new ArmRequestRule() {
            @Override
            public int order() {
                return 2;
            }

            @Override
            public ArmVerdict enter(ArmCall call) {
                throw new IllegalStateException("boom");
            }
        };
        ArmRequestGate gate = new ArmRequestGate(List.of(new LoggingRule(1, log), throwing, new LoggingRule(3, log)));

        assertThrows(IllegalStateException.class, () -> gate.serve(request("GET", "subscriptions/s"), req -> {
            log.add("handler");
            return Response.ok().build();
        }));

        assertEquals(List.of("enter1 GET /subscriptions/s", "exit1 threw"), log);
    }

    @Test
    void twoRulesClaimingOneOrderFailFast() {
        List<String> log = new ArrayList<>();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new ArmRequestGate(List.of(new LoggingRule(5, log), new LoggingRule(5, log))));

        assertTrue(failure.getMessage().contains("order 5"), failure.getMessage());
    }

    private static AzureRequest requestWithBody(String method, String path, String body) {
        return new AzureRequest(method, "arm", "arm", path, null,
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), Map.of("api-version", "2023-01-01"),
            Map.of(), null, false, null, null, path, null).withScope(SCOPE);
    }

    private static String read(AzureRequest request) {
        try {
            return new String(request.bodyStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads the body and api-version, and amends the call's location and the body. */
    private record AmendingRule(int order, List<String> log) implements ArmRequestRule {
        @Override
        public boolean readsBody() {
            return true;
        }

        @Override
        public ArmVerdict enter(ArmCall call) {
            log.add("saw " + new String(call.body().bytes(), StandardCharsets.UTF_8) + " at "
                + call.request().queryParams().get("api-version"));
            call.body().replace(Map.of("location", "eastus"));
            return ArmVerdict.amend(new ArmCall(call.scope(), call.id(), call.type(), call.method(), "eastus",
                call.body(), call.request()));
        }

        @Override
        public void exit(ArmCall call, Response response) {
            log.add("exit" + order + " " + call.normalizedLocation());
        }
    }

    @Test
    void anAmendingRuleRewritesTheBodyAndCallForLaterRulesAndTheHandler() {
        List<String> log = new ArrayList<>();
        AtomicReference<AzureRequest> seen = new AtomicReference<>();
        ArmRequestGate gate = new ArmRequestGate(List.of(new LoggingRule(1, log), new AmendingRule(5, log),
            new LoggingRule(9, log)));

        gate.serve(requestWithBody("PUT", "subscriptions/s/resourceGroups/rg", "{\"location\":\"East US\"}"),
            req -> {
                seen.set(req);
                return Response.ok().build();
            });

        assertEquals("eastus", seen.get().armCall().normalizedLocation());
        assertEquals("{\"location\":\"eastus\"}", read(seen.get()));
        assertEquals(List.of("enter1 PUT /subscriptions/s/resourceGroups/rg",
            "saw {\"location\":\"East US\"} at 2023-01-01",
            "enter9 PUT /subscriptions/s/resourceGroups/rg",
            "exit1 200", "exit5 null", "exit9 200"), log, "each rule exits with the call it was entered with");
    }

    @Test
    void aBufferedBodyThatNoRuleRewritesReachesTheHandlerIntact() {
        List<String> log = new ArrayList<>();
        AtomicReference<String> seen = new AtomicReference<>();
        ArmRequestRule reader = new ArmRequestRule() {
            @Override
            public int order() {
                return 3;
            }

            @Override
            public boolean readsBody() {
                return true;
            }

            @Override
            public ArmVerdict enter(ArmCall call) {
                log.add(new String(call.body().bytes(), StandardCharsets.UTF_8));
                return ArmVerdict.proceed();
            }
        };
        ArmRequestGate gate = new ArmRequestGate(List.of(reader));

        gate.serve(requestWithBody("PUT", "subscriptions/s/resourceGroups/rg", "{\"tags\":{}}"), req -> {
            seen.set(read(req));
            return Response.ok().build();
        });

        assertEquals(List.of("{\"tags\":{}}"), log);
        assertEquals("{\"tags\":{}}", seen.get());
    }

    @Test
    void withoutABodyReadingRuleTheBodyStreamIsNotTouched() {
        AtomicReference<AzureRequest> seen = new AtomicReference<>();
        AzureRequest original = requestWithBody("PUT", "subscriptions/s/resourceGroups/rg", "{}");
        ArmRequestGate gate = new ArmRequestGate(List.of(new LoggingRule(1, new ArrayList<>())));

        gate.serve(original, req -> {
            seen.set(req);
            return Response.ok().build();
        });

        assertSame(original.bodyStream(), seen.get().bodyStream());
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), read(seen.get()).getBytes(StandardCharsets.UTF_8));
    }
}
