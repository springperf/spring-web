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
            if (mappingContext != null && mappingContext.getBridgedMethod().getReturnType() == void.class && !resp.isHandled()) {
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
            ReturnValueResolver resolver = returnValueContext.getReturnValueResolver();
            if (resolver != null) {
                if (resolver.supportsReturnValue(returnValue, req, resp)) {
                    resolver.resolveReturnValue(returnValue, returnType, req, resp);
                    return true;
                }
            }
        }
        for (ReturnValueResolver resolver : resolvers) {
            if (resolver.supportsReturnValue(returnValue, req, resp)) {
                resolver.resolveReturnValue(returnValue, returnType, req, resp);
                return true;
            }
        }
        return false;
    }

    protected MethodReturnValueContext getMethodReturnValueContext(MappingHandlerMethod mappingContext) {
        if (mappingContext == null) {
            return null;
        }
        MethodReturnValueContext methodReturnValueContext = mappingContext.get(MAPPING_CACHE_KEY);
        if (methodReturnValueContext == null) {
            methodReturnValueContext = new MethodReturnValueContext();
            MethodParameter returnType = new MethodParameter(mappingContext.getBridgedMethod(), -1);
            methodReturnValueContext.setReturnType(returnType);
            mappingContext.set(MAPPING_CACHE_KEY, methodReturnValueContext);
        }
        return methodReturnValueContext;
    }
}
