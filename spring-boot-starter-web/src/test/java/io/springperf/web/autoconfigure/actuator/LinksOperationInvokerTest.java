package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.core.mapping.match.HttpMethodMatcher;
import io.springperf.web.core.mapping.match.Matcher;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LinksOperationInvokerTest {

    private final LinksOperationInvoker invoker = new LinksOperationInvoker();

    @Test
    void getHandleMethod_returnsHandleMethod() {
        assertNotNull(invoker.getHandleMethod());
        assertEquals("handleMethod", invoker.getHandleMethod().getName());
    }

    @Test
    void getMatchers_containsHttpGetMatcher() {
        List<Matcher> matchers = invoker.getMatchers();
        assertEquals(1, matchers.size());
        assertInstanceOf(HttpMethodMatcher.class, matchers.get(0));
    }

    @Test
    void getType_returnsActuatorLinks() {
        assertEquals("ActuatorLinks", invoker.getType());
    }

    @Test
    void invoke_throwsUnsupportedOperationException() {
        assertThrows(UnsupportedOperationException.class, () -> invoker.invoke(new Object[0]));
    }
}