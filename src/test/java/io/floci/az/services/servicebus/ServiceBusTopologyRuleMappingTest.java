package io.floci.az.services.servicebus;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServiceBusTopologyRuleMappingTest {

    private static ServiceBusTopologyFile.Rule rule(String name,
                                                     ServiceBusTopologyFile.RuleProperties properties) {
        return new ServiceBusTopologyFile.Rule(name, properties);
    }

    @Test
    void mapsCorrelationFilterWithTypedProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("region", "eu");
        properties.put("priority", 3);
        properties.put("score", 1.5);
        properties.put("vip", true);
        var filter = new ServiceBusTopologyFile.CorrelationFilter(
                properties, "corr-1", null, null, null, "order-created", "session-1", null, null);
        var entity = ServiceBusTopologyLoader.toRuleEntity("t", "s",
                rule("r1", new ServiceBusTopologyFile.RuleProperties("Correlation", filter, null, null)));

        assertEquals("CorrelationFilter", entity.filterType());
        assertEquals("corr-1", entity.correlationId());
        assertEquals("order-created", entity.label());
        assertEquals("session-1", entity.sessionId());
        assertEquals("eu", entity.correlationProperties().get("region"));
        assertEquals("3", entity.correlationProperties().get("priority"));
        assertNull(entity.correlationPropertyTypes().get("region"));
        assertEquals("long", entity.correlationPropertyTypes().get("priority"));
        assertEquals("double", entity.correlationPropertyTypes().get("score"));
        assertEquals("boolean", entity.correlationPropertyTypes().get("vip"));
    }

    @Test
    void mapsSqlFilterWithAction() {
        var entity = ServiceBusTopologyLoader.toRuleEntity("t", "s",
                rule("r2", new ServiceBusTopologyFile.RuleProperties("Sql",
                        null,
                        new ServiceBusTopologyFile.SqlExpressionHolder("priority > 2"),
                        new ServiceBusTopologyFile.SqlExpressionHolder("SET x = 1"))));

        assertEquals("SqlFilter", entity.filterType());
        assertEquals("priority > 2", entity.sqlExpression());
        assertEquals("SET x = 1", entity.actionSqlExpression());
    }

    @Test
    void rejectsMissingFilterType() {
        assertThrows(IllegalArgumentException.class, () -> ServiceBusTopologyLoader.toRuleEntity(
                "t", "s", rule("r", new ServiceBusTopologyFile.RuleProperties(null, null, null, null))));
    }

    @Test
    void rejectsSqlFilterWithoutExpression() {
        assertThrows(IllegalArgumentException.class, () -> ServiceBusTopologyLoader.toRuleEntity(
                "t", "s", rule("r", new ServiceBusTopologyFile.RuleProperties("Sql", null, null, null))));
    }

    @Test
    void rejectsUnknownFilterType() {
        assertThrows(IllegalArgumentException.class, () -> ServiceBusTopologyLoader.toRuleEntity(
                "t", "s", rule("r", new ServiceBusTopologyFile.RuleProperties("Fancy", null, null, null))));
    }
}
