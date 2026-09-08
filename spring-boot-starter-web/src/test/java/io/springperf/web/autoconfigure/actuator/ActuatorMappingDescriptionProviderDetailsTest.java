package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.match.ConsumeOrProduceMatcher;
import io.springperf.web.core.mapping.match.HttpMethodMatcher;
import io.springperf.web.core.mapping.match.MediaTypeExpressionSupport;
import io.springperf.web.core.mapping.match.NameValueExpressionSupport;
import io.springperf.web.core.mapping.match.ParamOrHeaderMatcher;
import io.springperf.web.core.resource.ResourceHandlerRegistration;
import io.springperf.web.core.resource.ResourceRequestHandler;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.web.method.HandlerMethod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验证 {@link ActuatorMappingDescriptionProvider} 的完整描述逻辑�? * 资源路由、handlerMethod 描述、predicate/conditions 构建（各 matcher 类型）�? */
@SuppressWarnings("unchecked")
class ActuatorMappingDescriptionProviderDetailsTest {

    static class TestController {
        public String hello(String name) { return name; }
    }

    private PathMappingContext controllerRoute() throws Exception {
        HandlerMethod hm = new HandlerMethod(new TestController(),
                TestController.class.getMethod("hello", String.class));
        List<io.springperf.web.core.mapping.match.Matcher> matchers = new ArrayList<>();
        matchers.add(new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET, HttpMethod.POST}));
        matchers.add(new ConsumeOrProduceMatcher(true,
                java.util.Arrays.asList(MediaTypeExpressionSupport.build("application/json"))));
        matchers.add(new ParamOrHeaderMatcher(true, java.util.Arrays.asList(
                NameValueExpressionSupport.build("X-Custom=val"))));
        return new PathMappingContext(hm, matchers, "/api/hello");
    }

    private PathMappingContext resourceRoute() {
        ResourceHandlerRegistration reg = new ResourceHandlerRegistration("/static/**")
                .addResourceLocations("classpath:/static/");
        ResourceRequestHandler handler = new ResourceRequestHandler(reg);
        return new PathMappingContext(handler, "/static/**");
    }

    private ActuatorMappingDescriptionProvider provider(PathMappingContext... routes) {
        WebContext webContext = mock(WebContext.class);
        MappingRegistry registry = new MappingRegistry();
        for (PathMappingContext r : routes) {
            registry.registerMapping(r);
        }
        when(webContext.getWebComponent(MappingRegistry.class)).thenReturn(registry);
        return new ActuatorMappingDescriptionProvider(webContext);
    }

    @Test
    void describeMappings_controllerRoute_buildsFullDescription() throws Exception {
        PathMappingContext route = controllerRoute();
        Object result = provider(route).describeMappings(mock(ApplicationContext.class));

        Map<String, List<Map<String, Object>>> wrapper = (Map) result;
        List<Map<String, Object>> mappings = wrapper.get("dispatcherServlet");
        assertEquals(1, mappings.size());
        Map<String, Object> mapping = mappings.get(0);

assertTrue(((String) mapping.get("handler")).contains("TestController#hello"));
        String predicate = (String) mapping.get("predicate");
        assertTrue(predicate.contains("GET") && predicate.contains("POST"),
                "predicate 应含 method，实际: " + predicate);
        assertTrue(predicate.contains("/api/hello"), "predicate 应含 path");
        assertTrue(predicate.startsWith("{") && predicate.endsWith("}") && predicate.contains("["),
                "predicate 格式应为 {methods [path]}");

        Map<String, Object> details = (Map<String, Object>) mapping.get("details");
Map<String, Object> hm = (Map<String, Object>) details.get("handlerMethod");
        assertEquals("io.springperf.web.autoconfigure.actuator.ActuatorMappingDescriptionProviderDetailsTest.TestController",
                hm.get("className"));
        assertEquals("hello", hm.get("name"));

Map<String, Object> conditions = (Map<String, Object>) details.get("requestMappingConditions");
        assertTrue(conditions.get("patterns").toString().contains("/api/hello"));
        assertTrue(new java.util.HashSet<>((List<String>) conditions.get("methods"))
                        .containsAll(Arrays.asList("GET", "POST")),
                "methods 应含 GET/POST（顺序无关）");
assertTrue(((List<String>) conditions.get("produces")).size() >= 1,
                "produce matcher 应写入 produces");
        assertEquals("X-Custom=val", ((List<String>) conditions.get("headers")).get(0));
    }

    @Test
    void describeMappings_resourceRoute_buildsResourceDescription() throws Exception {
        PathMappingContext route = resourceRoute();
        Object result = provider(route).describeMappings(mock(ApplicationContext.class));

        Map<String, List<Map<String, Object>>> wrapper = (Map) result;
        Map<String, Object> mapping = wrapper.get("dispatcherServlet").get(0);

        assertTrue(((String) mapping.get("handler")).contains("ResourceRequestHandler"));
        assertEquals("/static/**", mapping.get("predicate"));
        assertNull(mapping.get("details"));
    }
}
