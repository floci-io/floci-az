package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A parsed ARM template language expression: the inside of a {@code "[...]"} string, as {@link ExpressionParser}
 * builds it and {@link ExpressionEvaluator} evaluates it.
 */
public sealed interface Expression {

    /** A string or integer literal. */
    record Literal(JsonNode value) implements Expression {
    }

    /**
     * A function call. {@code name} keeps the template's spelling ({@code listKeys}); functions are looked up
     * case-insensitively.
     */
    record FunctionCall(String name, List<Expression> arguments) implements Expression {
        public FunctionCall {
            arguments = List.copyOf(arguments);
        }
    }

    /** {@code target.property}: a property of an object, looked up case-insensitively. */
    record PropertyAccess(Expression target, String property) implements Expression {
    }

    /** {@code target[index]}: an array element by integer index, or an object property by string key. */
    record IndexAccess(Expression target, Expression index) implements Expression {
    }
}
