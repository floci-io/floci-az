package io.floci.az.core.arm;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.ScopeContext;
import io.floci.az.core.cloud.ScopeSource;
import io.floci.az.core.directory.ResourceGroups;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("AppendedLocationRule: a location added after the first pass is checked too")
class AppendedLocationRuleTest {

    private static final String SUB = "00000000-0000-0000-0000-0000000000d2";
    private static final String ACCOUNT = "subscriptions/" + SUB
        + "/resourceGroups/rg-append/providers/Microsoft.Storage/storageAccounts/appendacct";

    private static final ArmTypeRegistry TYPES = new ArmTypeRegistry(Map.of(
        "microsoft.storage", new ArmSurface("Microsoft.Storage", ArmLane.ARM_HANDLER, List.of(
            ArmResourceType.resourceGroup("Microsoft.Storage", "storageAccounts", LocationPolicy.REQUIRED)))));

    /** Stands in for a policy {@code append} between the two passes: sets the body's location to {@code value}. */
    private record AppendingRule(String value) implements ArmRequestRule {
        @Override
        public int order() {
            return 40;
        }

        @Override
        public boolean readsBody() {
            return true;
        }

        @Override
        public ArmVerdict enter(ArmCall call) {
            Map<String, Object> body = call.body().json();
            body.put("location", value);
            call.body().replace(body);
            return ArmVerdict.proceed();
        }
    }

    private static ArmRequestGate gate(boolean normalize, boolean strict, String appended) {
        LocationRule location = new LocationRule(TYPES, new ResourceGroups(null), normalize, strict);
        return new ArmRequestGate(List.of(location, new AppendingRule(appended), new AppendedLocationRule(location)),
            TYPES);
    }

    private static AzureRequest put(String body) {
        ScopeContext scope = new ScopeContext(CloudCatalog.forNameOrPublic("AzureCloud"), null, SUB, "rg-append",
            null, ScopeSource.ARM_PATH);
        return new AzureRequest("PUT", "arm", "arm", ACCOUNT, null,
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), Map.of("api-version", "2023-01-01"),
            Map.of(), null, false, null, null, ACCOUNT, null).withScope(scope);
    }

    private static String read(AzureRequest request) {
        try {
            return new String(request.bodyStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void anAppendedDisplayNameReachesTheHandlerCanonical() {
        AtomicReference<AzureRequest> seen = new AtomicReference<>();

        gate(true, false, "West Europe").serve(put("{\"location\":\"East US\"}"), req -> {
            seen.set(req);
            return Response.ok().build();
        });

        assertEquals("{\"location\":\"westeurope\"}", read(seen.get()));
        assertEquals("westeurope", seen.get().armCall().normalizedLocation());
    }

    @Test
    void anAppendedLocationTheCloudDoesNotOfferIsRefused() {
        AtomicReference<AzureRequest> seen = new AtomicReference<>();

        Response response = gate(false, true, "US Gov Virginia").serve(put("{\"location\":\"eastus\"}"), req -> {
            seen.set(req);
            return Response.ok().build();
        });

        assertEquals(400, response.getStatus());
        assertNull(seen.get(), "the handler must not run");
    }

    @Test
    void aBodyThePolicyLeftAloneIsNotCheckedTwice() {
        AtomicReference<AzureRequest> seen = new AtomicReference<>();

        gate(true, false, "eastus").serve(put("{\"location\":\"East US\"}"), req -> {
            seen.set(req);
            return Response.ok().build();
        });

        assertEquals("{\"location\":\"eastus\"}", read(seen.get()));
    }
}
