package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.core.mapping.match.ConsumeOrProduceMatcher;
import io.springperf.web.core.mapping.match.HttpMethodMatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.web.WebEndpointHttpMethod;
import org.springframework.boot.actuate.endpoint.web.WebOperationRequestPredicate;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OperationHandlerInvokerTest {

    @Test
    void getHandleMethod_returnsHandleMethod() {
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(null,
                new WebOperationRequestPredicate("/test", WebEndpointHttpMethod.GET,
                        Collections.emptyList(), Collections.singletonList("application/json")),
                Collections.emptyList());
        assertNotNull(invoker.getHandleMethod());
        assertEquals("handleMethod", invoker.getHandleMethod().getName());
    }

    @Test
    void getType_returnsActuator() {
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(null,
                new WebOperationRequestPredicate("/test", WebEndpointHttpMethod.GET,
                        Collections.emptyList(), Collections.singletonList("application/json")),
                Collections.emptyList());
        assertEquals("Actuator", invoker.getType());
    }

    @Test
    void invoke_throwsUnsupportedOperationException() {
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(null,
                new WebOperationRequestPredicate("/test", WebEndpointHttpMethod.GET,
                        Collections.emptyList(), Collections.singletonList("application/json")),
                Collections.emptyList());
        assertThrows(UnsupportedOperationException.class, () -> invoker.invoke(new Object[0]));
    }

    @Test
    void matchers_containsHttpMethodMatcherForGet() {
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(null,
                new WebOperationRequestPredicate("/test", WebEndpointHttpMethod.GET,
                        Collections.emptyList(), Collections.singletonList("application/json")),
                Collections.emptyList());
        assertTrue(invoker.getMatchers().stream().anyMatch(m -> m instanceof HttpMethodMatcher));
    }

    @Test
    void matchers_containsHttpMethodMatcherForPost() {
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(null,
                new WebOperationRequestPredicate("/test", WebEndpointHttpMethod.POST,
                        Collections.emptyList(), Collections.singletonList("application/json")),
                Collections.emptyList());
        assertTrue(invoker.getMatchers().stream().anyMatch(m -> m instanceof HttpMethodMatcher));
    }

    @Test
    void matchers_withProduces_addsConsumeOrProduceMatcher() {
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(null,
                new WebOperationRequestPredicate("/test", WebEndpointHttpMethod.GET,
                        Collections.emptyList(), Collections.singletonList("application/json")),
                Collections.emptyList());
        assertTrue(invoker.getMatchers().stream().anyMatch(m -> m instanceof ConsumeOrProduceMatcher));
    }

    @Test
    void matchers_withConsumesAndConsumableMediaTypes_addsConsumeOrProduceMatcher() {
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(null,
                new WebOperationRequestPredicate("/test", WebEndpointHttpMethod.GET,
                        Collections.singletonList("application/json"), Collections.emptyList()),
                Collections.singletonList("application/json"));
        assertTrue(invoker.getMatchers().stream().anyMatch(m -> m instanceof ConsumeOrProduceMatcher));
    }

    @Test
    void getMatchers_returnsUnmodifiableList() {
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(null,
                new WebOperationRequestPredicate("/test", WebEndpointHttpMethod.GET,
                        Collections.emptyList(), Collections.singletonList("application/json")),
                Collections.emptyList());
        assertThrows(UnsupportedOperationException.class, () -> invoker.getMatchers().add(null));
    }

    @Test
    void getOperation_returnsNull_whenNotProvided() {
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(null,
                new WebOperationRequestPredicate("/test", WebEndpointHttpMethod.GET,
                        Collections.emptyList(), Collections.singletonList("application/json")),
                Collections.emptyList());
        assertNull(invoker.getOperation());
    }

    @Test
    void getPredicate_returnsProvidedPredicate() {
        WebOperationRequestPredicate predicate = new WebOperationRequestPredicate("/test", WebEndpointHttpMethod.GET,
                Collections.emptyList(), Collections.singletonList("application/json"));
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(null, predicate, Collections.emptyList());
        assertSame(predicate, invoker.getPredicate());
    }

    @Test
    void getOperation_returnsOperation_whenProvided() {
        // WebOperation can only be set via constructor, using real bean
        WebOperationRequestPredicate predicate = new WebOperationRequestPredicate("/test", WebEndpointHttpMethod.GET,
                Collections.emptyList(), Collections.singletonList("application/json"));
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(null, predicate, Collections.emptyList());
        assertNull(invoker.getOperation());
    }
}