package io.floci.az.services.servicebus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Compiles a subscription's rule set into an ActiveMQ Artemis core filter (SQL92 selector)
 * applied to the subscription's MULTICAST queue, so filter evaluation happens inside the
 * broker at routing time.
 *
 * <p>Azure system properties map to the identifiers Artemis resolves on AMQP messages
 * ({@code AMQPMessage.getObjectProperty}):
 * <ul>
 *   <li>{@code CorrelationId} / {@code sys.CorrelationId} → {@code JMSCorrelationID} (AMQP correlation-id)</li>
 *   <li>{@code Label} / {@code sys.Label} / {@code sys.Subject} → {@code JMSType} (AMQP subject)</li>
 *   <li>{@code SessionId} / {@code sys.SessionId} → {@code JMSXGroupID} (AMQP group-id)</li>
 *   <li>user properties → AMQP application properties, referenced by name</li>
 * </ul>
 * System properties without a broker-side identifier (MessageId, To, ReplyTo,
 * ReplyToSessionId, ContentType) are rejected with {@link IllegalArgumentException}
 * so misrouting fails loudly at rule creation instead of silently at delivery.
 */
final class ServiceBusRuleSelector {

    /** Selector that never matches — used when a subscription has no (effective) rules. */
    static final String MATCH_NONE = "1=0";
    /** Empty Artemis filter — the queue receives every message routed to the address. */
    static final String MATCH_ALL = "";

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private ServiceBusRuleSelector() {}

    /**
     * Compiles the full rule set of one subscription. Rules combine as a logical OR
     * (Azure semantics for rules without actions). No rules → match nothing.
     */
    static String forRules(List<ServiceBusModels.RuleEntity> rules) {
        List<String> parts = new ArrayList<>();
        for (ServiceBusModels.RuleEntity rule : rules) {
            String selector = forRule(rule);
            if (MATCH_ALL.equals(selector)) {
                return MATCH_ALL;
            }
            if (!MATCH_NONE.equals(selector)) {
                parts.add(selector);
            }
        }
        if (parts.isEmpty()) {
            return MATCH_NONE;
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return "(" + String.join(") OR (", parts) + ")";
    }

    /** Compiles a single rule's filter to a selector fragment. */
    static String forRule(ServiceBusModels.RuleEntity rule) {
        return switch (rule.filterType()) {
            case "TrueFilter" -> MATCH_ALL;
            case "FalseFilter" -> MATCH_NONE;
            case "SqlFilter" -> translateSql(rule.sqlExpression());
            case "CorrelationFilter" -> compileCorrelation(rule);
            default -> throw new IllegalArgumentException("Unknown filter type: " + rule.filterType());
        };
    }

    private static String compileCorrelation(ServiceBusModels.RuleEntity rule) {
        rejectUnsupported("MessageId", rule.messageId());
        rejectUnsupported("To", rule.to());
        rejectUnsupported("ReplyTo", rule.replyTo());
        rejectUnsupported("ReplyToSessionId", rule.replyToSessionId());
        rejectUnsupported("ContentType", rule.contentType());

        List<String> conditions = new ArrayList<>();
        if (rule.correlationId() != null) {
            conditions.add("JMSCorrelationID = " + stringLiteral(rule.correlationId()));
        }
        if (rule.label() != null) {
            conditions.add("JMSType = " + stringLiteral(rule.label()));
        }
        if (rule.sessionId() != null) {
            conditions.add("JMSXGroupID = " + stringLiteral(rule.sessionId()));
        }
        for (Map.Entry<String, String> e : rule.correlationProperties().entrySet()) {
            String valueType = rule.correlationPropertyTypes().get(e.getKey());
            conditions.add(userProperty(e.getKey()) + " = " + typedLiteral(e.getValue(), valueType));
        }
        if (conditions.isEmpty()) {
            throw new IllegalArgumentException("CorrelationFilter must set at least one property");
        }
        return String.join(" AND ", conditions);
    }

    private static void rejectUnsupported(String field, String value) {
        if (value != null) {
            throw new IllegalArgumentException("CorrelationFilter on " + field
                    + " is not supported by the emulator (no broker-side AMQP mapping); "
                    + "filter on CorrelationId, Label, SessionId or application properties instead");
        }
    }

    private static String userProperty(String name) {
        if (!IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Property name '" + name
                    + "' cannot be used in a filter: it is not a valid selector identifier");
        }
        return name;
    }

    private static String stringLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static final Pattern INTEGER_LITERAL = Pattern.compile("[+-]?\\d+");
    private static final Pattern DECIMAL_LITERAL = Pattern.compile("[+-]?\\d*\\.?\\d+([eE][+-]?\\d+)?");

    /**
     * Emits a correlation-property value as a selector literal matching its declared
     * XML Schema type, so numeric and boolean application properties compare with
     * the correct selector type (JMS selectors never match across types).
     */
    private static String typedLiteral(String value, String xmlSchemaType) {
        if (xmlSchemaType == null) {
            return stringLiteral(value);
        }
        String trimmed = value.trim();
        switch (xmlSchemaType.toLowerCase(Locale.ROOT)) {
            case "int", "long", "short", "byte",
                 "integer", "unsignedint", "unsignedlong", "unsignedshort", "unsignedbyte" -> {
                if (INTEGER_LITERAL.matcher(trimmed).matches()) {
                    return trimmed;
                }
            }
            case "double", "float", "decimal" -> {
                if (DECIMAL_LITERAL.matcher(trimmed).matches()) {
                    return trimmed;
                }
            }
            case "boolean" -> {
                if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
                    return trimmed.toUpperCase(Locale.ROOT);
                }
            }
            default -> { }
        }
        return stringLiteral(value);
    }

    // ── SqlFilter translation ─────────────────────────────────────────────────

    /**
     * Translates an Azure SQL filter expression to Artemis selector syntax:
     * {@code sys.*} identifiers are mapped to broker identifiers, {@code user.} prefixes
     * and {@code [bracketed]} delimiters are stripped, and {@code EXISTS(p)} becomes
     * {@code (p IS NOT NULL)}. String literals pass through untouched.
     */
    static String translateSql(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("SqlFilter requires a SqlExpression");
        }
        StringBuilder out = new StringBuilder(expression.length());
        int i = 0;
        int n = expression.length();
        while (i < n) {
            char c = expression.charAt(i);
            if (c == '\'') {
                int end = endOfStringLiteral(expression, i);
                out.append(expression, i, end);
                i = end;
            } else if (c == '[') {
                int close = expression.indexOf(']', i);
                if (close < 0) {
                    throw new IllegalArgumentException("Unterminated [delimited] identifier in filter: " + expression);
                }
                i = appendIdentifier(out, expression, expression.substring(i + 1, close), close + 1);
            } else if (Character.isLetter(c) || c == '_' || c == '$') {
                int end = i;
                while (end < n && (Character.isLetterOrDigit(expression.charAt(end))
                        || expression.charAt(end) == '_' || expression.charAt(end) == '$'
                        || expression.charAt(end) == '.')) {
                    end++;
                }
                i = appendIdentifier(out, expression, expression.substring(i, end), end);
            } else if (c == '%') {
                throw new IllegalArgumentException(
                        "The modulo operator (%) is not supported in emulator SQL filters");
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Returns the index just past the closing quote, honouring {@code ''} escapes. */
    private static int endOfStringLiteral(String s, int start) {
        int i = start + 1;
        while (i < s.length()) {
            if (s.charAt(i) == '\'') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new IllegalArgumentException("Unterminated string literal in filter: " + s);
    }

    /**
     * Emits the translated form of one identifier/keyword token and returns the resume index.
     * {@code EXISTS} consumes its parenthesised argument as well.
     */
    private static int appendIdentifier(StringBuilder out, String expression, String token, int resume) {
        switch (token.toLowerCase(Locale.ROOT)) {
            case "and", "or", "not", "like", "in", "is", "null", "between", "escape", "true", "false" -> {
                out.append(token);
                return resume;
            }
            case "exists" -> {
                return appendExists(out, expression, resume);
            }
            default -> { }
        }
        out.append(mapProperty(token));
        return resume;
    }

    /** Resolves a {@code sys.}/{@code user.}/bare property token to its selector identifier. */
    private static String mapProperty(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.startsWith("sys.")) {
            return mapSystemProperty(token.substring(4));
        }
        if (lower.startsWith("user.")) {
            return userProperty(token.substring(5));
        }
        return userProperty(token);
    }

    private static String mapSystemProperty(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "correlationid" -> "JMSCorrelationID";
            case "label", "subject" -> "JMSType";
            case "sessionid" -> "JMSXGroupID";
            default -> throw new IllegalArgumentException("System property 'sys." + name
                    + "' is not supported by the emulator (no broker-side AMQP mapping); "
                    + "supported: sys.CorrelationId, sys.Label, sys.Subject, sys.SessionId");
        };
    }

    /** Rewrites {@code EXISTS ( ident )} to {@code (ident IS NOT NULL)}. */
    private static int appendExists(StringBuilder out, String expression, int i) {
        int n = expression.length();
        while (i < n && Character.isWhitespace(expression.charAt(i))) i++;
        if (i >= n || expression.charAt(i) != '(') {
            throw new IllegalArgumentException("EXISTS must be followed by (property): " + expression);
        }
        int close = expression.indexOf(')', i);
        if (close < 0) {
            throw new IllegalArgumentException("Unterminated EXISTS(...) in filter: " + expression);
        }
        String rawName = expression.substring(i + 1, close).trim();
        out.append("(").append(mapProperty(rawName)).append(" IS NOT NULL)");
        return close + 1;
    }
}
