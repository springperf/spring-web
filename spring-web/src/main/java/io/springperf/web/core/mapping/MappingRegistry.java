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
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

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
            String prefix = "";
            if (clazz.isAnnotationPresent(RequestMapping.class)) {
                RequestMapping crm = clazz.getAnnotation(RequestMapping.class);
                if (crm.value().length > 0) {
                    prefix = WebUtils.pathJoin(prefix, crm.value()[0]);
                }
            }

            Method[] methods = clazz.getDeclaredMethods();
            for (Method m : methods) {
                RequestMapping requestMapping = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (requestMapping == null) {
                    continue;
                }
                initMethodMappingContext(bean, m, prefix, requestMapping);
            }
        }

    }

    public void registerMapping(PathMappingContext mappingContext) {
        mappingContextList.add(mappingContext);
    }

    /**
     * 在初始化完成后注册新的 Mapping，直接加入已构建的优化器。
     * <p>适用于框架初始化后动态添加路由的场景（如 Actuator 端点）。
     * 仅支持无通配符的简单路径；通配符路径需要重新执行 optimizeMapping。</p>
     */
    public synchronized void registerMappingAfterInit(PathMappingContext mappingContext) {
        registerMapping(mappingContext);
        String pathRule = mappingContext.getPathRule();
        if (PathPatternUtils.pathHaveWildcard(pathRule)) {
            log.warn("registerMappingAfterInit does not support wildcard path: {}", pathRule);
            return;
        }
        for (RouterOptimizer optimizer : optimizers) {
            if (optimizer instanceof FullPathRouterOptimizer) {
                FullPathRouterOptimizer.putSimpleUrl(
                        ((FullPathRouterOptimizer) optimizer).getRouteMap(),
                        mappingContext);
                return;
            }
        }
        // 没有 FullPathRouterOptimizer → 创建一个新的（兜底）
        FullPathRouterOptimizer fallback = new FullPathRouterOptimizer();
        FullPathRouterOptimizer.putSimpleUrl(fallback.getRouteMap(), mappingContext);
        fallback.init(Collections.singletonList(mappingContext));
        optimizers.add(0, fallback);
    }

    public void initComponentPhase3() {
        optimizeMapping(mappingContextList);
    }

    protected void initMethodMappingContext(Object bean, Method method, String prefix, RequestMapping requestMapping) {
        String[] paths = requestMapping.path();
        if (paths.length == 0) {
            paths = requestMapping.value();
        }
        if (paths.length == 0) {
            paths = new String[]{""};
        }
        HandlerMethod hm = new HandlerMethod(bean, method);
        List<Matcher> matchers = initMatcher(requestMapping);
        for (String path : paths) {
            String fullPath = WebUtils.pathJoin(prefix, path);
            PathMappingContext methodMappingContext = new PathMappingContext(hm, matchers, fullPath);
            registerMapping(methodMappingContext);
            log.info("Mapped {} -> {}#{}", methodMappingContext, bean.getClass().getSimpleName(), method.getName());
        }
    }

    protected List<Matcher> initMatcher(RequestMapping requestMapping) {
        List<Matcher> matchers = new ArrayList<>();
        RequestMethod[] methods = requestMapping.method();
        if (methods.length > 0) {
            HttpMethod[] httpMethods = Arrays.stream(methods).map(x -> HttpMethod.resolve(x.name())).toArray(HttpMethod[]::new);
            matchers.add(new HttpMethodMatcher(httpMethods));
        }
        String[] params = requestMapping.params();
        if (params.length > 0) {
            List<NameValueExpressionSupport> expressionList = Arrays.stream(params).map(NameValueExpressionSupport::build).collect(Collectors.toList());
            matchers.add(new ParamOrHeaderMatcher(false, expressionList));
        }
        String[] headers = requestMapping.headers();
        if (headers.length > 0) {
            List<NameValueExpressionSupport> expressionList = Arrays.stream(headers).map(NameValueExpressionSupport::build).collect(Collectors.toList());
            matchers.add(new ParamOrHeaderMatcher(true, expressionList));
        }
        String[] consumes = requestMapping.consumes();
        if (consumes.length > 0) {
            List<MediaTypeExpressionSupport> mediaTypeRuleList = Arrays.stream(consumes).map(MediaTypeExpressionSupport::build).collect(Collectors.toList());
            matchers.add(new ConsumeOrProduceMatcher(false, mediaTypeRuleList));
        }
        String[] produces = requestMapping.produces();
        if (produces.length > 0) {
            List<MediaTypeExpressionSupport> mediaTypeRuleList = Arrays.stream(produces).map(MediaTypeExpressionSupport::build).collect(Collectors.toList());
            matchers.add(new ConsumeOrProduceMatcher(true, mediaTypeRuleList));
        }
        return matchers;
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
        if (result.isMatched()) {
            PathMappingContext.set(req, result.getMatchedContext());
        }
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

