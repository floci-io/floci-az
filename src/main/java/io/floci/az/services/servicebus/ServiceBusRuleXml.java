package io.floci.az.services.servicebus;

import io.floci.az.core.XmlBuilder;
import io.floci.az.core.XmlParser;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Parses and serialises Service Bus {@code <RuleDescription>} payloads
 * (rule create/update bodies and the optional {@code <DefaultRuleDescription>}
 * embedded in a subscription create body).
 *
 * <p>Filter subtypes are identified by the {@code i:type} (xsi:type) attribute on
 * {@code <Filter>}/{@code <Action>}, exactly as emitted by the Azure SDKs:
 * {@code TrueFilter}, {@code FalseFilter}, {@code SqlFilter}, {@code CorrelationFilter},
 * {@code EmptyRuleAction}, {@code SqlRuleAction}.
 */
final class ServiceBusRuleXml {

    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";

    private ServiceBusRuleXml() {}

    /**
     * Parses a rule create/update body ({@code <entry>} wrapping a {@code <RuleDescription>}).
     * The rule name from the request path is authoritative; a body without a
     * {@code <Filter>} yields a TrueFilter rule (Azure's default).
     */
    static ServiceBusModels.RuleEntity parseRule(String topicName, String subscriptionName,
                                                  String ruleName, String xml) {
        return parse(topicName, subscriptionName, ruleName, xml, "RuleDescription")
                .orElseGet(() -> ServiceBusModels.RuleEntity.trueFilter(topicName, subscriptionName, ruleName));
    }

    /**
     * Parses the optional {@code <DefaultRuleDescription>} of a subscription create body.
     * When present it replaces the implicit $Default TrueFilter rule; the rule keeps the
     * name given in the body, falling back to {@code $Default}.
     */
    static Optional<ServiceBusModels.RuleEntity> parseDefaultRule(String topicName,
                                                                   String subscriptionName, String xml) {
        return parse(topicName, subscriptionName, null, xml, "DefaultRuleDescription");
    }

    /**
     * @param forcedName authoritative rule name (path segment); when {@code null} the body's
     *                   {@code <Name>} wins, falling back to {@code $Default}
     */
    private static Optional<ServiceBusModels.RuleEntity> parse(String topicName, String subscriptionName,
                                                                String forcedName, String xml,
                                                                String containerElement) {
        if (xml == null || xml.isEmpty()) {
            return Optional.empty();
        }
        try {
            XMLStreamReader r = XmlParser.newStreamReader(xml);
            boolean sawContainer = false;
            boolean inContainer = false;
            boolean inFilter = false;
            boolean inAction = false;
            boolean inProperties = false;
            String filterType = null;
            String actionType = null;
            String name = null;
            String pendingKey = null;
            Map<String, String> fields = new LinkedHashMap<>();
            Map<String, String> properties = new LinkedHashMap<>();
            Map<String, String> propertyTypes = new LinkedHashMap<>();
            String actionSql = null;

            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();
                    if (containerElement.equals(local)) {
                        sawContainer = true;
                        inContainer = true;
                    } else if (inContainer && "Filter".equals(local)) {
                        inFilter = true;
                        filterType = typeAttribute(r);
                    } else if (inContainer && "Action".equals(local)) {
                        inAction = true;
                        actionType = typeAttribute(r);
                    } else if (inFilter && "Properties".equals(local)) {
                        inProperties = true;
                    } else if (inProperties && "Key".equals(local)) {
                        pendingKey = r.getElementText();
                    } else if (inProperties && "Value".equals(local)) {
                        if (pendingKey != null) {
                            String valueType = typeAttribute(r);
                            if (valueType != null && !"string".equalsIgnoreCase(valueType)) {
                                propertyTypes.put(pendingKey, valueType);
                            }
                            properties.put(pendingKey, r.getElementText());
                            pendingKey = null;
                        }
                    } else if (inProperties) {
                        // KeyValueOfstringanyType wrapper — descend without consuming
                    } else if (inFilter) {
                        String text = XmlParser.readLeafText(r);
                        if (text != null) {
                            fields.put(local, text);
                        }
                    } else if (inAction && "SqlExpression".equals(local)) {
                        actionSql = r.getElementText();
                    } else if (inContainer && "Name".equals(local)) {
                        name = r.getElementText();
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String local = r.getLocalName();
                    if (containerElement.equals(local)) {
                        inContainer = false;
                    } else if ("Filter".equals(local)) {
                        inFilter = false;
                    } else if ("Action".equals(local)) {
                        inAction = false;
                    } else if ("Properties".equals(local)) {
                        inProperties = false;
                    }
                }
            }
            r.close();

            if (!sawContainer) {
                return Optional.empty();
            }
            String resolvedType = resolveFilterType(filterType, fields, properties);
            String action = "SqlRuleAction".equals(actionType) ? actionSql : null;
            String resolvedName = forcedName != null ? forcedName
                    : (name != null && !name.isBlank() ? name : "$Default");
            return Optional.of(new ServiceBusModels.RuleEntity(
                    topicName, subscriptionName,
                    resolvedName,
                    resolvedType,
                    "SqlFilter".equals(resolvedType) ? fields.get("SqlExpression") : null,
                    fields.get("CorrelationId"),
                    fields.get("MessageId"),
                    fields.get("To"),
                    fields.get("ReplyTo"),
                    fields.get("Label"),
                    fields.get("SessionId"),
                    fields.get("ReplyToSessionId"),
                    fields.get("ContentType"),
                    Map.copyOf(properties),
                    Map.copyOf(propertyTypes),
                    action,
                    Instant.now()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Extracts the local part of the xsi:type attribute (e.g. {@code d2p1:SqlFilter} → {@code SqlFilter}). */
    private static String typeAttribute(XMLStreamReader r) {
        String value = r.getAttributeValue(XSI_NS, "type");
        if (value == null) {
            value = r.getAttributeValue(null, "type");
        }
        if (value == null) {
            return null;
        }
        int colon = value.indexOf(':');
        return colon < 0 ? value : value.substring(colon + 1);
    }

    /** Falls back to structural detection when the xsi:type attribute is missing. */
    private static String resolveFilterType(String declaredType, Map<String, String> fields,
                                             Map<String, String> properties) {
        if (declaredType != null && !declaredType.isBlank()) {
            return declaredType;
        }
        if (!properties.isEmpty()
                || fields.containsKey("CorrelationId") || fields.containsKey("Label")
                || fields.containsKey("MessageId") || fields.containsKey("To")
                || fields.containsKey("ReplyTo") || fields.containsKey("SessionId")
                || fields.containsKey("ReplyToSessionId") || fields.containsKey("ContentType")) {
            return "CorrelationFilter";
        }
        if (fields.containsKey("SqlExpression")) {
            return "SqlFilter";
        }
        return "TrueFilter";
    }

    /**
     * Builds the {@code <RuleDescription>} content element for ATOM responses.
     * Element order follows the Service Bus XSD: Filter, Action, CreatedAt, Name.
     */
    static String ruleDescriptionXml(ServiceBusModels.RuleEntity rule, String sbNamespace) {
        XmlBuilder xb = new XmlBuilder()
                .startAttr("RuleDescription",
                        "xmlns:i", XSI_NS,
                        "xmlns", sbNamespace);
        appendFilter(xb, rule);
        appendAction(xb, rule);
        xb.elem("CreatedAt", ServiceBusModels.ISO8601.format(rule.createdAt()))
          .elem("Name", rule.name());
        return xb.end("RuleDescription").build();
    }

    private static void appendFilter(XmlBuilder xb, ServiceBusModels.RuleEntity rule) {
        switch (rule.filterType()) {
            case "CorrelationFilter" -> {
                xb.startAttr("Filter", "i:type", "CorrelationFilter")
                  .elem("CorrelationId", rule.correlationId())
                  .elem("MessageId", rule.messageId())
                  .elem("To", rule.to())
                  .elem("ReplyTo", rule.replyTo())
                  .elem("Label", rule.label())
                  .elem("SessionId", rule.sessionId())
                  .elem("ReplyToSessionId", rule.replyToSessionId())
                  .elem("ContentType", rule.contentType());
                if (!rule.correlationProperties().isEmpty()) {
                    xb.start("Properties");
                    for (Map.Entry<String, String> e : rule.correlationProperties().entrySet()) {
                        String valueType = rule.correlationPropertyTypes()
                                .getOrDefault(e.getKey(), "string");
                        xb.start("KeyValueOfstringanyType")
                          .elem("Key", e.getKey())
                          .startAttr("Value",
                                  "xmlns:d6p1", "http://www.w3.org/2001/XMLSchema",
                                  "i:type", "d6p1:" + valueType)
                          .raw(XmlBuilder.escape(e.getValue()))
                          .end("Value")
                          .end("KeyValueOfstringanyType");
                    }
                    xb.end("Properties");
                }
                xb.end("Filter");
            }
            case "SqlFilter" -> xb.startAttr("Filter", "i:type", "SqlFilter")
                    .elem("SqlExpression", rule.sqlExpression())
                    .elem("CompatibilityLevel", "20")
                    .end("Filter");
            case "FalseFilter" -> xb.startAttr("Filter", "i:type", "FalseFilter")
                    .elem("SqlExpression", "1=0")
                    .elem("CompatibilityLevel", "20")
                    .end("Filter");
            default -> xb.startAttr("Filter", "i:type", "TrueFilter")
                    .elem("SqlExpression", "1=1")
                    .elem("CompatibilityLevel", "20")
                    .end("Filter");
        }
    }

    private static void appendAction(XmlBuilder xb, ServiceBusModels.RuleEntity rule) {
        if (rule.actionSqlExpression() == null || rule.actionSqlExpression().isBlank()) {
            xb.selfClose("Action", "i:type", "EmptyRuleAction");
        } else {
            xb.startAttr("Action", "i:type", "SqlRuleAction")
              .elem("SqlExpression", rule.actionSqlExpression())
              .elem("CompatibilityLevel", "20")
              .end("Action");
        }
    }
}
