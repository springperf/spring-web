package io.springperf.web.core.async.reactive;

import io.springperf.web.annotation.ReactiveSupport;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.core.async.stream.*;
import io.springperf.web.core.mapping.MappingCacheKey;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.retval.resolver.async.BaseAsyncReturnValueResolver;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.json.JsonConverter;
import lombok.SneakyThrows;
import org.springframework.core.MethodParameter;
import org.springframework.core.ReactiveAdapter;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.context.request.async.DeferredResult;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.stream.Collectors;

public class ReactiveReturnValueResolver extends BaseAsyncReturnValueResolver {

    protected static final MappingCacheKey<ReactiveConfig> MAPPING_CACHE_KEY = MappingCacheKey.createClassCacheKey(ReactiveConfig.class);
    private static final Set<Class<?>> SUPPORTED_TYPES = new HashSet<>(Arrays.asList(Long.class, JsonConverter.class));
    private AsyncSupportRegistry asyncSupportRegistry;
    private StreamSenderFactory streamSenderFactory;
    private ReactiveAdapterRegistry adapterRegistry;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        asyncSupportRegistry = webContext.getWebComponentWithDefault(AsyncSupportRegistry.class, new AsyncSupportRegistry());
        streamSenderFactory = webContext.getWebComponentWithDefault(StreamSenderFactory.class, new DefaultStreamSenderFactory());
        adapterRegistry = webContext.getBeanFromCtx(ReactiveAdapterRegistry.class);
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        if (adapterRegistry == null) {
            return false;
        }
        Class<?> reactiveType = ResponseEntity.class.isAssignableFrom(returnType.getParameterType()) ?
                ResolvableType.forMethodParameter(returnType).getGeneric().resolve() :
                returnType.getParameterType();
        return adapterRegistry.getAdapter(reactiveType) != null;
    }

    @Override
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        if (adapterRegistry == null) {
            return false;
        }
        if (returnValue instanceof ResponseEntity) {
            returnValue = ((ResponseEntity<?>) returnValue).getBody();
        }
        if (returnValue == null) {
            return false;
        }
        return adapterRegistry.getAdapter(returnValue.getClass()) != null;
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        if (returnValue instanceof ResponseEntity) {
            ResponseEntity<?> responseEntity = (ResponseEntity<?>) returnValue;
            resp.setStatusCode(responseEntity.getStatusCode());
            if (responseEntity.getHeaders() != null) {
                resp.getHeaders().putAll(responseEntity.getHeaders());
            }
            returnValue = responseEntity.getBody();
            returnType = returnType.nested();
        }
        ReactiveAdapter adapter = this.adapterRegistry.getAdapter(returnValue.getClass());
        ResolvableType elementType = ResolvableType.forMethodParameter(returnType).getGeneric();
        Class<?> elementClass = elementType.toClass();
        ReactiveConfig reactiveConfig = getReactiveConfig(req);
        StreamEmitter emitter = createStreamEmitter(reactiveConfig, adapter, elementClass, req, resp);
        if (emitter != null) {
            StreamEmitterUtil.extendResponseAndFlush(emitter, resp, true);
            StreamSender sender = StreamEmitterUtil.initStreamSenderAndStartAsync(emitter, streamSenderFactory, asyncSupportRegistry, req, resp);
            PublisherToStreamEmitterAdapter streamEmitterAdapter = new PublisherToStreamEmitterAdapter(emitter, sender, reactiveConfig);
            streamEmitterAdapter.subscribe(adapter, returnValue);
            StreamEmitterUtil.initializeWithStreamSender(emitter, sender);
        } else {
            DeferredResult deferredResult = reactiveConfig.getTimeout() < 0 ? new DeferredResult() : new DeferredResult(reactiveConfig.getTimeout());
            PublisherToDeferredResultAdapter deferredResultAdapter = new PublisherToDeferredResultAdapter(deferredResult, adapter);
            deferredResultAdapter.subscribe(adapter, returnValue);
            asyncSupportRegistry.startDeferredResultProcessing(req, resp, deferredResult);
        }
    }

    @SneakyThrows
    protected StreamEmitter createStreamEmitter(ReactiveConfig reactiveConfig, ReactiveAdapter adapter, Class<?> elementClass, WebServerHttpRequest request, WebServerHttpResponse response) {
        if (adapter.isMultiValue()) {
            if (reactiveConfig.getStreamEmitterType() != null && reactiveConfig.getStreamEmitterConstructor() != null) {
                Constructor<? extends StreamEmitter> streamEmitterConstructor = reactiveConfig.getStreamEmitterConstructor();
                Object[] args = getConstructorArgs(streamEmitterConstructor, reactiveConfig, request);
                return streamEmitterConstructor.newInstance(args);
            }
            if (ServerSentEvent.class.isAssignableFrom(elementClass) || containMediaType(MediaType.TEXT_EVENT_STREAM, request, response)) {
                return new SseJsonEmitter(reactiveConfig.getTimeout(), asyncSupportRegistry.getJsonConverter());
            }
            if (CharSequence.class.isAssignableFrom(elementClass)) {
                return new TextStreamEmitter(reactiveConfig.getTimeout());
            }
            if (containMediaType(MediaType.APPLICATION_STREAM_JSON, request, response)) {
                return new StreamJsonEmitter(reactiveConfig.getTimeout(), asyncSupportRegistry.getJsonConverter());
            }
        }
        return null;
    }

    protected Object[] getConstructorArgs(Constructor streamEmitterConstructor, ReactiveConfig reactiveConfig, WebServerHttpRequest request) {
        Object[] args = new Object[streamEmitterConstructor.getParameterCount()];
        for (int index = 0; index < streamEmitterConstructor.getParameterCount(); index++) {
            Class<?> argType = streamEmitterConstructor.getParameterTypes()[index];
            args[index] = getConstructorArg(argType, reactiveConfig, request);
        }
        return args;
    }

    protected Object getConstructorArg(Class<?> argType, ReactiveConfig reactiveConfig, WebServerHttpRequest request) {
        if (JsonConverter.class.equals(argType)) {
            return asyncSupportRegistry.getJsonConverter();
        } else if (long.class.equals(argType) || Long.class.equals(argType)) {
            return reactiveConfig.getTimeout();
        } else {
            throw new IllegalStateException("No supported type : " + argType);
        }
    }

    protected boolean containMediaType(MediaType mediaType, WebServerHttpRequest request, WebServerHttpResponse response) {
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType != null && contentType.includes(contentType)) {
            return true;
        }
        List<MediaType> supportMediaTypeList = getSupportMediaTypeList(request);
        boolean matched = false;
        for (MediaType supported : supportMediaTypeList) {
            if (mediaType.includes(supported)) {
                matched = true;
                break;
            }
        }
        if (matched) {
            if (contentType == null) {
                for (MediaType supported : supportMediaTypeList) {
                    if (supported.isConcrete()) {
                        response.getHeaders().setContentType(supported);
                        break;
                    }
                }
            }
            return true;
        }
        return false;
    }

    protected List<MediaType> getSupportMediaTypeList(WebServerHttpRequest request) {
        PathMappingContext mappingContext = PathMappingContext.get(request);
        if (mappingContext != null && mappingContext.getProducibleMediaTypes() != null) {
            return mappingContext.getProducibleMediaTypes();
        }
        return request.getHeaders().getAccept();
    }

    protected ReactiveConfig getReactiveConfig(WebServerHttpRequest request) {
        MappingHandlerMethod handlerMethod = PathMappingContext.get(request);
        if (handlerMethod != null) {
            ReactiveConfig reactiveConfig = handlerMethod.get(MAPPING_CACHE_KEY);
            if (reactiveConfig == null) {
                ReactiveSupport reactiveSupport = handlerMethod.getMethodAndClassAnnotation(ReactiveSupport.class);
                if (reactiveSupport != null) {
                    reactiveConfig = new ReactiveConfig(reactiveSupport.streamEmitterType(), selectBestConstructor(reactiveConfig.getStreamEmitterType()), reactiveSupport.highWaterMark(), reactiveSupport.lowWaterMark(), reactiveSupport.timeout());
                } else {
                    reactiveConfig = ReactiveConfig.DEFAULT;
                }
            }
            return reactiveConfig;
        }
        return ReactiveConfig.DEFAULT;
    }

    protected boolean isConstructorSupported(Constructor<?> ctor) {
        for (Class<?> type : ctor.getParameterTypes()) {
            if (!SUPPORTED_TYPES.contains(type)) {
                return false;
            }
        }
        return true;
    }

    protected Constructor selectBestConstructor(Class<? extends StreamEmitter> streamEmitterType) {
        if (streamEmitterType == null) {
            return null;
        }
        List<Constructor<?>> supportedConstructor = Arrays.stream(streamEmitterType.getDeclaredConstructors()).filter(this::isConstructorSupported).collect(Collectors.toList());
        supportedConstructor.sort(Comparator.comparingInt(Constructor::getParameterCount));
        Constructor<?> best = supportedConstructor.get(supportedConstructor.size() - 1);
        if (supportedConstructor.size() > 1 && supportedConstructor.get(supportedConstructor.size() - 2).getParameterCount() == best.getParameterCount()) {
            throw new IllegalStateException("Multiple constructors with same max parameter count: " + best.getParameterCount());
        }
        best.setAccessible(true);
        return best;
    }

}
