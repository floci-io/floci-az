package io.floci.az.services.monitor.activitylog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ActivityLogFilter: the $filter grammar of the list operation")
class ActivityLogFilterTest {

    private static ActivityLogEvent event(String operation, String level, String status, String resourceGroup) {
        Instant at = Instant.parse("2026-10-12T10:00:00Z");
        return new ActivityLogEvent("e1", "o1", "corr-1", at, at, "Administrative", "EndRequest", operation, level, status,
            "Created", "me@contoso.com", Map.of("appid", "app-1"), operation,
            "/subscriptions/s/resourceGroups/" + resourceGroup + "/providers/Microsoft.Storage/storageAccounts/sa", "PUT",
            "/subscriptions/s/resourceGroups/" + resourceGroup + "/providers/Microsoft.Storage/storageAccounts/sa?api-version=2023-01-01",
            null, "127.0.0.1", "/subscriptions/s/resourceGroups/" + resourceGroup + "/providers/Microsoft.Storage/storageAccounts/sa",
            "Microsoft.Storage/storageAccounts", "Microsoft.Storage", resourceGroup, "s", "t", Map.of("statusCode", "Created"));
    }

    @Test
    void requiresAStartTimeAndNarrowsByEveryClause() {
        ActivityLogEvent write = event("Microsoft.Storage/storageAccounts/write", "Informational", "Succeeded", "rg1");
        Predicate<ActivityLogEvent> all = ActivityLogFilter.parse("eventTimestamp ge '2026-10-12T09:00:00Z' and eventTimestamp le '2026-10-12T11:00:00Z'");
        assertTrue(all.test(write));
        assertFalse(ActivityLogFilter.parse("eventTimestamp ge '2026-10-12T10:00:01Z'").test(write));
        assertTrue(ActivityLogFilter.parse("eventTimestamp ge '2026-10-12T09:00:00Z' and resourceGroupName eq 'RG1'").test(write));
        assertFalse(ActivityLogFilter.parse("eventTimestamp ge '2026-10-12T09:00:00Z' and resourceGroupName eq 'rg2'").test(write));
        assertTrue(ActivityLogFilter.parse("eventTimestamp ge '2026-10-12T09:00:00Z' and resourceUri eq '/subscriptions/s/resourceGroups/rg1/providers/Microsoft.Storage/storageAccounts/sa'").test(write));
        assertTrue(ActivityLogFilter.parse("eventTimestamp ge '2026-10-12T09:00:00Z' and resourceProvider eq 'Microsoft.Storage'").test(write));
        assertTrue(ActivityLogFilter.parse("eventTimestamp ge '2026-10-12T09:00:00Z' and correlationId eq 'corr-1'").test(write));
        assertTrue(ActivityLogFilter.parse("eventTimestamp ge '2026-10-12T09:00:00Z' and eventChannels eq 'Operation' and operations eq 'Microsoft.Storage/storageAccounts/write' and levels eq 'Informational'").test(write));
        assertFalse(ActivityLogFilter.parse("eventTimestamp ge '2026-10-12T09:00:00Z' and operations eq 'Microsoft.Storage/storageAccounts/delete'").test(write));
        assertTrue(ActivityLogFilter.parse("eventTimestamp ge '2026-10-12T09:00:00Z' and levels eq 'Error, Informational'").test(write));
        assertTrue(ActivityLogFilter.parse("eventTimestamp ge '2026-10-12T09:00:00Z' and status eq 'Succeeded' and caller eq 'me@contoso.com'").test(write));
        assertTrue(ActivityLogFilter.parse("eventTimestamp ge '2026-10-12T09:00:00Z' and searchText eq 'storageAccounts/sa'").test(write));
        assertFalse(ActivityLogFilter.parse("eventTimestamp ge '2026-10-12T09:00:00Z' and searchText eq 'nothing like it'").test(write));
    }

    @Test
    void rejectsWhatTheGrammarDoesNotAllow() {
        assertThrows(ActivityLogFilter.InvalidFilterException.class, () -> ActivityLogFilter.parse(null));
        assertThrows(ActivityLogFilter.InvalidFilterException.class, () -> ActivityLogFilter.parse("resourceGroupName eq 'rg1'"));
        assertThrows(ActivityLogFilter.InvalidFilterException.class, () -> ActivityLogFilter.parse("eventTimestamp ge '2026-10-12T09:00:00Z' and colour eq 'blue'"));
        assertThrows(ActivityLogFilter.InvalidFilterException.class, () -> ActivityLogFilter.parse("eventTimestamp ge 'yesterday'"));
        assertThrows(ActivityLogFilter.InvalidFilterException.class, () -> ActivityLogFilter.parse("eventTimestamp ge '2026-10-12T09:00:00Z' or resourceGroupName eq 'rg1'"));
    }

    @Test
    void acceptsTheUnquotedOffsetLessTimestampsTheCliSends() {
        ActivityLogEvent write = event("Microsoft.Storage/storageAccounts/write", "Informational", "Succeeded", "rg1");
        // az monitor activity-log list: `eventTimestamp ge {start} and eventTimestamp le {end}` from
        // datetime.utcnow().isoformat(), no quotes and no offset.
        assertTrue(ActivityLogFilter.parse("eventTimestamp ge 2026-10-12T09:00:00.123456 and eventTimestamp le 2026-10-12T11:00:00").test(write));
        assertFalse(ActivityLogFilter.parse("eventTimestamp ge 2026-10-12T10:00:01").test(write));
        assertTrue(ActivityLogFilter.parse("eventTimestamp ge 2026-10-12 and resourceGroupName eq 'rg1'").test(write));
        assertTrue(ActivityLogFilter.parse("eventTimestamp ge 2026-10-12T11:00:00+02:00").test(write));
        assertEquals(Instant.parse("2026-10-12T09:00:00Z"), ActivityLogFilter.instant("2026-10-12T09:00:00"));
        assertThrows(ActivityLogFilter.InvalidFilterException.class, () -> ActivityLogFilter.parse("eventTimestamp ge 2026-10-12T09:00:00 and resourceGroupName eq rg 1"));
    }

    @Test
    void quotesAreEscapedByDoubling() {
        ActivityLogEvent write = event("Microsoft.Storage/storageAccounts/write", "Informational", "Succeeded", "it's");
        assertTrue(ActivityLogFilter.parse("eventTimestamp ge '2026-10-12T09:00:00Z' and resourceGroupName eq 'it''s'").test(write));
        assertEquals(2, ActivityLogFilter.splitOnAnd("a eq 'x and y' and b eq 'z'").size());
    }
}
