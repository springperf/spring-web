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

/**
 * Manages a chain of ReturnValueResolvers that process handler method return values into HTTP responses.
 */
public class ReturnValueResolverRegistry extends WebComponentContainer {

    public static final MappingCacheKey<MethodReturnValueContext> MAPPING_CACHE_KEY = MappingCacheKey.createMethodCacheKey(MethodReturnValueContext.class);

    private final List<ReturnValueResolver> resolvers = new ArrayList<>();

    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        webContext.getWebComponentWithDefault(HttpBodyCodecRegistry.class, new HttpBodyCodecRegistry());
        initReturnValueResolver();
    }

    public void initReturnValueResolver() {
        //异步处理
        registerWebComponent(new DeferredResultReturnValueResolver());
        registerWebComponent(new ListenableFutureReturnValueResolver());
        registerWebComponent(new CompletionStageReturnValueResolver());
        registerWebComponent(new AsyncTaskReturnValueResolver());
        registerWebComponent(new CallableReturnValueResolver());
        registerWebComponent(new StreamEmitterReturnValueResolver());
        registerWebComponent(new ReactiveReturnValueResolver());
        //流式处理相关
        registerWebComponent(new ByteArrayReturnValueResolver());
        registerWebComponent(new ResourceReturnValueResolver());
        registerWebComponent(new InputStreamReturnValueResolver());
        registerWebComponent(new FileReturnValueResolver());
        //通用实体处理
        registerWebComponent(new HttpEntityReturnValueResolver());
        registerWebComponent(new JsonBodyReturnValueResolver());
        // 拓展处理
        registerWebComponent(ReturnValueResolver.class);
        // 实际初始化resolvers
        initRealComponentList(resolvers, ReturnValueResolver.class);
    }

    public void addResolver(ReturnValueResolver resolver) {
        registerWebComponent(resolver);
        initRealComponentList(resolvers, ReturnValueResolver.class);
    }

    public void resolveReturnValue(Object returnValue, MappingHandlerMethod mappingContext, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        if (skipResolve(returnValue, mappingContext, req, resp)) {
            return;
        }
        if (doResolveReturnValue(returnValue, mappingContext, req, resp)) {
            resp.setHandled();
        }
    }

    protected boolean skipResolve(Object returnValue, MappingHandlerMethod mappingContext, WebServerHttpRequest req, WebServerHttpResponse resp) {
        if (returnValue == null) {
            if (mappingContext != null && mappingContext.getMethod().getReturnType() == void.class && !resp.isHandled()) {
                resp.setHandled();
            }
            return true;
        }
        return resp.isHandled();
    }


    protected boolean doResolveReturnValue(Object returnValue, MappingHandlerMethod mappingContext, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        MethodReturnValueContext returnValueContext = getMethodReturnValueContext(mappingContext);
        MethodParameter returnType = returnValueContext == null ? null : returnValueContext.getReturnType();
        if (returnValueContext != null) {
            // Fast path 1: 异步 dispatch 时，内联泛型解析器匹配
            if (returnValueContext.isAsyncType()) {
                ReturnValueResolver innerResolver = returnValueContext.getInnerReturnValueResolver();
                if (innerResolver != null && innerResolver.supportsReturnValue(returnValue, req, resp)) {
                    innerResolver.resolveReturnValue(returnValue, returnValueContext.getInnerReturnType(), req, resp);
                    return true;
                }
            }
            // Fast path 2: 缓存的主解析器匹配
            ReturnValueResolver resolver = returnValueContext.getReturnValueResolver();
            if (resolver != null && resolver.supportsReturnValue(returnValue, req, resp)) {
                resolver.resolveReturnValue(returnValue, returnType, req, resp);
                return true;
            }
        }
        // 线性扫描（缓存 miss 或两个缓存均不匹配）
        for (ReturnValueResolver resolver : resolvers) {
            if (resolver.supportsReturnValue(returnValue, req, resp)) {
                resolver.resolveReturnValue(returnValue, returnType, req, resp);
                // 懒缓存：将匹配的解析器缓存到 context
                if (returnValueContext != null) {
                    returnValueContext.setReturnValueResolver(resolver);
                    if (resolver instanceof BaseAsyncReturnValueResolver) {
                        resolveInnerReturnValueContext(returnValueContext, mappingContext);
                    }
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 懒缓存异步类型的泛型内联类型和解析器。
     * <p>如 {@code DeferredResult<String>} 在第一次请求时，
     * 解析 {@code String} 的解析器并缓存，异步 dispatch 回来时可直接命中。</p>
     */
    protected void resolveInnerReturnValueContext(MethodReturnValueContext context, MappingHandlerMethod mappingContext) {
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

            @Override
            public Class<?> getParameterType() {
                return genericRt.resolve();
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
            mappingContext.set(MAPPING_CACHE_KEY, methodReturnValueContext);
        }
        return methodReturnValueContext;
    }
}
