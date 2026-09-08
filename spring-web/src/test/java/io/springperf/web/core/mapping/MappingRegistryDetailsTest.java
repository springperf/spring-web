package io.springperf.web.core.mapping;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.match.ConsumeOrProduceMatcher;
import io.springperf.web.core.mapping.match.HttpMethodMatcher;
import io.springperf.web.core.mapping.match.Matcher;
import io.springperf.web.core.mapping.match.MediaTypeExpressionSupport;
import io.springperf.web.core.mapping.match.ParamOrHeaderMatcher;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 补充 MappingRegistry 覆盖率：类级别 path 属性、params/headers/consumes/produces
 * matcher 构建、Phase3 路由优化分流，以及 mapping()/doMapping() 的匹配/405/未全匹配/404 分支。
 */
@ExtendWith(MockitoExtension.class)
class MappingRegistryDetailsTest {

    @Mock WebContext webContext;
    @Mock ApplicationContext applicationContext;
    @Mock Environment environment;

    private RequestContext reqCtx;

    private WebServerHttpRequest mockRequest(String path, HttpMethod method) {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        reqCtx = mock(RequestContext.class);
        Map<RequestAttribute<?>, Object> fastAttrs = new HashMap<>();
        lenient().when(req.getRequestContext()).thenReturn(reqCtx);
        lenient().when(req.getPath()).thenReturn(path);
        lenient().when(req.getMethod()).thenReturn(method);
        lenient().when(req.getHeaders()).thenReturn(new org.springframework.http.HttpHeaders());
        lenient().when(reqCtx.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> fastAttrs.get(inv.getArgument(0)));
        lenient().doAnswer(inv -> {
            fastAttrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(reqCtx).setAttribute(any(RequestAttribute.class), any());
        return req;
    }

    /* ==================== 类级别 @RequestMapping(path = ...) ==================== */

    @Test
    void initComponentPhase1_classLevelPathAttribute_usedAsPrefix() {
        when(webContext.getCtx()).thenReturn(applicationContext);
        when(applicationContext.getEnvironment()).thenReturn(environment);
        when(environment.resolvePlaceholders(anyString())).thenAnswer(inv -> inv.getArgument(0));
        MappingRegistry registry = new MappingRegistry();
        when(applicationContext.getBeansWithAnnotation(Controller.class))
                .thenReturn(Collections.singletonMap("pathCtrl", new PathBasedController()));
        registry.initWithWebContext(webContext);

        registry.initComponentPhase1();

        List<PathMappingContext> mappings = registry.getMappingContextList();
        assertEquals(1, mappings.size());
        // 类级 path="/class-base" + 方法级 "/m" → "/class-base/m"
        assertEquals("/class-base/m", mappings.get(0).getPathRule());
        assertEquals(PathBasedController.class, mappings.get(0).getMethod().getDeclaringClass());
    }

    @Controller
    @RequestMapping(path = "/class-base")
    static class PathBasedController {
        @RequestMapping("/m")
        @SuppressWarnings("unused")
        public void m() {}
    }

    /* ==================== initMatcher: params/headers/consumes/produces ==================== */

    @Test
    void initMatcher_allExpressionAttributes_createsFourMatchers() throws Exception {
        MappingRegistry registry = new MappingRegistry();
        Method method = FullConditionController.class.getMethod("doIt");
        RequestMapping annotation = method.getAnnotation(RequestMapping.class);

        List<Matcher> matchers = registry.initMatcher(annotation);

        assertEquals(4, matchers.size());
        assertEquals(1, countOf(matchers, ParamOrHeaderMatcher.class, false), "params → ParamOrHeaderMatcher(header=false)");
        assertEquals(1, countOf(matchers, ParamOrHeaderMatcher.class, true), "headers → ParamOrHeaderMatcher(header=true)");
        assertEquals(1, countOf(matchers, ConsumeOrProduceMatcher.class, false), "consumes → ConsumeOrProduceMatcher(produce=false)");
        assertEquals(1, countOf(matchers, ConsumeOrProduceMatcher.class, true), "produces → ConsumeOrProduceMatcher(produce=true)");
    }

    @Controller
    static class FullConditionController {
        @RequestMapping(path = "/full",
                params = "mode=fast",
                headers = "X-Trace=1",
                consumes = "application/json",
                produces = "application/json")
        @SuppressWarnings("unused")
        public void doIt() {}
    }

    private static long countOf(List<Matcher> matchers, Class<?> type, boolean flag) {
        return matchers.stream()
                .filter(m -> m.getClass().equals(type))
                .filter(m -> {
                    if (m instanceof ParamOrHeaderMatcher) {
                        return ((ParamOrHeaderMatcher) m).isHeader() == flag;
                    }
                    if (m instanceof ConsumeOrProduceMatcher) {
                        return ((ConsumeOrProduceMatcher) m).isProduce() == flag;
                    }
                    return false;
                })
                .count();
    }

    /* ==================== initComponentPhase3: optimizeMapping 分流 ==================== */

    @Test
    void initComponentPhase3_buildsOptimizersForEachPathKind() {
        MappingRegistry registry = new MappingRegistry();
        // 简单 URL → FullPathRouterOptimizer；** → fullWildcard；单段 * → simpleWildcard
        registry.registerMapping(createPathMappingContext("/simple", Collections.emptyList()));
        registry.registerMapping(createPathMappingContext("/files/**", Collections.emptyList()));
        registry.registerMapping(createPathMappingContext("/api/*/info", Collections.emptyList()));

        registry.initComponentPhase3();

        // Phase3 不抛异常且 mapping 保留（optimizers 占用后不删除 mappingContextList）
        assertEquals(3, registry.getMappingContextList().size());
        // 简单 URL 已可被优化后的路由命中
        WebServerHttpRequest req = mockRequest("/simple", HttpMethod.GET);
        MappingResult result = registry.mapping(req);
        assertTrue(result.isMatched());
        assertSame(registry.getMappingContextList().get(0), result.getMatchedContext());
    }

    /* ==================== doMapping: 匹配 / 405 / 非方法不匹配 / 404 ==================== */

    @Test
    void mapping_simplePathMatched_returnsMatched() {
        MappingRegistry registry = new MappingRegistry();
        registry.registerMapping(createPathMappingContext("/simple", Collections.emptyList()));
        registry.initComponentPhase3();

        WebServerHttpRequest req = mockRequest("/simple", HttpMethod.GET);
        MappingResult result = registry.mapping(req);

        assertTrue(result.isMatched());
        assertNotNull(result.getMatchedContext());
        assertEquals("/simple", result.getMatchedContext().getPathRule());
    }

    @Test
    void mapping_methodMismatch_returnsPathMatchedWith405Flag() {
        MappingRegistry registry = new MappingRegistry();
        registry.registerMapping(createPathMappingContext("/only-get",
                Collections.singletonList(new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET}))));
        registry.initComponentPhase3();

        WebServerHttpRequest req = mockRequest("/only-get", HttpMethod.POST);
        MappingResult result = registry.mapping(req);

        assertFalse(result.isMatched());
        assertTrue(result.isPathMatched());
        assertTrue(result.isMethodMismatch());
    }

    @Test
    void mapping_nonMethodMismatch_returnsPathMatchedWithout405Flag() {
        MappingRegistry registry = new MappingRegistry();
        // 路径命中但条件不满足（无 HttpMethodMatcher → 非方法失败），继续后续 optimizer 后返回 pathMatched
        registry.registerMapping(createPathMappingContext("/need-json",
                Collections.singletonList(new ConsumeOrProduceMatcher(false,
                        Collections.singletonList(MediaTypeExpressionSupport.build("application/json"))))));
        registry.initComponentPhase3();

        WebServerHttpRequest req = mockRequest("/need-json", HttpMethod.GET);
        MappingResult result = registry.mapping(req);

        assertFalse(result.isMatched());
        assertTrue(result.isPathMatched());
        assertFalse(result.isMethodMismatch());
    }

    @Test
    void mapping_unknownPath_returnsNotFound() {
        MappingRegistry registry = new MappingRegistry();
        registry.registerMapping(createPathMappingContext("/simple", Collections.emptyList()));
        registry.initComponentPhase3();

        WebServerHttpRequest req = mockRequest("/unknown", HttpMethod.GET);
        MappingResult result = registry.mapping(req);

        assertFalse(result.isMatched());
        assertFalse(result.isPathMatched());
        assertNull(result.getMatchedContext());
    }

    /* ==================== helpers ==================== */

    private static PathMappingContext createPathMappingContext(String pathRule, List<Matcher> matchers) {
        try {
            HandlerMethod hm = new HandlerMethod(new Object(), Object.class.getDeclaredMethod("toString"));
            return new PathMappingContext(hm, matchers, pathRule);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}