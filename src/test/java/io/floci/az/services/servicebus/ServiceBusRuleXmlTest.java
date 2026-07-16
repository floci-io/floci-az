package io.floci.az.services.servicebus;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceBusRuleXmlTest {

    private static final String SB_NS = "http://schemas.microsoft.com/netservices/2010/10/servicebus/connect";
    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";

    @Test
    void parsesCorrelationFilterRule() {
        String body = "<entry xmlns=\"http://www.w3.org/2005/Atom\"><content type=\"application/xml\">"
                + "<RuleDescription xmlns:i=\"" + XSI_NS + "\" xmlns=\"" + SB_NS + "\">"
                + "<Filter i:type=\"CorrelationFilter\">"
                + "<CorrelationId>high</CorrelationId>"
                + "<Label>red</Label>"
                + "<Properties><KeyValueOfstringanyType><Key>prop1</Key>"
                + "<Value i:type=\"d6p1:string\" xmlns:d6p1=\"http://www.w3.org/2001/XMLSchema\">abc</Value>"
                + "</KeyValueOfstringanyType></Properties>"
                + "</Filter>"
                + "<Action i:type=\"EmptyRuleAction\"/>"
                + "<Name>my-rule</Name>"
                + "</RuleDescription></content></entry>";

        ServiceBusModels.RuleEntity rule = ServiceBusRuleXml.parseRule("t", "s", "my-rule", body);
        assertEquals("CorrelationFilter", rule.filterType());
        assertEquals("high", rule.correlationId());
        assertEquals("red", rule.label());
        assertEquals(Map.of("prop1", "abc"), rule.correlationProperties());
        assertEquals("my-rule", rule.name());
    }

    @Test
    void parsesSqlFilterRuleWithAction() {
        String body = "<entry xmlns=\"http://www.w3.org/2005/Atom\"><content type=\"application/xml\">"
                + "<RuleDescription xmlns:i=\"" + XSI_NS + "\" xmlns=\"" + SB_NS + "\">"
                + "<Filter i:type=\"SqlFilter\">"
                + "<SqlExpression>color='blue' AND quantity=10</SqlExpression>"
                + "<CompatibilityLevel>20</CompatibilityLevel>"
                + "</Filter>"
                + "<Action i:type=\"SqlRuleAction\"><SqlExpression>SET quantity = quantity/2</SqlExpression></Action>"
                + "<Name>sql-rule</Name>"
                + "</RuleDescription></content></entry>";

        ServiceBusModels.RuleEntity rule = ServiceBusRuleXml.parseRule("t", "s", "sql-rule", body);
        assertEquals("SqlFilter", rule.filterType());
        assertEquals("color='blue' AND quantity=10", rule.sqlExpression());
        assertEquals("SET quantity = quantity/2", rule.actionSqlExpression());
    }

    @Test
    void ruleNameFromPathWinsOverBody() {
        String body = "<RuleDescription xmlns:i=\"" + XSI_NS + "\" xmlns=\"" + SB_NS + "\">"
                + "<Filter i:type=\"TrueFilter\"><SqlExpression>1=1</SqlExpression></Filter>"
                + "<Name>body-name</Name></RuleDescription>";
        assertEquals("path-name", ServiceBusRuleXml.parseRule("t", "s", "path-name", body).name());
    }

    @Test
    void emptyBodyDefaultsToTrueFilter() {
        ServiceBusModels.RuleEntity rule = ServiceBusRuleXml.parseRule("t", "s", "r", "");
        assertEquals("TrueFilter", rule.filterType());
    }

    @Test
    void parsesDefaultRuleDescriptionFromSubscriptionBody() {
        String body = "<entry xmlns=\"http://www.w3.org/2005/Atom\"><content type=\"application/xml\">"
                + "<SubscriptionDescription xmlns:i=\"" + XSI_NS + "\" xmlns=\"" + SB_NS + "\">"
                + "<LockDuration>PT1M</LockDuration>"
                + "<DefaultRuleDescription>"
                + "<Filter i:type=\"CorrelationFilter\"><Label>order.created</Label></Filter>"
                + "<Name>my-default</Name>"
                + "</DefaultRuleDescription>"
                + "</SubscriptionDescription></content></entry>";

        Optional<ServiceBusModels.RuleEntity> rule = ServiceBusRuleXml.parseDefaultRule("t", "s", body);
        assertTrue(rule.isPresent());
        assertEquals("CorrelationFilter", rule.get().filterType());
        assertEquals("order.created", rule.get().label());
        assertEquals("my-default", rule.get().name());
    }

    @Test
    void subscriptionBodyWithoutDefaultRuleYieldsEmpty() {
        String body = "<SubscriptionDescription xmlns=\"" + SB_NS + "\">"
                + "<RequiresSession>true</RequiresSession></SubscriptionDescription>";
        assertTrue(ServiceBusRuleXml.parseDefaultRule("t", "s", body).isEmpty());
    }

    @Test
    void roundTripsCorrelationFilterXml() {
        ServiceBusModels.RuleEntity rule = new ServiceBusModels.RuleEntity(
                "topic", "sub", "r1", "CorrelationFilter",
                null, "cid", null, null, null, "lbl", "sid", null, null,
                Map.of("k", "v"), Map.of(), null, Instant.EPOCH);
        String xml = ServiceBusRuleXml.ruleDescriptionXml(rule, SB_NS);

        ServiceBusModels.RuleEntity reparsed = ServiceBusRuleXml.parseRule("topic", "sub", "r1", xml);
        assertEquals("CorrelationFilter", reparsed.filterType());
        assertEquals("cid", reparsed.correlationId());
        assertEquals("lbl", reparsed.label());
        assertEquals("sid", reparsed.sessionId());
        assertEquals(Map.of("k", "v"), reparsed.correlationProperties());

        assertTrue(xml.contains("i:type=\"CorrelationFilter\""));
        assertTrue(xml.contains("<Name>r1</Name>"));
    }

    @Test
    void typedCorrelationPropertyValuesRoundTrip() {
        String body = "<RuleDescription xmlns:i=\"" + XSI_NS + "\" xmlns=\"" + SB_NS + "\">"
                + "<Filter i:type=\"CorrelationFilter\">"
                + "<Properties><KeyValueOfstringanyType><Key>quantity</Key>"
                + "<Value i:type=\"d6p1:int\" xmlns:d6p1=\"http://www.w3.org/2001/XMLSchema\">10</Value>"
                + "</KeyValueOfstringanyType></Properties>"
                + "</Filter><Name>typed</Name></RuleDescription>";

        ServiceBusModels.RuleEntity rule = ServiceBusRuleXml.parseRule("t", "s", "typed", body);
        assertEquals(Map.of("quantity", "10"), rule.correlationProperties());
        assertEquals(Map.of("quantity", "int"), rule.correlationPropertyTypes());

        String xml = ServiceBusRuleXml.ruleDescriptionXml(rule, SB_NS);
        assertTrue(xml.contains("i:type=\"d6p1:int\""));
    }

    @Test
    void trueFilterXmlUsesCanonicalExpression() {
        ServiceBusModels.RuleEntity rule = ServiceBusModels.RuleEntity.trueFilter("t", "s", "$Default");
        String xml = ServiceBusRuleXml.ruleDescriptionXml(rule, SB_NS);
        assertTrue(xml.contains("i:type=\"TrueFilter\""));
        assertTrue(xml.contains("<SqlExpression>1=1</SqlExpression>"));
        assertTrue(xml.contains("i:type=\"EmptyRuleAction\""));
    }
}
