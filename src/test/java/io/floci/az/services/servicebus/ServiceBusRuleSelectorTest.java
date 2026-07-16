package io.floci.az.services.servicebus;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServiceBusRuleSelectorTest {

    private static ServiceBusModels.RuleEntity correlation(String correlationId, String label,
                                                            String sessionId, Map<String, String> props) {
        return new ServiceBusModels.RuleEntity("t", "s", "r", "CorrelationFilter",
                null, correlationId, null, null, null, label, sessionId, null, null,
                props, Map.of(), null, Instant.EPOCH);
    }

    private static ServiceBusModels.RuleEntity sql(String expression) {
        return new ServiceBusModels.RuleEntity("t", "s", "r", "SqlFilter",
                expression, null, null, null, null, null, null, null, null,
                Map.of(), Map.of(), null, Instant.EPOCH);
    }

    private static ServiceBusModels.RuleEntity ofType(String filterType) {
        return new ServiceBusModels.RuleEntity("t", "s", "r", filterType,
                null, null, null, null, null, null, null, null, null,
                Map.of(), Map.of(), null, Instant.EPOCH);
    }

    // ── CorrelationFilter ─────────────────────────────────────────────────────

    @Test
    void correlationFilterMapsSystemProperties() {
        String selector = ServiceBusRuleSelector.forRule(
                correlation("corr-1", "order.created", "sess-9", Map.of()));
        assertEquals("JMSCorrelationID = 'corr-1' AND JMSType = 'order.created' AND JMSXGroupID = 'sess-9'",
                selector);
    }

    @Test
    void correlationFilterOnLabelOnly() {
        assertEquals("JMSType = 'red'",
                ServiceBusRuleSelector.forRule(correlation(null, "red", null, Map.of())));
    }

    @Test
    void correlationFilterUserPropertiesAndQuoteEscaping() {
        String selector = ServiceBusRuleSelector.forRule(
                correlation(null, "it's", null, Map.of("color", "blue")));
        assertEquals("JMSType = 'it''s' AND color = 'blue'", selector);
    }

    @Test
    void correlationFilterEmitsTypedLiterals() {
        ServiceBusModels.RuleEntity rule = new ServiceBusModels.RuleEntity(
                "t", "s", "r", "CorrelationFilter", null, null, null, null, null,
                null, null, null, null,
                Map.of("quantity", "10"),
                Map.of("quantity", "int"),
                null, Instant.EPOCH);
        assertEquals("quantity = 10", ServiceBusRuleSelector.forRule(rule));

        ServiceBusModels.RuleEntity boolRule = new ServiceBusModels.RuleEntity(
                "t", "s", "r", "CorrelationFilter", null, null, null, null, null,
                null, null, null, null,
                Map.of("urgent", "true"),
                Map.of("urgent", "boolean"),
                null, Instant.EPOCH);
        assertEquals("urgent = TRUE", ServiceBusRuleSelector.forRule(boolRule));

        // a declared numeric type with a non-numeric value falls back to a string literal
        ServiceBusModels.RuleEntity badInt = new ServiceBusModels.RuleEntity(
                "t", "s", "r", "CorrelationFilter", null, null, null, null, null,
                null, null, null, null,
                Map.of("quantity", "ten"),
                Map.of("quantity", "int"),
                null, Instant.EPOCH);
        assertEquals("quantity = 'ten'", ServiceBusRuleSelector.forRule(badInt));
    }

    @Test
    void correlationFilterRejectsUnsupportedSystemProperties() {
        ServiceBusModels.RuleEntity withMessageId = new ServiceBusModels.RuleEntity(
                "t", "s", "r", "CorrelationFilter", null, null, "msg-1", null, null,
                null, null, null, null, Map.of(), Map.of(), null, Instant.EPOCH);
        assertThrows(IllegalArgumentException.class,
                () -> ServiceBusRuleSelector.forRule(withMessageId));
    }

    @Test
    void correlationFilterRejectsInvalidPropertyNames() {
        assertThrows(IllegalArgumentException.class, () -> ServiceBusRuleSelector.forRule(
                correlation(null, null, null, Map.of("bad-name", "x"))));
    }

    @Test
    void emptyCorrelationFilterIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ServiceBusRuleSelector.forRule(
                correlation(null, null, null, Map.of())));
    }

    // ── SqlFilter translation ─────────────────────────────────────────────────

    @Test
    void sqlFilterPassesUserPropertiesThrough() {
        assertEquals("color = 'blue' AND quantity = 10",
                ServiceBusRuleSelector.forRule(sql("color = 'blue' AND quantity = 10")));
    }

    @Test
    void sqlFilterMapsSysAndUserPrefixes() {
        assertEquals("JMSType = 'red' OR color = 'red'",
                ServiceBusRuleSelector.forRule(sql("sys.Label = 'red' OR user.color = 'red'")));
        assertEquals("JMSCorrelationID = 'x'",
                ServiceBusRuleSelector.forRule(sql("sys.CorrelationId = 'x'")));
        assertEquals("JMSType = 'y'",
                ServiceBusRuleSelector.forRule(sql("sys.Subject = 'y'")));
        assertEquals("JMSXGroupID = 'z'",
                ServiceBusRuleSelector.forRule(sql("sys.SessionId = 'z'")));
    }

    @Test
    void sqlFilterKeywordsAreNotTreatedAsProperties() {
        assertEquals("color IS NOT NULL AND quantity BETWEEN 1 AND 10 AND color LIKE 'b%'",
                ServiceBusRuleSelector.forRule(
                        sql("color IS NOT NULL AND quantity BETWEEN 1 AND 10 AND color LIKE 'b%'")));
    }

    @Test
    void sqlFilterRewritesExists() {
        assertEquals("(color IS NOT NULL)",
                ServiceBusRuleSelector.forRule(sql("EXISTS(color)")));
        assertEquals("NOT (JMSType IS NOT NULL)",
                ServiceBusRuleSelector.forRule(sql("NOT EXISTS ( sys.Label )")));
    }

    @Test
    void sqlFilterLeavesStringLiteralsUntouched() {
        assertEquals("color = 'sys.Label AND EXISTS(x)'",
                ServiceBusRuleSelector.forRule(sql("color = 'sys.Label AND EXISTS(x)'")));
        assertEquals("note = 'it''s ok'",
                ServiceBusRuleSelector.forRule(sql("note = 'it''s ok'")));
    }

    @Test
    void sqlFilterStripsBracketDelimiters() {
        assertEquals("color = 'blue'",
                ServiceBusRuleSelector.forRule(sql("[color] = 'blue'")));
    }

    @Test
    void sqlFilterRejectsUnsupportedConstructs() {
        assertThrows(IllegalArgumentException.class,
                () -> ServiceBusRuleSelector.forRule(sql("sys.MessageId = 'x'")));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceBusRuleSelector.forRule(sql("quantity % 2 = 0")));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceBusRuleSelector.forRule(sql("color = 'unterminated")));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceBusRuleSelector.forRule(sql("")));
    }

    // ── Rule-set combination ──────────────────────────────────────────────────

    @Test
    void noRulesMatchesNothing() {
        assertEquals(ServiceBusRuleSelector.MATCH_NONE, ServiceBusRuleSelector.forRules(List.of()));
    }

    @Test
    void trueFilterMatchesEverything() {
        assertEquals(ServiceBusRuleSelector.MATCH_ALL,
                ServiceBusRuleSelector.forRules(List.of(ofType("TrueFilter"))));
        assertEquals(ServiceBusRuleSelector.MATCH_ALL,
                ServiceBusRuleSelector.forRules(List.of(sql("a = 1"), ofType("TrueFilter"))));
    }

    @Test
    void multipleRulesCombineWithOr() {
        assertEquals("(JMSType = 'a') OR (color = 'b')",
                ServiceBusRuleSelector.forRules(List.of(
                        correlation(null, "a", null, Map.of()),
                        sql("color = 'b'"))));
    }

    @Test
    void falseFiltersAreDropped() {
        assertEquals("JMSType = 'a'",
                ServiceBusRuleSelector.forRules(List.of(
                        ofType("FalseFilter"),
                        correlation(null, "a", null, Map.of()))));
        assertEquals(ServiceBusRuleSelector.MATCH_NONE,
                ServiceBusRuleSelector.forRules(List.of(ofType("FalseFilter"))));
    }
}
