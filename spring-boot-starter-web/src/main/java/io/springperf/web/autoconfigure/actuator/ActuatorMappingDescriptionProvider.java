package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.match.*;
import io.springperf.web.core.resource.ResourceRequestHandler;
import org.springframework.boot.actuate.web.mappings.MappingDescriptionProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Perf 框架的 Actuator 路由映射描述提供者。
 */
public class ActuatorMappingDescriptionProvider implements MappingDescriptionProvider {

    private static final String MAPPING_NAME = "dispatcherServlets";
    private final WebContext webContext;

    public ActuatorMappingDescriptionProvider(WebContext webContext) { this.webContext = webContext; }

    @Override public String getMappingName() { return MAPPING_NAME; }

    @Override
    public Object describeMappings(ApplicationContext context) {
        MappingRegistry mappingRegistry = webContext.getWebComponent(MappingRegistry.class);
        if (mappingRegistry == null) return Collections.emptyMap();
        List<PathMappingContext> routes = mappingRegistry.getMappingContextList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (PathMappingContext route : routes) result.add(describeRoute(route));
        Map<String, List<Map<String, Object>>> wrapper = new LinkedHashMap<>();
        wrapper.put("dispatcherServlet", result);
        return wrapper;
    }

    private Map<String, Object> describeRoute(PathMappingContext route) {
        if (route.getBean() instanceof ResourceRequestHandler) return describeResourceRoute(route);
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("handler", buildHandlerString(route));
        mapping.put("predicate", buildPredicateString(route));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("handlerMethod", buildHandlerMethod(route));
        details.put("requestMappingConditions", buildConditions(route));
        mapping.put("details", details);
        return mapping;
    }

    private Map<String, Object> describeResourceRoute(PathMappingContext route) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        ResourceRequestHandler handler = (ResourceRequestHandler) route.getBean();
        String locationsStr = handler.getRegistration().getLocationValues().stream()
                .map(loc -> "\"" + loc + "\"").collect(Collectors.joining(", "));
        mapping.put("handler", "ResourceRequestHandler [" + locationsStr + "]");
        mapping.put("predicate", route.getPathRule());
        mapping.put("details", null);
        return mapping;
    }

    private String buildHandlerString(PathMappingContext route) {
        Class<?> beanType = route.getBeanType();
        Method method = route.getMethod();
        return beanType != null && method != null ? beanType.getName() + "#" + method.getName() : route.toString();
    }

    private String buildPredicateString(PathMappingContext route) {
        StringBuilder sb = new StringBuilder("{");
        String methods = extractMethods(route);
        if (!methods.isEmpty()) sb.append(methods).append(" ");
        sb.append("[").append(route.getPathRule()).append("]}");
        return sb.toString();
    }

    private Map<String, Object> buildHandlerMethod(PathMappingContext route) {
        Map<String, Object> hm = new LinkedHashMap<>();
        Method method = route.getMethod();
        hm.put("className", method != null ? method.getDeclaringClass().getCanonicalName() : "");
        hm.put("name", method != null ? method.getName() : "");
        hm.put("descriptor", method != null ? org.springframework.asm.Type.getMethodDescriptor(method) : "");
        return hm;
    }

    private Map<String, Object> buildConditions(PathMappingContext route) {
        Map<String, Object> conditions = new LinkedHashMap<>();
        conditions.put("patterns", Collections.singletonList(route.getPathRule()));
        conditions.put("methods", extractMethodList(route));
        List<String> params = new ArrayList<>(), headers = new ArrayList<>();
        List<String> consumes = new ArrayList<>(), produces = new ArrayList<>();
        for (Matcher matcher : route.getMatchers()) {
            if (matcher instanceof ConsumeOrProduceMatcher) {
                List<String> mediaTypes = ((ConsumeOrProduceMatcher) matcher).getMediaTypeExpressions().stream()
                        .map(Objects::toString).collect(Collectors.toList());
                if (((ConsumeOrProduceMatcher) matcher).isProduce()) produces.addAll(mediaTypes);
                else consumes.addAll(mediaTypes);
            } else if (matcher instanceof ParamOrHeaderMatcher) {
                List<String> exprs = ((ParamOrHeaderMatcher) matcher).getExpressions().stream()
                        .map(NameValueExpressionSupport::toString).collect(Collectors.toList());
                if (((ParamOrHeaderMatcher) matcher).isHeader()) headers.addAll(exprs);
                else params.addAll(exprs);
            }
        }
        conditions.put("params", params); conditions.put("headers", headers);
        conditions.put("consumes", consumes); conditions.put("produces", produces);
        return conditions;
    }

    private String extractMethods(PathMappingContext route) {
        for (Matcher matcher : route.getMatchers())
            if (matcher instanceof HttpMethodMatcher)
                return ((HttpMethodMatcher) matcher).getHttpMethods().stream().map(HttpMethod::name).collect(Collectors.joining(" | "));
        return "";
    }

    private List<String> extractMethodList(PathMappingContext route) {
        for (Matcher matcher : route.getMatchers())
            if (matcher instanceof HttpMethodMatcher)
                return ((HttpMethodMatcher) matcher).getHttpMethods().stream().map(HttpMethod::name).collect(Collectors.toList());
        return Collections.emptyList();
    }
}