package io.floci.az.services.arm;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.ArmRouter;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmCall;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmVerdict;
import io.floci.az.core.arm.ResourceGroupCleanup;
import io.floci.az.core.arm.ResourceGroupRule;
import io.floci.az.core.cloud.CloudDefaults;
import io.floci.az.core.directory.ResourceGroup;
import io.floci.az.core.directory.ResourceGroups;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A resource written while its group's cascading delete runs must not outlive the group: the rule and the
 * service, wired as in production over one {@link ResourceGroups}, with the router mocked so the test decides
 * what happens during the cascade.
 */
@DisplayName("ARM resource groups: a write racing the cascading delete")
class ResourceGroupDeleteRaceTest {

    private static final String SUB = "7a1d0000-0000-4000-8000-00000000c0a6";
    private static final String GROUP = "rg-race";
    private static final String EXISTING = "/subscriptions/" + SUB + "/resourceGroups/" + GROUP
        + "/providers/Microsoft.Storage/storageAccounts/raceexisting";
    private static final String LATE = "/subscriptions/" + SUB + "/resourceGroups/" + GROUP
        + "/providers/Microsoft.Storage/storageAccounts/racelate";

    private final List<String> index = new CopyOnWriteArrayList<>();
    private final List<String> cascaded = new CopyOnWriteArrayList<>();
    private ResourceGroups groups;
    private ResourceGroupRule rule;
    private ResourceGroupService service;
    private ArmRouter router;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wire() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.resourceGroupDelete()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4577");
        router = mock(ArmRouter.class);
        Instance<ArmRouter> routers = mock(Instance.class);
        when(routers.get()).thenReturn(router);
        groups = new ResourceGroups(null);
        groups.put(new ResourceGroup(SUB, GROUP, "westus", null, null, ResourceGroup.SUCCEEDED));
        rule = new ResourceGroupRule(groups, config, CloudDefaults.catalogOnly());
        Instance<ResourceGroupCleanup> cleanups = mock(Instance.class);
        when(cleanups.iterator()).thenAnswer(invocation -> List.<ResourceGroupCleanup>of().iterator());
        service = new ResourceGroupService(config, groups, routers, cleanups, CloudDefaults.catalogOnly());
        index.add(EXISTING);
    }

    private static ArmCall call(String method, String path) {
        return new ArmCall(null, ArmResourceId.parse(path.substring(1)).orElseThrow(), null, method, null);
    }

    private static AzureRequest deleteRequest() {
        return new AzureRequest("DELETE", "arm", "arm", "", null, null, Map.of(), null, false);
    }

    private Response deleteGroup() {
        return service.handle(deleteRequest(), "DELETE", SUB, GROUP, () -> new ArrayList<>(index));
    }

    /** The router deletes from the test's index, as the handlers behind the real one delete their resources. */
    private void routerDeletes(Runnable duringTheFirstDelete) {
        when(router.dispatchInternal(eq("DELETE"), anyString(), any(), any())).thenAnswer(invocation -> {
            if (cascaded.isEmpty()) {
                duringTheFirstDelete.run();
            }
            String id = invocation.getArgument(1);
            cascaded.add(id);
            index.remove(id);
            return Response.ok().build();
        });
    }

    @Test
    void aWriteUnderTheGroupIsRefusedWhileTheDeleteRunsAndAdmittedOnceItEnded() {
        AtomicReference<ArmVerdict> put = new AtomicReference<>();
        AtomicReference<ArmVerdict> patch = new AtomicReference<>();
        AtomicReference<ArmVerdict> read = new AtomicReference<>();
        AtomicReference<ArmVerdict> childDelete = new AtomicReference<>();
        routerDeletes(() -> {
            put.set(rule.enter(call("PUT", LATE)));
            patch.set(rule.enter(call("PATCH", LATE)));
            read.set(rule.enter(call("GET", LATE)));
            childDelete.set(rule.enter(call("DELETE", EXISTING)));
        });

        assertEquals(202, deleteGroup().getStatus());

        Response refused = assertInstanceOf(ArmVerdict.Respond.class, put.get()).response();
        assertEquals(409, refused.getStatus());
        Map<?, ?> error = (Map<?, ?>) ((Map<?, ?>) refused.getEntity()).get("error");
        assertEquals("ResourceGroupBeingDeleted", error.get("code"));
        assertInstanceOf(ArmVerdict.Respond.class, patch.get());
        assertInstanceOf(ArmVerdict.Proceed.class, read.get());
        assertInstanceOf(ArmVerdict.Proceed.class, childDelete.get());
        assertThat(cascaded, contains(EXISTING));
        assertFalse(groups.exists(SUB, GROUP));

        ArmCall afterwards = call("PUT", LATE);
        assertInstanceOf(ArmVerdict.Proceed.class, rule.enter(afterwards));
        rule.exit(afterwards, Response.ok().build());
    }

    @Test
    void theDeleteWaitsForAWriteAdmittedBeforeItAndDeletesWhatThatWriteCreated() throws InterruptedException {
        routerDeletes(() -> { });
        ArmCall inFlight = call("PUT", LATE);
        assertInstanceOf(ArmVerdict.Proceed.class, rule.enter(inFlight));

        AtomicReference<Response> answer = new AtomicReference<>();
        Thread delete = new Thread(() -> answer.set(deleteGroup()), "rg-delete");
        delete.start();
        awaitBlocked(delete);
        assertThat("nothing is listed or deleted while the write runs", cascaded, empty());
        assertInstanceOf(ArmVerdict.Respond.class, rule.enter(call("PUT", LATE)),
            "a write arriving after the delete began is refused");

        index.add(LATE);
        rule.exit(inFlight, Response.status(201).build());
        delete.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(delete.isAlive(), "the delete ends once the write has");
        assertEquals(202, answer.get().getStatus());
        assertTrue(cascaded.contains(LATE), "the resource the racing write created is deleted: " + cascaded);
        assertThat(index, empty());
        assertFalse(groups.exists(SUB, GROUP));
    }

    @Test
    void aFailedDeleteRemovesTheMarkAndKeepsTheGroup() {
        when(router.dispatchInternal(eq("DELETE"), anyString(), any(), any()))
            .thenThrow(new IllegalStateException("handler failed"));

        assertThrows(IllegalStateException.class, this::deleteGroup);

        assertTrue(groups.exists(SUB, GROUP));
        ArmCall afterwards = call("PUT", LATE);
        assertInstanceOf(ArmVerdict.Proceed.class, rule.enter(afterwards));
        rule.exit(afterwards, Response.ok().build());
    }

    @Test
    void aMissingGroupMarksNothing() {
        assertEquals(404, service.handle(deleteRequest(), "DELETE", SUB, "rg-absent", List::of).getStatus());

        ArmCall underTheAbsentGroup = call("PUT", LATE.replace(GROUP, "rg-absent"));
        assertInstanceOf(ArmVerdict.Proceed.class, rule.enter(underTheAbsentGroup));
        rule.exit(underTheAbsentGroup, Response.ok().build());
    }

    /** Returns once {@code thread} is parked waiting for the in-flight write; fails if it ends instead. */
    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.WAITING) {
            if (thread.getState() == Thread.State.TERMINATED) {
                fail("the delete ended while a write under the group was still running");
            }
            if (System.nanoTime() > deadline) {
                fail("the delete never waited for the write; state " + thread.getState());
            }
            Thread.sleep(5);
        }
    }
}
