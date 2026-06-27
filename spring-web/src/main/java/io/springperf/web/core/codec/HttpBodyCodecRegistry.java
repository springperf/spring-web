package io.springperf.web.core.codec;

import io.springperf.web.context.WebComponentContainer;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.codec.interceptor.HttpBodyCodecInterceptorRegistry;
import io.springperf.web.core.mapping.MappingCacheKey;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.http.support.BodyHttpInputMessage;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.*;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Manages HttpBodyConverters and media type negotiation for reading request bodies and writing response bodies.
 */
public class HttpBodyCodecRegistry extends WebComponentContainer {

    private static final List<MediaType> ALL_APPLICATION_MEDIA_TYPES = Arrays.asList(MediaType.ALL, new MediaType("application"));

    private static final Set<HttpMethod> SUPPORTED_METHODS = EnumSet.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH);

    private static final Object NO_VALUE = new Object();
    protected final List<HttpBodyConverter> converters = new ArrayList<>();

    public static final MappingCacheKey<Type> TARGET_TYPE_CACHE_KEY = MappingCacheKey.createMethodCacheKey(Type.class);

    protected List<MediaType> allSupportedMediaTypes = new ArrayList<>();

    protected HttpBodyCodecInterceptorRegistry interceptorRegistry;


    private static List<MediaType> getAllSupportedMediaTypes(List<HttpBodyConverter> messageConverters) {
        Set<MediaType> allSupportedMediaTypes = new LinkedHashSet<>();
        for (HttpBodyConverter messageConverter : messageConverters) {
            allSupportedMediaTypes.addAll(messageConverter.getSupportedMediaTypes());
        }
        List<MediaType> result = new ArrayList<>(allSupportedMediaTypes);
        MediaType.sortBySpecificity(result);
        return Collections.unmodifiableList(result);
    }

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        interceptorRegistry = webContext.getWebComponentWithDefault(HttpBodyCodecInterceptorRegistry.class, new HttpBodyCodecInterceptorRegistry());
        registerWebComponent(HttpMessageConverter.class, this::toHttpBodyConverter);
    }

    @Override
    public void initComponentPhase2() throws Exception {
        super.initComponentPhase2();
        initRealComponentList(converters, HttpBodyConverter.class);
        this.allSupportedMediaTypes = getAllSupportedMediaTypes(converters);
    }

    protected HttpBodyConverter toHttpBodyConverter(HttpMessageConverter converter) {
        if (converter instanceof GenericHttpMessageConverter) {
            GenericHttpMessageConverter genericConverter = (GenericHttpMessageConverter) converter;
            return new WrappedHttpBodyConverter(genericConverter);
        } else {
            return new AdaptedHttpBodyConverter(converter);
        }
    }

    private static MediaType resetContentTypeWithCharset(HttpHeaders headers, MediaType contentType, Charset setCharset) {
        if (setCharset != null) {
            Charset contentTypeCharset = contentType.getCharset();
            boolean modifyCharset = false;
            if (contentTypeCharset == null) {
                if (!Objects.equals(StandardCharsets.UTF_8, setCharset)) {
                    modifyCharset = true;
                }
            } else if (!Objects.equals(contentTypeCharset, setCharset)) {
                modifyCharset = true;
            }
            if (modifyCharset) {
                contentType = new MediaType(contentType, setCharset);
                headers.setContentType(contentType);
            }
        }
        return contentType;
    }

    public Object readBody(Type targetType, MethodParameter parameter, BodyHttpInputMessage msg, WebServerHttpRequest request) {
        MediaType contentType = msg.getHeaders().getContentType();
        boolean noContentType = false;
        if (contentType == null) {
            noContentType = true;
            contentType = MediaType.APPLICATION_JSON;
        }
        contentType = resetContentTypeWithCharset(msg.getHeaders(), contentType, request.getCharacterEncoding());
        Object body = NO_VALUE;
        try {
            for (HttpBodyConverter converter : this.converters) {
                if (converter.canRead(targetType, parameter.getContainingClass(), contentType)) {
                    if (msg.hasBody()) {
                        HttpInputMessage msgToUse = interceptorRegistry.beforeBodyRead(request, msg, parameter, targetType, converter);
                        body = converter.read(targetType, parameter.getContainingClass(), msgToUse);
                        body = interceptorRegistry.afterBodyRead(request, body, msgToUse, parameter, targetType, converter);
                    } else {
                        body = interceptorRegistry.handleEmptyBodyRead(request, null, msg, parameter, targetType, converter);
                    }
                    break;
                }
            }
        } catch (IOException ex) {
            throw new HttpMessageNotReadableException("I/O error while reading input message", ex, request);
        }
        HttpMethod httpMethod = request.getMethod();
        if (body == NO_VALUE) {
            if (httpMethod == null || !SUPPORTED_METHODS.contains(httpMethod) ||
                    (noContentType && !request.hasBody())) {
                return null;
            }
            throw new HttpMessageNotReadableException("not support contentType :" + contentType);
        }
        return body;
    }

    public void writeBody(Object value, MethodParameter returnType, WebServerHttpRequest request, WebServerHttpResponse response) throws IOException {
        Object body;
        Class<?> valueType;
        Type targetType;
        if (value instanceof CharSequence) {
            body = value.toString();
            valueType = String.class;
            targetType = String.class;
        } else {
            body = value;
            valueType = body.getClass();
            PathMappingContext ctx = PathMappingContext.get(request);
            targetType = ctx != null ? ctx.get(TARGET_TYPE_CACHE_KEY) : null;
            if (targetType == null) {
                targetType = GenericTypeResolver.resolveType(getGenericType(returnType), returnType.getContainingClass());
                if (ctx != null) {
                    ctx.set(TARGET_TYPE_CACHE_KEY, targetType);
                }
            }
        }
        MediaType selectedMediaType = chooseWriteMediaType(body, valueType, targetType, request, response);
        if (selectedMediaType == null) {
            if (body != null) {
                throw new HttpMessageNotWritableException("only support :" + allSupportedMediaTypes);
            }
            return;
        }
        selectedMediaType = resetContentTypeWithCharset(response.getHeaders(), selectedMediaType, response.getCharacterEncoding());
        for (HttpBodyConverter converter : converters) {
            if (converter.canWrite(targetType, valueType, selectedMediaType)) {
                body = interceptorRegistry.beforeBodyWrite(body, returnType, selectedMediaType, converter, request, response);
                if (body != null) {
                    converter.write(body, targetType, selectedMediaType, response);
                    break;
                }
            }
        }
    }

    private static final List<MediaType> MEDIA_TYPE_LIST_ALL = Collections.singletonList(MediaType.ALL);

    protected MediaType chooseWriteMediaType(Object body, Class<?> valueType, Type targetType, WebServerHttpRequest request, WebServerHttpResponse response) {
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType != null && contentType.isConcrete()) {
            return contentType;
        }
        List<MediaType> acceptableTypes = getAcceptableMediaTypes(request);
        if (acceptableTypes.isEmpty()) {
            {
                acceptableTypes = MEDIA_TYPE_LIST_ALL;
            }
        }
        List<MediaType> producibleTypes = getProducibleMediaTypes(request, valueType, targetType);
        List<MediaType> mediaTypesToUse = new ArrayList<>();
        for (MediaType requestedType : acceptableTypes) {
            for (MediaType producibleType : producibleTypes) {
                if (requestedType.isCompatibleWith(producibleType)) {
                    mediaTypesToUse.add(getMostSpecificMediaType(requestedType, producibleType));
                }
            }
        }
        if (mediaTypesToUse.isEmpty()) {
            return null;
        }
        MediaType.sortBySpecificityAndQuality(mediaTypesToUse);

        for (MediaType mediaType : mediaTypesToUse) {
            if (mediaType.isConcrete()) {
                return mediaType;
            } else if (mediaType.isPresentIn(ALL_APPLICATION_MEDIA_TYPES)) {
                return MediaType.APPLICATION_JSON_UTF8;
            }
        }
        return null;
    }

    protected List<MediaType> getAcceptableMediaTypes(WebServerHttpRequest request) {
        return request.getHeaders().getAccept();
    }

    protected List<MediaType> getProducibleMediaTypes(
            WebServerHttpRequest request, Class<?> valueClass, @Nullable Type targetType) {
        PathMappingContext mappingContext = PathMappingContext.get(request);
        if (mappingContext != null) {
            List<MediaType> mediaTypes = mappingContext.getProducibleMediaTypes();
            if (mediaTypes != null) {
                return mediaTypes;
            }
        }
        if (!this.allSupportedMediaTypes.isEmpty()) {
            List<MediaType> result = new ArrayList<>();
            for (HttpBodyConverter converter : this.converters) {
                if (converter.canWrite(targetType, valueClass, null)) {
                    result.addAll(converter.getSupportedMediaTypes());
                }
            }
            return result;
        } else {
            return MEDIA_TYPE_LIST_ALL;
        }
    }

    protected MediaType getMostSpecificMediaType(MediaType acceptType, MediaType produceType) {
        MediaType produceTypeToUse = produceType.copyQualityValue(acceptType);
        return (MediaType.SPECIFICITY_COMPARATOR.compare(acceptType, produceTypeToUse) <= 0 ? acceptType : produceTypeToUse);
    }

    protected Type getGenericType(MethodParameter returnType) {
        if (HttpEntity.class.isAssignableFrom(returnType.getParameterType())) {
            return ResolvableType.forType(returnType.getGenericParameterType()).getGeneric().getType();
        } else {
            return returnType.getGenericParameterType();
        }
    }

    public List<HttpBodyConverter> getConverters() {
        return converters;
    }

    public void registerConverter(HttpBodyConverter converter) {
        registerWebComponent(converter);
        initRealComponentList(converters, HttpBodyConverter.class);
        this.allSupportedMediaTypes = getAllSupportedMediaTypes(converters);
    }
}
