package io.springperf.web.core.mapping;

import io.springperf.web.core.mapping.match.HttpMethodMatcher;
import io.springperf.web.core.mapping.match.Matcher;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MappingRegistryTest {

    @Mock WebServerHttpRequest request;

    /* ==================== registerMapping ==================== */

    @Test
    void registerMapping_addsToMappingList() {
        MappingRegistry registry = new MappingRegistry();
        PathMappingContext ctx = createPathMappingContext("/test");

        registry.registerMapping(ctx);

        List<PathMappingContext> list = registry.getMappingContextList();
        assertEquals(1, list.size());
        assertSame(ctx, list.get(0));
    }

    @Test
    void registerMapping_multipleMappings_appendedInOrder() {
        MappingRegistry registry = new MappingRegistry();
        PathMappingContext ctx1 = createPathMappingContext("/a");
        PathMappingContext ctx2 = createPathMappingContext("/b");
        PathMappingContext ctx3 = createPathMappingContext("/c");

        registry.registerMapping(ctx1);
        registry.registerMapping(ctx2);
        registry.registerMapping(ctx3);

        assertEquals(3, registry.getMappingContextList().size());
        assertSame(ctx1, registry.getMappingContextList().get(0));
        assertSame(ctx3, registry.getMappingContextList().get(2));
    }

    /* ==================== getMappingContextList ==================== */

    @Test
    void getMappingContextList_emptyByDefault() {
        MappingRegistry registry = new MappingRegistry();

        assertTrue(registry.getMappingContextList().isEmpty());
    }

    @Test
    void getMappingContextList_returnsDirectReference() {
        MappingRegistry registry = new MappingRegistry();
        PathMappingContext ctx = createPathMappingContext("/test");

        registry.registerMapping(ctx);

        assertSame(registry.getMappingContextList(), registry.getMappingContextList());
    }

    /* ==================== isMethodMismatch ==================== */

    @Test
    void isMethodMismatch_nullContexts_returnsFalse() throws Exception {
        boolean result = invokeIsMethodMismatch(null, request);

        assertFalse(result);
    }

    @Test
    void isMethodMismatch_emptyContexts_returnsFalse() throws Exception {
        boolean result = invokeIsMethodMismatch(new PathMappingContext[0], request);

        assertFalse(result);
    }

    @Test
    void isMethodMismatch_contextWithoutMethodMatcher_returnsFalse() throws Exception {
        PathMappingContext ctx = createPathMappingContext("/test", Collections.<Matcher>emptyList());
        PathMappingContext[] contexts = new PathMappingContext[]{ctx};

        boolean result = invokeIsMethodMismatch(contexts, request);

        assertFalse(result);
    }

    @Test
    void isMethodMismatch_methodMatches_returnsFalse() throws Exception {
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        HttpMethodMatcher matcher = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET});
        PathMappingContext ctx = createPathMappingContext("/test", Collections.<Matcher>singletonList(matcher));
        PathMappingContext[] contexts = new PathMappingContext[]{ctx};

        boolean result = invokeIsMethodMismatch(contexts, request);

        assertFalse(result);
    }

    @Test
    void isMethodMismatch_methodDoesNotMatch_returnsTrue() throws Exception {
        when(request.getMethod()).thenReturn(HttpMethod.POST);
        HttpMethodMatcher matcher = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET});
        PathMappingContext ctx = createPathMappingContext("/test", Collections.<Matcher>singletonList(matcher));
        PathMappingContext[] contexts = new PathMappingContext[]{ctx};

        boolean result = invokeIsMethodMismatch(contexts, request);

        assertTrue(result);
    }

    @Test
    void isMethodMismatch_oneContextMatches_returnsFalse() throws Exception {
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        HttpMethodMatcher getMatcher = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET});
        HttpMethodMatcher postMatcher = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.POST});
        PathMappingContext ctx1 = createPathMappingContext("/test", Collections.<Matcher>singletonList(getMatcher));
        PathMappingContext ctx2 = createPathMappingContext("/test", Collections.<Matcher>singletonList(postMatcher));
        PathMappingContext[] contexts = new PathMappingContext[]{ctx1, ctx2};

        boolean result = invokeIsMethodMismatch(contexts, request);

        assertFalse(result);
    }

    /* ==================== initMatcher ==================== */

    @Test
    void initMatcher_withHttpMethods_createsMethodMatcher() throws Exception {
        Method method = TestController.class.getMethod("getMethod");
        RequestMapping annotation = method.getAnnotation(RequestMapping.class);
        assertNotNull(annotation);

        MappingRegistry registry = new MappingRegistry();
        List<Matcher> matchers = registry.initMatcher(annotation);

        assertFalse(matchers.isEmpty());
        assertTrue(matchers.stream().anyMatch(m -> m instanceof HttpMethodMatcher));
    }

    @Test
    void initMatcher_withoutHttpMethods_returnsEmptyList() throws Exception {
        Method method = TestController.class.getMethod("noMethod");
        RequestMapping annotation = method.getAnnotation(RequestMapping.class);
        assertNotNull(annotation);

        MappingRegistry registry = new MappingRegistry();
        List<Matcher> matchers = registry.initMatcher(annotation);

        assertTrue(matchers.isEmpty());
    }

    /* ==================== helpers ==================== */

    private static PathMappingContext createPathMappingContext(String pathRule) {
        try {
            HandlerMethod hm = new HandlerMethod(new Object(), Object.class.getDeclaredMethod("toString"));
            return new PathMappingContext(hm, Collections.<Matcher>emptyList(), pathRule);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private static PathMappingContext createPathMappingContext(String pathRule, List<Matcher> matchers) {
        try {
            HandlerMethod hm = new HandlerMethod(new Object(), Object.class.getDeclaredMethod("toString"));
            return new PathMappingContext(hm, matchers, pathRule);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean invokeIsMethodMismatch(PathMappingContext[] contexts, WebServerHttpRequest req) throws Exception {
        Method method = MappingRegistry.class.getDeclaredMethod("isMethodMismatch", PathMappingContext[].class, WebServerHttpRequest.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, contexts, req);
    }

    private static class TestController {
        @RequestMapping(method = RequestMethod.GET)
        public void getMethod() {}

        @RequestMapping
        public void noMethod() {}
    }
}