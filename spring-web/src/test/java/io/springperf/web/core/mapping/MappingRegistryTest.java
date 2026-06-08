package io.springperf.web.core.mapping;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.match.*;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MappingRegistryTest {

    @Mock WebServerHttpRequest request;
    @Mock WebContext webContext;
    @Mock ApplicationContext applicationContext;
    @Mock Environment environment;

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

    /* ==================== mergeMatcherPair ==================== */

    @Test
    void mergeMatcherPair_httpMethod_mergesBothSets() {
        HttpMethodMatcher m = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET});
        HttpMethodMatcher c = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.POST});

        HttpMethodMatcher result = (HttpMethodMatcher) MappingRegistry.mergeMatcherPair(m, c);

        assertTrue(result.getHttpMethods().contains(HttpMethod.GET));
        assertTrue(result.getHttpMethods().contains(HttpMethod.POST));
        assertEquals(2, result.getHttpMethods().size());
    }

    @Test
    void mergeMatcherPair_httpMethod_withOverlap_deduplicates() {
        HttpMethodMatcher m = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET, HttpMethod.POST});
        HttpMethodMatcher c = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.POST, HttpMethod.PUT});

        HttpMethodMatcher result = (HttpMethodMatcher) MappingRegistry.mergeMatcherPair(m, c);

        assertEquals(3, result.getHttpMethods().size());
        assertTrue(result.getHttpMethods().contains(HttpMethod.GET));
        assertTrue(result.getHttpMethods().contains(HttpMethod.POST));
        assertTrue(result.getHttpMethods().contains(HttpMethod.PUT));
    }

    @Test
    void mergeMatcherPair_paramMatchers_mergesExpressions() {
        ParamOrHeaderMatcher m = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("a=1")));
        ParamOrHeaderMatcher c = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("b=2")));

        ParamOrHeaderMatcher result = (ParamOrHeaderMatcher) MappingRegistry.mergeMatcherPair(m, c);

        assertEquals(2, result.getExpressions().size());
        assertFalse(result.isHeader());
    }

    @Test
    void mergeMatcherPair_headerMatchers_mergesExpressions() {
        ParamOrHeaderMatcher m = new ParamOrHeaderMatcher(true,
                Collections.singletonList(NameValueExpressionSupport.build("X-Custom=myvalue")));
        ParamOrHeaderMatcher c = new ParamOrHeaderMatcher(true,
                Collections.singletonList(NameValueExpressionSupport.build("X-Other=other")));

        ParamOrHeaderMatcher result = (ParamOrHeaderMatcher) MappingRegistry.mergeMatcherPair(m, c);

        assertEquals(2, result.getExpressions().size());
        assertTrue(result.isHeader());
    }

    @Test
    void mergeMatcherPair_consumeMatchers_mergesExpressions() {
        ConsumeOrProduceMatcher m = new ConsumeOrProduceMatcher(false,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json")));
        ConsumeOrProduceMatcher c = new ConsumeOrProduceMatcher(false,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/xml")));

        ConsumeOrProduceMatcher result = (ConsumeOrProduceMatcher) MappingRegistry.mergeMatcherPair(m, c);

        assertEquals(2, result.getMediaTypeExpressions().size());
        assertFalse(result.isProduce());
    }

    @Test
    void mergeMatcherPair_produceMatchers_mergesExpressions() {
        ConsumeOrProduceMatcher m = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json")));
        ConsumeOrProduceMatcher c = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/xml")));

        ConsumeOrProduceMatcher result = (ConsumeOrProduceMatcher) MappingRegistry.mergeMatcherPair(m, c);

        assertEquals(2, result.getMediaTypeExpressions().size());
        assertTrue(result.isProduce());
    }

    @Test
    void mergeMatcherPair_unknownType_returnsMethodMatcher() {
        Matcher unknown = new Matcher() {
            @Override
            public boolean match(WebServerHttpRequest req, PathMappingContext ctx) {
                return true;
            }

            @Override
            public boolean isSameTypeMatcher(Matcher other) {
                return false;
            }

            @Override
            public boolean haveAmbiguous(Matcher other) {
                return false;
            }
        };
        HttpMethodMatcher m = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET});

        Matcher result = MappingRegistry.mergeMatcherPair(m, unknown);

        assertSame(m, result);
    }

    /* ==================== mergeMatchers ==================== */

    @Test
    void mergeMatchers_emptyClassMatchers_noChange() {
        MappingRegistry registry = new MappingRegistry();
        List<Matcher> methodMatchers = new ArrayList<>();
        methodMatchers.add(new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET}));

        registry.mergeMatchers(methodMatchers, Collections.<Matcher>emptyList());

        assertEquals(1, methodMatchers.size());
    }

    @Test
    void mergeMatchers_classHttpMethod_mergesWithExisting() {
        MappingRegistry registry = new MappingRegistry();
        List<Matcher> methodMatchers = new ArrayList<>();
        methodMatchers.add(new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET}));
        List<Matcher> classMatchers = Collections.<Matcher>singletonList(
                new HttpMethodMatcher(new HttpMethod[]{HttpMethod.POST}));

        registry.mergeMatchers(methodMatchers, classMatchers);

        assertEquals(1, methodMatchers.size());
        HttpMethodMatcher merged = (HttpMethodMatcher) methodMatchers.get(0);
        assertTrue(merged.getHttpMethods().contains(HttpMethod.GET));
        assertTrue(merged.getHttpMethods().contains(HttpMethod.POST));
    }

    @Test
    void mergeMatchers_classConsumeMatcher_noMethodConsume_appends() {
        MappingRegistry registry = new MappingRegistry();
        List<Matcher> methodMatchers = new ArrayList<>();
        methodMatchers.add(new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET}));
        List<Matcher> classMatchers = Collections.<Matcher>singletonList(
                new ConsumeOrProduceMatcher(false,
                        Collections.singletonList(MediaTypeExpressionSupport.build("application/json"))));

        registry.mergeMatchers(methodMatchers, classMatchers);

        assertEquals(2, methodMatchers.size());
        assertTrue(methodMatchers.get(1) instanceof ConsumeOrProduceMatcher);
    }

    @Test
    void mergeMatchers_classMultipleMatchers_mergedAndAppended() {
        MappingRegistry registry = new MappingRegistry();
        List<Matcher> methodMatchers = new ArrayList<>();
        methodMatchers.add(new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET}));
        List<Matcher> classMatchers = new ArrayList<>();
        classMatchers.add(new HttpMethodMatcher(new HttpMethod[]{HttpMethod.POST}));
        classMatchers.add(new ConsumeOrProduceMatcher(false,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json"))));

        registry.mergeMatchers(methodMatchers, classMatchers);

        assertEquals(2, methodMatchers.size());
        HttpMethodMatcher mergedMethod = (HttpMethodMatcher) methodMatchers.get(0);
        assertTrue(mergedMethod.getHttpMethods().contains(HttpMethod.GET));
        assertTrue(mergedMethod.getHttpMethods().contains(HttpMethod.POST));
        assertTrue(methodMatchers.get(1) instanceof ConsumeOrProduceMatcher);
    }

    /* ==================== initMethodMappingContext with class-level constraints ==================== */

    @Test
    void initMethodMappingContext_withClassLevelHttpMethod_mergedIntoContext() throws Exception {
        MappingRegistry registry = new MappingRegistry();
        TestController bean = new TestController();
        Method method = TestController.class.getMethod("getMethod");
        RequestMapping methodMapping = method.getAnnotation(RequestMapping.class);
        List<Matcher> classMatchers = Collections.singletonList(
                new HttpMethodMatcher(new HttpMethod[]{HttpMethod.POST}));

        registry.initMethodMappingContext(bean, method, new String[]{"/api"}, methodMapping, classMatchers);

        assertEquals(1, registry.getMappingContextList().size());
        PathMappingContext ctx = registry.getMappingContextList().get(0);

        boolean hasMergedMethods = Arrays.stream(ctx.getMatchers())
                .filter(m -> m instanceof HttpMethodMatcher)
                .anyMatch(m -> {
                    HttpMethodMatcher hm = (HttpMethodMatcher) m;
                    return hm.getHttpMethods().contains(HttpMethod.GET)
                            && hm.getHttpMethods().contains(HttpMethod.POST);
                });
        assertTrue(hasMergedMethods, "class-level POST should merge with method-level GET");
    }

    @Test
    void initMethodMappingContext_withClassLevelConsume_appended() throws Exception {
        MappingRegistry registry = new MappingRegistry();
        TestController bean = new TestController();
        Method method = TestController.class.getMethod("noMethod");
        RequestMapping methodMapping = method.getAnnotation(RequestMapping.class);
        List<Matcher> classMatchers = Collections.singletonList(
                new ConsumeOrProduceMatcher(false,
                        Collections.singletonList(MediaTypeExpressionSupport.build("application/json"))));

        registry.initMethodMappingContext(bean, method, new String[]{"/api"}, methodMapping, classMatchers);

        assertEquals(1, registry.getMappingContextList().size());
        PathMappingContext ctx = registry.getMappingContextList().get(0);

        long consumeMatchers = Arrays.stream(ctx.getMatchers())
                .filter(m -> m instanceof ConsumeOrProduceMatcher)
                .count();
        assertEquals(1, consumeMatchers, "class-level consume should be present in context");
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