package dev.rosewood.rosechat.placeholder.condition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OperatorTest {

    @Test
    void equalityAndContainsAreCaseInsensitive() {
        assertTrue(Operator.EQUALS.evaluate("Hello", "hello"));
        assertFalse(Operator.NOT_EQUALS.evaluate("Hello", "hello"));
        assertTrue(Operator.CONTAINS.evaluate("Hello World", "WORLD"));
        assertFalse(Operator.CONTAINS.evaluate("Hello", "xyz"));
    }

    @Test
    void numericComparisonsRespectBoundaries() {
        assertTrue(Operator.LESS_THAN.evaluate("1.5", "2"));
        assertFalse(Operator.LESS_THAN.evaluate("2", "2"));
        assertTrue(Operator.LESS_THAN_OR_EQUALS.evaluate("2", "2"));
        assertTrue(Operator.GREATER_THAN.evaluate("3", "2"));
        assertFalse(Operator.GREATER_THAN.evaluate("2", "2"));
        assertTrue(Operator.GREATER_THAN_OR_EQUALS.evaluate("2", "2"));
    }

    @Test
    void malformedInputsFailClosedInsteadOfThrowing() {
        assertFalse(Operator.LESS_THAN.evaluate("nope", "2"));
        assertFalse(Operator.GREATER_THAN.evaluate("2", "nope"));
        assertFalse(Operator.EQUALS.evaluate(null, "x"));
        assertFalse(Operator.CONTAINS.evaluate("x", null));
    }

    @Test
    void symbolsRemainStableForConfigParsing() {
        assertTrue(java.util.Map.of(
                "!=", Operator.NOT_EQUALS,
                "<=", Operator.LESS_THAN_OR_EQUALS,
                ">=", Operator.GREATER_THAN_OR_EQUALS,
                "=", Operator.EQUALS,
                "<", Operator.LESS_THAN,
                ">", Operator.GREATER_THAN,
                "^", Operator.CONTAINS
        ).entrySet().stream().allMatch(entry -> entry.getKey().equals(entry.getValue().getSymbol())));
    }
}
