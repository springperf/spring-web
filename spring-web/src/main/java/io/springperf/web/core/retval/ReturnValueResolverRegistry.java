package io.springperf.web.core.retval;

import io.springperf.web.context.WebComponentContainer;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.async.reactive.ReactiveReturnValueResolver;
import io.springperf.web.core.async.stream.StreamEmitterReturnValueResolver;
import io.springperf.web.core.codec.HttpBodyCodecRegistry;
import io.springperf.web.core.mapping.MappingCacheKey;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.retval.resolver.*;
import io.springperf.web.core.retval.resolver.async.*;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages a chain of ReturnValueResolvers that process handler method return values into HTTP responses.
 */
public class ReturnValueResolverRegistry extends WebComponentContainer {

    public static final MappingCacheKey<MethodReturnValueContext> MAPPING_CACHE_KEY = MappingCacheKey
            .createMethodCacheKey(MethodReturnValueContext.class);

    private final List<ReturnValueResolver> resolvers = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<Class<?>, Boolean> asyncReturnValueCache = new ConcurrentHashMap<>();
    private volatile List<ReturnValueResolver> asyncResolvers;

    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        webContext.getWebComponentWithDefault(HttpBodyCodecRegistry.class, new HttpBodyCodecRegistry());
        initReturnValueResolver();
    }

    public void initReturnValueResolver() {
        // 异步处理
        registerWebComponent(new DeferredResultReturnValueResolver());
        registerWebComponent(new CompletionStageReturnValueResolver());
        registerWebComponent(new AsyncTaskReturnValueResolver());
        registerWebComponent(new CallableReturnValueResolver());
        registerWebComponent(new StreamEmitterReturnValueResolver());
        registerWebComponent(new ReactiveReturnValueResolver());
        // 流式处理相关
        registerWebComponent(new ByteArrayReturnValueResolver());
        registerWebComponent(new ResourceReturnValueResolver());
        registerWebComponent(new InputStreamReturnValueResolver());
        registerWebComponent(new FileReturnValueResolver());
        // 通用实体处理
        registerWebComponent(new HttpEntityReturnValueResolver());
        registerWebComponent(new JsonBodyReturnValueResolver());
        // 拓展处理
        registerWebComponent(ReturnValueResolver.class);
        // 实际初始化resolvers
        initRealComponentList(resolvers, ReturnValueResolver.class);
    }

    /**
     * 判断 returnValue 是否被任意 BaseAsyncReturnValueResolver 支持（即属于异步返回值）。 结果缓存到 asyncReturnValueCache，避免重复遍历。
     */
    public boolean isAsyncReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        if (returnValue == null)
            return false;
        Class<?> clazz = returnValue.getClass();
        Boolean cached = asyncReturnValueCache.get(clazz);
        if (cached != null)
            return cached;
        for (ReturnValueResolver resolver : getAsyncResolvers()) {
            if (resolver.supportsReturnValue(returnValue, req, resp)) {
                asyncReturnValueCache.putIfAbsent(clazz, Boolean.TRUE);
                return true;
            }
        }
        asyncReturnValueCache.putIfAbsent(clazz, Boolean.FALSE);
        return false;
    }

    private List<ReturnValueResolver> getAsyncResolvers() {
        List<ReturnValueResolver> list = this.asyncResolvers;
        if (list == null) {
            synchronized (this) {
                list = this.asyncResolvers;
                if (list == null) {
                    list = new ArrayList<>();
                    for (ReturnValueResolver r : resolvers) {
                        if (r instanceof BaseAsyncReturnValueResolver) {
                            list.add(r);
                        }
                    }
                    this.asyncResolvers = list;
                }
            }
        }
        return list;
    }

    public void addResolver(ReturnValueResolver resolver) {
        registerWebComponent(resolver);
        initRealComponentList(resolvers, ReturnValueResolver.class);
        // 动态注册解析器后，失效异步解析器与按类型缓存，确保新注册的异步解析器被识别
        this.asyncResolvers = null;
        this.asyncReturnValueCache.clear();
    }

    public void resolveReturnValue(Object returnValue, MappingHandlerMethod mappingContext, WebServerHttpRequest req,
            WebServerHttpResponse resp) throws Exception {
        if (skipResolve(returnValue, mappingContext, req, resp)) {
            return;
        }
        if (doResolveReturnValue(returnValue, mappingContext, req, resp)) {
            resp.setHandled();
        }
    }

    protected boolean skipResolve(Object returnValue, MappingHandlerMethod mappingContext, WebServerHttpRequest req,
            WebServerHttpResponse resp) {
        if (returnValue == null) {
            if (mappingContext != null) {
                Class<?> returnType = mappingContext.getMethod().getReturnType();
                if (returnType == void.class) {
                    // void 方法：响应体由方法自身写入，直接标记完成
                    if (!resp.isHandled()) {
                        resp.setHandled();
                    }
                } else {
                    // 非 void 方法返回 null：声明了返回类型却返回空值，视为"无响应体的已完成响应"。
                    // 必须标记 handled，否则响应永不 flush（客户端挂起至超时 504）。
                    resp.setHandled();
                }
            }
            // mappingContext == null 时保持原行为：不主动标记（由其它路径负责 flush）
            return true;
        }
        return resp.isHandled();
    }

    protected boolean doResolveReturnValue(Object returnValue, MappingHandlerMethod mappingContext,
            WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        MethodReturnValueContext returnValueContext = getMethodReturnValueContext(mappingContext);
        MethodParameter returnType = returnValueContext == null ? null : returnValueContext.getReturnType();

        // Optional 解包：声明式或运行时
        if (returnValue instanceof Optional<?> opt) {
            if (opt.isEmpty()) {
                return true; // Optional.empty() → 无响应体
            }
            returnValue = opt.get();
            if (returnValueContext != null && returnValueContext.isOptionalType()) {
                // 声明式 Optional：使用内联类型，尝试内联解析器缓存
                returnType = returnValueContext.getOptionalInnerReturnType();
                ReturnValueResolver innerResolver = returnValueContext.getOptionalInnerReturnValueResolver();
                if (innerResolver != null && innerResolver.supportsReturnValue(returnValue, req, resp)) {
                    innerResolver.resolveReturnValue(returnValue, returnType, req, resp);
                    return true;
                }
            }
            // 运行时 Optional（方法签名未声明）：returnType 保持原样，走正常流程
        }

        if (returnValueContext != null) {
            // Fast path 1: 缓存的主解析器匹配（优先于异步内联解析器）
            ReturnValueResolver resolver = returnValueContext.getReturnValueResolver();
            if (resolver != null && resolver.supportsReturnValue(returnValue, req, resp)) {
                resolver.resolveReturnValue(returnValue, returnType, req, resp);
                return true;
            }
            // Fast path 2: 异步 dispatch 时，内联泛型解析器匹配
            if (returnValueContext.isAsyncType()) {
                ReturnValueResolver innerResolver = returnValueContext.getInnerReturnValueResolver();
                if (innerResolver != null && innerResolver.supportsReturnValue(returnValue, req, resp)) {
                    innerResolver.resolveReturnValue(returnValue, returnValueContext.getInnerReturnType(), req, resp);
                    return true;
                }
            }
        }
        // 线性扫描（缓存 miss 或两个缓存均不匹配）
        for (ReturnValueResolver resolver : resolvers) {
            if (resolver.supportsReturnValue(returnValue, req, resp)) {
                resolver.resolveReturnValue(returnValue, returnType, req, resp);
                // 懒缓存：将匹配的解析器缓存到 context
                if (returnValueContext != null) {
                    if (returnValueContext.isOptionalType()) {
                        // 声明式 Optional：缓存内联解析器，后续请求直接命中 fast path
                        returnValueContext.setOptionalInnerReturnValueResolver(resolver);
                    } else {
                        returnValueContext.setReturnValueResolver(resolver);
                    }
                    if (resolver instanceof BaseAsyncReturnValueResolver) {
                        resolveInnerReturnValueContext(returnValueContext, mappingContext);
                    }
                }
                return true;
            }
        }
        // 无解析器认领：若是响应式类型，说明缺 ReactiveAdapter（最常见原因：运行时类路径无 reactor）。
        // 「静默空 200」是最难排查的降级形态，故显式告警。独立 logger 名避免与 lombok @Slf4j 的 log 冲突。
        if (returnValue instanceof org.reactivestreams.Publisher) {
            REACTIVE_WARN_LOG.warn("return value looks reactive but no resolver accepted it: {} "
                    + "— missing ReactiveAdapter? (e.g. reactor absent from classpath, "
                    + "or register one via a ReactiveAdapterRegistry bean)", returnValue.getClass().getName());
        }
        return false;
    }

    private static final org.slf4j.Logger REACTIVE_WARN_LOG = org.slf4j.LoggerFactory
            .getLogger(ReturnValueResolverRegistry.class);

    /**
     * 懒缓存异步类型的泛型内联类型和解析器。
     * <p>
     * 如 {@code DeferredResult<String>} 在第一次请求时， 解析 {@code String} 的解析器并缓存，异步 dispatch 回来时可直接命中。
     * </p>
     */
    protected void resolveInnerReturnValueContext(MethodReturnValueContext context,
            MappingHandlerMethod mappingContext) {
        context.setAsyncType(true);
        MethodParameter effectiveReturnType = mappingContext.getEffectiveReturnType();
        ResolvableType rt = effectiveReturnType != null
                ? ResolvableType.forType(effectiveReturnType.getGenericParameterType())
                : ResolvableType.forMethodReturnType(mappingContext.getMethod());
        ResolvableType generic = rt.getGeneric(0);
        Class<?> innerClass = generic.resolve();
        if (innerClass == null || innerClass == Object.class) {
            return; // 无法确定泛型类型，跳过缓存，运行时走线性扫描 fallback
        }
        MethodParameter innerReturnType = new MethodParameter(mappingContext.getMethod(), -1) {
            private final ResolvableType genericRt = rt.getGeneric(0);

            /** resolve 声明为 @Nullable，但构造前已确认同一泛型可解析；兜底 Object.class 以维持 getParameterType 的非空契约。 */
            private final Class<?> resolvedType = resolveOnce();

            private Class<?> resolveOnce() {
                Class<?> resolved = genericRt.resolve();
                return resolved != null ? resolved : Object.class;
            }

            @Override
            public Class<?> getParameterType() {
                return resolvedType;
            }

            @Override
            public Type getGenericParameterType() {
                return genericRt.getType();
            }
        };
        context.setInnerReturnType(innerReturnType);
        for (ReturnValueResolver resolver : resolvers) {
            if (resolver.supportsReturnType(innerReturnType, mappingContext)) {
                context.setInnerReturnValueResolver(resolver);
                break;
            }
        }
    }

    protected MethodReturnValueContext getMethodReturnValueContext(MappingHandlerMethod mappingContext) {
        if (mappingContext == null) {
            return null;
        }
        MethodReturnValueContext methodReturnValueContext = mappingContext.get(MAPPING_CACHE_KEY);
        if (methodReturnValueContext == null) {
            methodReturnValueContext = new MethodReturnValueContext();
            MethodParameter returnType = mappingContext.getEffectiveReturnType();
            if (returnType == null) {
                returnType = new MethodParameter(mappingContext.getMethod(), -1);
            }
            methodReturnValueContext.setReturnType(returnType);

            // 检测声明式 Optional 返回类型
            if (returnType.getParameterType() == Optional.class) {
                methodReturnValueContext.setOptionalType(true);
                MethodParameter innerReturnType = returnType.nestedIfOptional();
                methodReturnValueContext.setOptionalInnerReturnType(innerReturnType);
            }

            mappingContext.set(MAPPING_CACHE_KEY, methodReturnValueContext);
        }
        return methodReturnValueContext;
    }
}
