package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.floci.az.services.deployments.Expression.FunctionCall;
import io.floci.az.services.deployments.Expression.IndexAccess;
import io.floci.az.services.deployments.Expression.Literal;
import io.floci.az.services.deployments.Expression.PropertyAccess;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the ARM template language: a JSON string that starts with {@code [} and ends with {@code ]} is an
 * expression, and one that starts with {@code [[} is a literal string whose first bracket is an escape.
 *
 * <p>Grammar: a primary is a single-quoted string literal ({@code ''} escapes a quote), an integer (optionally
 * negative) or a function call {@code name(arg, ...)}; any primary can be followed by {@code .property} and
 * {@code [expression]} accessors. {@code true}, {@code false} and {@code null} are functions, so they are written
 * {@code true()}. A dotted name before an argument list ({@code ns.member(...)}) is a user-defined function call.
 */
public final class ExpressionParser {

    private final String text;
    private int position;

    private ExpressionParser(String text) {
        this.text = text;
    }

    /** True for a string the template language evaluates: {@code [...]}, but not the escaped {@code [[...]}. */
    public static boolean isExpression(String text) {
        return text != null && text.length() >= 2 && text.startsWith("[") && text.endsWith("]")
            && !text.startsWith("[[");
    }

    /** The literal value of an escaped string: {@code [[...} becomes {@code [...}; anything else is unchanged. */
    public static String unescape(String text) {
        return text != null && text.startsWith("[[") ? text.substring(1) : text;
    }

    /** Parses an expression string, outer brackets included. */
    public static Expression parse(String text) {
        if (!isExpression(text)) {
            throw TemplateException.invalid("The template language expression '" + text
                + "' is not valid: an expression must start with '[' and end with ']'.");
        }
        ExpressionParser parser = new ExpressionParser(text.substring(1, text.length() - 1));
        parser.skipWhitespace();
        Expression expression = parser.expression();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("unexpected '" + parser.peek() + "'");
        }
        return expression;
    }

    private Expression expression() {
        Expression primary = primary();
        while (true) {
            skipWhitespace();
            if (consume('.')) {
                skipWhitespace();
                primary = new PropertyAccess(primary, identifier());
            } else if (consume('[')) {
                skipWhitespace();
                Expression index = expression();
                skipWhitespace();
                expect(']');
                primary = new IndexAccess(primary, index);
            } else {
                return primary;
            }
        }
    }

    private Expression primary() {
        if (atEnd()) {
            throw error("an expression is expected");
        }
        char next = peek();
        if (next == '\'') {
            return new Literal(JsonNodeFactory.instance.textNode(stringLiteral()));
        }
        if (next == '-' || Character.isDigit(next)) {
            return new Literal(TemplateValues.number(integerLiteral()));
        }
        if (isIdentifierStart(next)) {
            return functionCall();
        }
        throw error("unexpected '" + next + "'");
    }

    private Expression functionCall() {
        StringBuilder name = new StringBuilder(identifier());
        skipWhitespace();
        while (consume('.')) {
            skipWhitespace();
            name.append('.').append(identifier());
            skipWhitespace();
        }
        if (!consume('(')) {
            throw error("'(' is expected after the function name '" + name + "'");
        }
        List<Expression> arguments = new ArrayList<>();
        skipWhitespace();
        if (!consume(')')) {
            do {
                skipWhitespace();
                arguments.add(expression());
                skipWhitespace();
            } while (consume(','));
            expect(')');
        }
        return new FunctionCall(name.toString(), arguments);
    }

    private String stringLiteral() {
        expect('\'');
        StringBuilder value = new StringBuilder();
        while (true) {
            if (atEnd()) {
                throw error("the string literal is not terminated");
            }
            char next = text.charAt(position++);
            if (next == '\'') {
                if (!atEnd() && peek() == '\'') {
                    position++;
                    value.append('\'');
                } else {
                    return value.toString();
                }
            } else {
                value.append(next);
            }
        }
    }

    private long integerLiteral() {
        int start = position;
        consume('-');
        if (atEnd() || !Character.isDigit(peek())) {
            throw error("a digit is expected");
        }
        while (!atEnd() && Character.isDigit(peek())) {
            position++;
        }
        try {
            return Long.parseLong(text.substring(start, position));
        } catch (NumberFormatException e) {
            throw error("the integer '" + text.substring(start, position) + "' is out of range");
        }
    }

    private String identifier() {
        if (atEnd() || !isIdentifierStart(peek())) {
            throw error("a name is expected");
        }
        int start = position;
        while (!atEnd() && isIdentifierPart(peek())) {
            position++;
        }
        return text.substring(start, position);
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private void skipWhitespace() {
        while (!atEnd() && Character.isWhitespace(peek())) {
            position++;
        }
    }

    private boolean consume(char expected) {
        if (!atEnd() && peek() == expected) {
            position++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!consume(expected)) {
            throw error("'" + expected + "' is expected");
        }
    }

    private boolean atEnd() {
        return position >= text.length();
    }

    private char peek() {
        return text.charAt(position);
    }

    private TemplateException error(String reason) {
        return TemplateException.invalid("The template language expression '[" + text + "]' is not valid: "
            + reason + " at position " + position + ".");
    }
}
