package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.floci.az.services.deployments.Expression.FunctionCall;
import io.floci.az.services.deployments.Expression.IndexAccess;
import io.floci.az.services.deployments.Expression.Literal;
import io.floci.az.services.deployments.Expression.PropertyAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ExpressionParser: the ARM template language grammar")
class ExpressionParserTest {

    private static Literal text(String value) {
        return new Literal(TextNode.valueOf(value));
    }

    @Test
    void onlyABracketedStringThatIsNotEscapedIsAnExpression() {
        assertTrue(ExpressionParser.isExpression("[parameters('a')]"));
        assertFalse(ExpressionParser.isExpression("[[parameters('a')]"));
        assertFalse(ExpressionParser.isExpression("plain"));
        assertFalse(ExpressionParser.isExpression("[not closed"));
        assertFalse(ExpressionParser.isExpression(null));
    }

    @Test
    void anEscapedStringLosesItsFirstBracket() {
        assertEquals("[literal]", ExpressionParser.unescape("[[literal]"));
        assertEquals("plain", ExpressionParser.unescape("plain"));
    }

    @Test
    void aFunctionCallWithStringAndIntegerArguments() {
        assertEquals(new FunctionCall("concat", List.of(text("a"), new Literal(IntNode.valueOf(-12)))),
            ExpressionParser.parse("[concat('a', -12)]"));
    }

    @Test
    void doubledQuotesEscapeAQuoteInsideAStringLiteral() {
        assertEquals(new FunctionCall("string", List.of(text("it's"))),
            ExpressionParser.parse("[string('it''s')]"));
    }

    @Test
    void nestedCallsAndChainedAccessorsBuildTheTree() {
        Expression parsed = ExpressionParser.parse(
            "[reference(parameters('name')).primaryEndpoints['blob'].length]");

        PropertyAccess length = assertInstanceOf(PropertyAccess.class, parsed);
        assertEquals("length", length.property());
        IndexAccess blob = assertInstanceOf(IndexAccess.class, length.target());
        assertEquals(text("blob"), blob.index());
        PropertyAccess endpoints = assertInstanceOf(PropertyAccess.class, blob.target());
        assertEquals("primaryEndpoints", endpoints.property());
        assertEquals(new FunctionCall("reference",
                List.of(new FunctionCall("parameters", List.of(text("name"))))),
            endpoints.target());
    }

    @Test
    void whitespaceBetweenTokensIsAllowed() {
        assertEquals(new FunctionCall("add", List.of(new Literal(IntNode.valueOf(1)), new Literal(IntNode.valueOf(2)))),
            ExpressionParser.parse("[ add( 1 ,  2 ) ]"));
    }

    @Test
    void aCallWithoutArgumentsAndADottedUserFunctionName() {
        assertEquals(new FunctionCall("resourceGroup", List.of()), ExpressionParser.parse("[resourceGroup()]"));
        assertEquals(new FunctionCall("contoso.unique", List.of(text("x"))),
            ExpressionParser.parse("[contoso.unique('x')]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[concat('a'", "[concat('a)]", "[parameters]", "[1 2]", "[]", "[concat('a'),]",
        "[x[0]", "[.a]"})
    void malformedExpressionsAreInvalidTemplateErrors(String text) {
        TemplateException error = assertThrows(TemplateException.class, () -> ExpressionParser.parse(text));
        assertEquals(TemplateException.INVALID_TEMPLATE, error.code());
    }
}
