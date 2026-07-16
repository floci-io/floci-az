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

    private static ServiceBusModels.RuleEntity correlationTyped(Map<String, String> props,
                                                                 Map<String, String> types) {
        return new ServiceBusModels.RuleEntity("t", "s", "r", "CorrelationFilter",
                null, null, null, null, null, null, null, null, null,
                props, types, null, Instant.EPOCH);
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
        assertEquals("quantity = 10", ServiceBusRuleSelector.forRule(
                correlationTyped(Map.of("quantity", "10"), Map.of("quantity", "int"))));
        assertEquals("urgent = TRUE", ServiceBusRuleSelector.forRule(
                correlationTyped(Map.of("urgent", "true"), Map.of("urgent", "boolean"))));
        // a declared numeric type with a non-numeric value falls back to a string literal
        assertEquals("quantity = 'ten'", ServiceBusRuleSelector.forRule(
                correlationTyped(Map.of("quantity", "ten"), Map.of("quantity", "int"))));
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

    @Test
    void sqlFilterSupportsInBetweenAndParentheses() {
        assertEquals("color IN ('red', 'blue') OR (quantity + 5) * 2 <= 30",
                ServiceBusRuleSelector.forRule(sql("color IN ('red', 'blue') OR (quantity + 5) * 2 <= 30")));
        assertEquals("note NOT LIKE 'a%'",
                ServiceBusRuleSelector.forRule(sql("note NOT LIKE 'a%'")));
    }

    @Test
    void sqlFilterPrefixesAreCaseInsensitiveAndKeywordCasePreserved() {
        assertEquals("JMSType = 'x'", ServiceBusRuleSelector.forRule(sql("SYS.LABEL = 'x'")));
        assertEquals("Color = 'y'", ServiceBusRuleSelector.forRule(sql("USER.Color = 'y'")));
        assertEquals("a = 1 and b = 2", ServiceBusRuleSelector.forRule(sql("a = 1 and b = 2")));
    }

    @Test
    void sqlFilterAllowsUnderscoreAndDollarIdentifiers() {
        assertEquals("_private = 1 AND $dollar = 2",
                ServiceBusRuleSelector.forRule(sql("_private = 1 AND $dollar = 2")));
    }

    @Test
    void sqlFilterMapsBracketedSystemProperty() {
        assertEquals("JMSType = 'blue'",
                ServiceBusRuleSelector.forRule(sql("[sys.Label] = 'blue'")));
    }

    @Test
    void sqlFilterRejectsBadBracketedIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> ServiceBusRuleSelector.forRule(sql("[bad-name] = 1")));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceBusRuleSelector.forRule(sql("[unterminated = 1")));
    }

    @Test
    void existsVariants() {
        assertEquals("(color IS NOT NULL)",
                ServiceBusRuleSelector.forRule(sql("EXISTS(user.color)")));
        assertEquals("(JMSXGroupID IS NOT NULL)",
                ServiceBusRuleSelector.forRule(sql("EXISTS ( sys.SessionId )")));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceBusRuleSelector.forRule(sql("EXISTS(sys.MessageId)")));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceBusRuleSelector.forRule(sql("EXISTS color")));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceBusRuleSelector.forRule(sql("EXISTS(color")));
    }

    @Test
    void unknownFilterTypeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ServiceBusRuleSelector.forRule(ofType("BogusFilter")));
    }

    @Test
    void typedLiteralsForDecimalAndInvalidBoolean() {
        assertEquals("price = 2.5", ServiceBusRuleSelector.forRule(
                correlationTyped(Map.of("price", "2.5"), Map.of("price", "double"))));
        // a declared boolean that isn't true/false falls back to a string literal
        assertEquals("urgent = 'yes'", ServiceBusRuleSelector.forRule(
                correlationTyped(Map.of("urgent", "yes"), Map.of("urgent", "boolean"))));
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
