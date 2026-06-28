package io.springperf.web.core.mapping;

import io.springperf.web.context.WebComponentContainer;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.match.*;
import io.springperf.web.core.mapping.optimize.*;
import io.springperf.web.core.mapping.route.Router;
import io.springperf.web.core.resource.ResourceHandlerRegistry;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.util.PathPatternUtils;
import io.springperf.web.util.WebUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.PropertyResolver;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;

/**
 * Scans ApplicationContext for @Controller/@RestController beans and registers their mappings.
 */
@Slf4j
public class MappingRegistry extends WebComponentContainer {

    private final List<PathMappingContext> mappingContextList = new ArrayList<>();
    private final List<RouterOptimizer> optimizers = new ArrayList<>();

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        webContext.getWebComponentWithDefault(ResourceHandlerRegistry.class, new ResourceHandlerRegistry());
    }


    public void initComponentPhase1() {
        Map<String, Object> beans = new HashMap<>();
        ApplicationContext ctx = getWebContext().getCtx();
        beans.putAll(ctx.getBeansWithAnnotation(RestController.class));
        beans.putAll(ctx.getBeansWithAnnotation(Controller.class));
        for (Object bean : new HashSet<>(beans.values())) {
            Class<?> clazz = bean.getClass();
            // 使用真实类而非代理类的方法，确保 @RequestMapping/@PostMapping 等注解可被正常读取
            Class<?> targetClass = ClassUtils.getUserClass(clazz);
            String[] prefix = new String[]{""};
            List<Matcher> classMatchers = emptyList();
            RequestMapping crm = AnnotatedElementUtils.findMergedAnnotation(targetClass, RequestMapping.class);
            if (crm != null) {
                if (crm.value().length > 0) {
                    prefix = crm.value();
                } else if (crm.path().length > 0) {
                    prefix = crm.path();
                }
                classMatchers = initMatcher(crm);
            }
            prefix = resolvePlaceholders(prefix);
            Method[] methods = targetClass.getDeclaredMethods();
            for (Method m : methods) {
                RequestMapping requestMapping = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (requestMapping == null) {
                    continue;
                }
                initMethodMappingContext(bean, m, prefix, requestMapping, classMatchers);
            }
        }

    }

    public void registerMapping(PathMappingContext mappingContext) {
        mappingContextList.add(mappingContext);
    }

    public void initComponentPhase3() {
        optimizeMapping(mappingContextList);
    }

    protected void initMethodMappingContext(Object bean, Method method, String[] prefixArray, RequestMapping requestMapping, List<Matcher> classMatchers) {
        String[] paths = requestMapping.path();
        if (paths.length == 0) {
            paths = requestMapping.value();
        }
        if (paths.length == 0) {
            paths = new String[]{""};
        }
        paths = resolvePlaceholders(paths);
        HandlerMethod hm = new HandlerMethod(bean, method);
        List<Matcher> matchers = initMatcher(requestMapping);
        mergeMatchers(matchers, classMatchers);
        for (String prefix : prefixArray) {
            for (String path : paths) {
                String fullPath = WebUtils.pathJoin(prefix, path);
                PathMappingContext methodMappingContext = new PathMappingContext(hm, matchers, fullPath);
                registerMapping(methodMappingContext);
                log.info("Mapped {} -> {}#{}", methodMappingContext, bean.getClass().getSimpleName(), method.getName());
            }
        }
    }

    protected List<Matcher> initMatcher(RequestMapping requestMapping) {
        List<Matcher> matchers = new ArrayList<>();
        RequestMethod[] methods = requestMapping.method();
        if (methods.length > 0) {
            HttpMethod[] httpMethods = Arrays.stream(methods).map(x -> HttpMethod.valueOf(x.name())).toArray(HttpMethod[]::new);
            matchers.add(new HttpMethodMatcher(httpMethods));
        }
        String[] params = requestMapping.params();
        if (params.length > 0) {
            params = resolvePlaceholders(params);
            List<NameValueExpressionSupport> expressionList = Arrays.stream(params).map(NameValueExpressionSupport::build).collect(Collectors.toList());
            matchers.add(new ParamOrHeaderMatcher(false, expressionList));
        }
        String[] headers = requestMapping.headers();
        if (headers.length > 0) {
            headers = resolvePlaceholders(headers);
            List<NameValueExpressionSupport> expressionList = Arrays.stream(headers).map(NameValueExpressionSupport::build).collect(Collectors.toList());
            matchers.add(new ParamOrHeaderMatcher(true, expressionList));
        }
        String[] consumes = requestMapping.consumes();
        if (consumes.length > 0) {
            consumes = resolvePlaceholders(consumes);
            List<MediaTypeExpressionSupport> mediaTypeRuleList = Arrays.stream(consumes).map(MediaTypeExpressionSupport::build).collect(Collectors.toList());
            matchers.add(new ConsumeOrProduceMatcher(false, mediaTypeRuleList));
        }
        String[] produces = requestMapping.produces();
        if (produces.length > 0) {
            produces = resolvePlaceholders(produces);
            List<MediaTypeExpressionSupport> mediaTypeRuleList = Arrays.stream(produces).map(MediaTypeExpressionSupport::build).collect(Collectors.toList());
            matchers.add(new ConsumeOrProduceMatcher(true, mediaTypeRuleList));
        }
        return matchers;
    }

    /**
     * 将类级别的 Matcher 约束合并到方法级别的 Matcher 列表中。
     * 同类型的 Matcher 合并内部约束列表，不同类型则直接追加。
     */
    protected void mergeMatchers(List<Matcher> methodMatchers, List<Matcher> classMatchers) {
        for (Matcher classMatcher : classMatchers) {
            boolean merged = false;
            for (int i = 0; i < methodMatchers.size(); i++) {
                if (methodMatchers.get(i).isSameTypeMatcher(classMatcher)) {
                    methodMatchers.set(i, mergeMatcherPair(methodMatchers.get(i), classMatcher));
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                methodMatchers.add(classMatcher);
            }
        }
    }

    /**
     * 合并两个同类型的 Matcher 实例中的约束列表。
     */
    protected static Matcher mergeMatcherPair(Matcher methodMatcher, Matcher classMatcher) {
        if (methodMatcher instanceof HttpMethodMatcher && classMatcher instanceof HttpMethodMatcher) {
            HttpMethodMatcher m = (HttpMethodMatcher) methodMatcher;
            HttpMethodMatcher c = (HttpMethodMatcher) classMatcher;
            Set<HttpMethod> combined = new HashSet<>(m.getHttpMethods());
            combined.addAll(c.getHttpMethods());
            return new HttpMethodMatcher(combined.toArray(new HttpMethod[0]));
        }
        if (methodMatcher instanceof ParamOrHeaderMatcher && classMatcher instanceof ParamOrHeaderMatcher) {
            ParamOrHeaderMatcher m = (ParamOrHeaderMatcher) methodMatcher;
            ParamOrHeaderMatcher c = (ParamOrHeaderMatcher) classMatcher;
            List<NameValueExpressionSupport> combined = new ArrayList<>(m.getExpressions());
            combined.addAll(c.getExpressions());
            return new ParamOrHeaderMatcher(m.isHeader(), combined);
        }
        if (methodMatcher instanceof ConsumeOrProduceMatcher && classMatcher instanceof ConsumeOrProduceMatcher) {
            ConsumeOrProduceMatcher m = (ConsumeOrProduceMatcher) methodMatcher;
            ConsumeOrProduceMatcher c = (ConsumeOrProduceMatcher) classMatcher;
            List<MediaTypeExpressionSupport> combined = new ArrayList<>(m.getMediaTypeExpressions());
            combined.addAll(c.getMediaTypeExpressions());
            return new ConsumeOrProduceMatcher(m.isProduce(), combined);
        }
        return methodMatcher;
    }

    private String[] resolvePlaceholders(String[] values) {
        WebContext wc = getWebContext();
        if (wc == null) {
            return values;
        }
        PropertyResolver resolver = wc.getCtx().getEnvironment();
        String[] result = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = resolver.resolvePlaceholders(values[i]);
        }
        return result;
    }

    protected List<RouterOptimizer> getOptimizerTemplate() {
        List<RouterOptimizer> optimizerTemplate = new ArrayList<>();
        optimizerTemplate.add(new PrefixPathRouterOptimizer());
        optimizerTemplate.add(new SuffixPathRouterOptimizer());
        optimizerTemplate.add(new LoopPathPatternRouterOptimizer());
        return optimizerTemplate;
    }

    protected void optimizeMapping(List<PathMappingContext> mappingContextList) {
        // 一次遍历分流，替代三次流遍历
        List<PathMappingContext> simpleUrlList = new ArrayList<>();
        List<PathMappingContext> simpleWildcardList = new ArrayList<>();
        List<PathMappingContext> fullWildcardList = new ArrayList<>();
        for (PathMappingContext mc : mappingContextList) {
            if (!PathPatternUtils.pathHaveWildcard(mc.getPathRule())) {
                simpleUrlList.add(mc);
            } else if (mc.getPathRule().contains("**")) {
                fullWildcardList.add(mc);
            } else {
                simpleWildcardList.add(mc);
            }
        }

        FullPathRouterOptimizer fullPathRouterOptimizer = new FullPathRouterOptimizer();
        if (fullPathRouterOptimizer.support(simpleUrlList)) {
            fullPathRouterOptimizer.init(simpleUrlList);
            optimizers.add(fullPathRouterOptimizer);
        }
        initOptimizersFor(simpleWildcardList);
        initOptimizersFor(fullWildcardList);
    }

    private void initOptimizersFor(List<PathMappingContext> mappingList) {
        for (RouterOptimizer routerOptimizer : getOptimizerTemplate()) {
            if (routerOptimizer.support(mappingList)) {
                routerOptimizer.init(mappingList);
                optimizers.add(routerOptimizer);
            }
        }
    }

    /**
     * 执行 mapping 查找，结果写入请求属性并返回。
     */
    public MappingResult mapping(WebServerHttpRequest req) {
        MappingResult result = doMapping(req);
        MappingResult.set(req, result);
        return result;
    }

    protected MappingResult doMapping(WebServerHttpRequest req) {
        PathMappingContext[] pathMatchedCtxs = null;

        for (RouterOptimizer optimizer : optimizers) {
            Router router = optimizer.optimizeRoute(req);
            if (router != null) {
                PathMappingContext mappingContext = router.route(req);
                if (mappingContext != null) {
                    return MappingResult.matched(mappingContext);
                }
                // 路径匹配但 route() 返回 null（如方法不匹配）：记录上下文用于 CORS 预检 / 405 判断
                if (pathMatchedCtxs == null) {
                    try {
                        pathMatchedCtxs = PathMappingContext.getMatchPathMappingContexts(req);
                        if (pathMatchedCtxs != null && pathMatchedCtxs.length > 0) {
                            if (isMethodMismatch(pathMatchedCtxs, req)) {
                                // 方法不匹配：停止后续 optimizer 穿透（如 /** catch-all 不应覆盖 405）
                                return MappingResult.pathMatched(pathMatchedCtxs, true);
                            }
                            // 非 method 条件不匹配：记录上下文，但继续检查后续 optimizer（通配符 catch-all）
                        }
                    } catch (UnsupportedOperationException e) {
                        // Router 不支持提取上下文，跳过
                    }
                }
            }
        }
        if (pathMatchedCtxs != null) {
            return MappingResult.pathMatched(pathMatchedCtxs, false);
        }
        return MappingResult.notFound();
    }

    /**
     * 判断路由不匹配是否由于 HTTP method 导致。
     * <p>遍历 router 中所有 PathMappingContext：若任一 context 的 HttpMethodMatcher
     * 匹配请求方法（或无 HttpMethodMatcher），说明失败由其他条件引起，不返回 405。</p>
     */
    private static boolean isMethodMismatch(PathMappingContext[] contexts, WebServerHttpRequest req) {
        if (contexts == null || contexts.length == 0) {
            return false;
        }
        boolean hasMethodMatcher = false;
        for (PathMappingContext ctx : contexts) {
            for (Matcher matcher : ctx.getMatchers()) {
                if (matcher instanceof HttpMethodMatcher) {
                    hasMethodMatcher = true;
                    if (((HttpMethodMatcher) matcher).getHttpMethods().contains(req.getMethod())) {
                        return false; // 方法匹配，失败由其他条件引起
                    }
                }
            }
        }
        return hasMethodMatcher; // 所有 context 的方法都不匹配 → 405；无 HttpMethodMatcher → 非 method 问题
    }

    public List<PathMappingContext> getMappingContextList() {
        return mappingContextList;
    }
}

