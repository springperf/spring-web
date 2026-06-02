package io.springperf.web.core.mapping.match;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NameValueExpressionSupportTest {

    @Test
    void build_simpleName_parsesCorrectly() {
        NameValueExpressionSupport expr = NameValueExpressionSupport.build("name");
        assertEquals("name", expr.getName());
        assertNull(expr.getValue());
        assertFalse(expr.isNegated());
    }

    @Test
    void build_nameWithValue_parsesCorrectly() {
        NameValueExpressionSupport expr = NameValueExpressionSupport.build("name=value");
        assertEquals("name", expr.getName());
        assertEquals("value", expr.getValue());
        assertFalse(expr.isNegated());
    }

    @Test
    void build_negatedName_parsesCorrectly() {
        NameValueExpressionSupport expr = NameValueExpressionSupport.build("!name");
        assertEquals("name", expr.getName());
        assertNull(expr.getValue());
        assertTrue(expr.isNegated());
    }

    @Test
    void build_negatedNameWithValue_parsesCorrectly() {
        NameValueExpressionSupport expr = NameValueExpressionSupport.build("name!=value");
        assertEquals("name", expr.getName());
        assertEquals("value", expr.getValue());
        assertTrue(expr.isNegated());
    }

    @Test
    void build_emptyValue_parsesCorrectly() {
        NameValueExpressionSupport expr = NameValueExpressionSupport.build("name=");
        assertEquals("name", expr.getName());
        assertEquals("", expr.getValue());
        assertFalse(expr.isNegated());
    }

    @Test
    void build_valueWithEqualsSign_parsesCorrectly() {
        NameValueExpressionSupport expr = NameValueExpressionSupport.build("name=a=b");
        assertEquals("name", expr.getName());
        assertEquals("a=b", expr.getValue());
    }

    @Test
    void equals_sameValues_returnsTrue() {
        NameValueExpressionSupport a = NameValueExpressionSupport.build("name=value");
        NameValueExpressionSupport b = NameValueExpressionSupport.build("name=value");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentValues_returnsFalse() {
        NameValueExpressionSupport a = NameValueExpressionSupport.build("name=value1");
        NameValueExpressionSupport b = NameValueExpressionSupport.build("name=value2");
        assertNotEquals(a, b);
    }

    @Test
    void equals_differentNegation_returnsFalse() {
        NameValueExpressionSupport a = NameValueExpressionSupport.build("name=value");
        NameValueExpressionSupport b = NameValueExpressionSupport.build("name!=value");
        assertNotEquals(a, b);
    }

    @Test
    void toString_nameOnly_returnsName() {
        NameValueExpressionSupport expr = NameValueExpressionSupport.build("name");
        assertEquals("name", expr.toString());
    }

    @Test
    void toString_nameWithValue_returnsNameEqualsValue() {
        NameValueExpressionSupport expr = NameValueExpressionSupport.build("name=value");
        assertEquals("name=value", expr.toString());
    }

    @Test
    void toString_negatedName_returnsExclamationName() {
        NameValueExpressionSupport expr = NameValueExpressionSupport.build("!name");
        assertEquals("!name", expr.toString());
    }

    @Test
    void toString_negatedNameWithValue_returnsNameExclamationEqualsValue() {
        NameValueExpressionSupport expr = NameValueExpressionSupport.build("name!=value");
        assertEquals("name!=value", expr.toString());
    }
}
