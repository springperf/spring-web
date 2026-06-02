package io.springperf.web.core.invoker;

import io.springperf.web.core.mapping.match.Matcher;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CustomInvokerTest {

    @Test
    void getHandleMethod_returnsCorrectMethod() throws Exception {
        Method expected = getClass().getMethod("targetMethod");
        CustomInvoker invoker = new TestCustomInvoker(expected, "test-type");

        assertSame(expected, invoker.getHandleMethod());
    }

    @Test
    void getType_returnsConfiguredType() throws Exception {
        Method method = getClass().getMethod("targetMethod");
        CustomInvoker invoker = new TestCustomInvoker(method, "my-type");

        assertEquals("my-type", invoker.getType());
    }

    @Test
    void getMatchers_defaultReturnsEmptyList() throws Exception {
        Method method = getClass().getMethod("targetMethod");
        CustomInvoker invoker = new TestCustomInvoker(method, "test");

        List<Matcher> matchers = invoker.getMatchers();
        assertNotNull(matchers);
        assertTrue(matchers.isEmpty());
    }

    @Test
    void invoke_callsTargetMethod() throws Throwable {
        Method method = getClass().getMethod("targetMethod");
        CustomInvoker invoker = new TestCustomInvoker(method, "test");

        Object result = invoker.invoke(new Object[0]);

        assertEquals("target-called", result);
    }

    @SuppressWarnings("unused")
    public static String targetMethod() {
        return "target-called";
    }

    static class TestCustomInvoker implements CustomInvoker {
        private final Method method;
        private final String type;

        TestCustomInvoker(Method method, String type) {
            this.method = method;
            this.type = type;
        }

        @Override
        public Method getHandleMethod() {
            return method;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public Object invoke(Object[] args) throws Throwable {
            return method.invoke(null, args);
        }
    }
}