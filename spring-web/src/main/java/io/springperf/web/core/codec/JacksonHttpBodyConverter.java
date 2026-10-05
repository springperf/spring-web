package io.springperf.web.core.codec;

import com.fasterxml.jackson.annotation.JsonView;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.exc.InvalidDefinitionException;
import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.core.mapping.MappingCacheKey;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.json.MappingJacksonValue;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * High-performance Jackson {@link HttpBodyConverter} that caches {@link JavaType} objects via {@link MappingCacheKey}
 * in {@link PathMappingContext}.
 * <p>
 * Unlike per-instance caches, {@link MappingCacheKey} uses index-based array access on the per-method cache in
 * {@link PathMappingContext}, providing O(1) lookup with zero hashing overhead.
 * <p>
 * Register as a Spring bean to replace Spring's {@code MappingJackson2HttpMessageConverter} with higher priority.
 */
public class JacksonHttpBodyConverter extends BaseWebComponent implements HttpBodyConverter {

    private static final List<MediaType> SUPPORTED_MEDIA_TYPES = Collections
            .unmodifiableList(Arrays.asList(MediaType.APPLICATION_JSON, new MediaType("application", "*+json")));

    private static final MappingCacheKey<JavaType> READ_JAVA_TYPE_CACHE_KEY = MappingCacheKey
            .createMethodCacheKey(JavaType.class);

    private static final MappingCacheKey<Class<?>> JSON_VIEW_CACHE_KEY = MappingCacheKey
            .createMethodCacheKey((Class) Class.class);
    private static final Class<?> NO_JSON_VIEW = Void.class;

    private ObjectMapper mapper;

    public JacksonHttpBodyConverter() {
    }

    public JacksonHttpBodyConverter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void initComponentPhase1() {
        if (mapper == null) {
            mapper = webContext.getBeanFromCtx(ObjectMapper.class);
            if (mapper == null) {
                mapper = io.springperf.web.json.JacksonMappers.defaultMapper();
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 50000;
    }

    @Override
    public Class<? extends HttpMessageConverter<?>> getConverterClass() {
        return JacksonHttpBodyConverter.class;
    }

    // ---- GenericHttpMessageConverter (无上下文时传 null 委托给新 API 兜底) ----

    @Override
    @Deprecated
    public boolean canRead(Type type, @Nullable Class<?> contextClass, MediaType mediaType) {
        return canRead(type, contextClass, mediaType, null, null);
    }

    @Override
    @Deprecated
    public Object read(Type type, @Nullable Class<?> contextClass, HttpInputMessage inputMessage)
            throws IOException, HttpMessageNotReadableException {
        return read(type, contextClass, inputMessage, null, null);
    }

    @Override
    @Deprecated
    public boolean canWrite(Type type, Class<?> valueType, MediaType mediaType) {
        return canWrite(type, valueType, mediaType, null, null, null);
    }

    @Override
    @Deprecated
    public void write(Object value, Type type, @Nullable MediaType contentType, HttpOutputMessage outputMessage)
            throws IOException, HttpMessageNotWritableException {
        write(value, type, contentType, outputMessage, null, null, null);
    }

    @Override
    @Deprecated
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
        return canRead((Type) clazz, null, mediaType, null, null);
    }

    @Override
    @Deprecated
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return canWrite((Type) clazz, clazz, mediaType, null, null, null);
    }

    @Override
    public List<MediaType> getSupportedMediaTypes() {
        return SUPPORTED_MEDIA_TYPES;
    }

    @Override
    public List<MediaType> getSupportedMediaTypes(@Nullable Class<?> clazz) {
        return SUPPORTED_MEDIA_TYPES;
    }

    @Override
    @Deprecated
    public Object read(Class<?> clazz, HttpInputMessage inputMessage)
            throws IOException, HttpMessageNotReadableException {
        return read((Type) clazz, null, inputMessage, null, null);
    }

    @Override
    @Deprecated
    public void write(Object value, @Nullable MediaType contentType, HttpOutputMessage outputMessage)
            throws IOException, HttpMessageNotWritableException {
        write(value, null, contentType, outputMessage, null, null, null);
    }

    // ---- New context-aware API (read 走 MappingCacheKey 缓存 JavaType，write 直接 writeValue) ----

    /**
     * 获取读操作使用的 ObjectMapper，子类可重写以实现请求级/方法级 ObjectMapper 切换。
     */
    protected ObjectMapper getReadObjectMapper(WebServerHttpRequest request, PathMappingContext mappingContext) {
        return mapper;
    }

    /**
     * 获取写操作使用的 ObjectMapper，子类可重写以实现请求级/方法级 ObjectMapper 切换。
     */
    protected ObjectMapper getWriteObjectMapper(WebServerHttpRequest request, PathMappingContext mappingContext) {
        return mapper;
    }

    @Override
    public boolean canRead(Type type, @Nullable Class<?> contextClass, MediaType mediaType,
            WebServerHttpRequest request, PathMappingContext mappingContext) {
        if (!isJsonMediaType(mediaType))
            return false;
        // 注：此处曾有一条 `if (type == String.class) return false;`，让 String 交给
        // StringHttpMessageConverter。但当请求声明 application/json 时，Spring 原生的
        // StringHttpMessageConverter 只支持 text/plain，不会接手，于是所有 converter 都返回
        // false → 400 "not support contentType"。框架也没有其他能读 JSON 下 String 的转换器。
        // 故移除该排除：JSON 请求体里的 String 由 Jackson 正常解析（含引号的 JSON 字符串）。
        // 对照 Spring：MappingJackson2HttpMessageConverter 同样能读 String。
        //
        // 另：Jackson 2 时代的 canDeserialize(javaType) 能力探测在 Jackson 3 已移除。
        // 这里不再做能力预判（与 Spring 7 的 AbstractJacksonHttpMessageConverter 一致——
        // 它也只判断类型/mediaType 兼容，真正不支持时由 readValue 抛异常）。
        // 由此 CAN_DESERIALIZE_CACHE_KEY 这个缓存键失去用途。
        return true;
    }

    @Override
    public Object read(Type type, @Nullable Class<?> contextClass, HttpInputMessage inputMessage,
            WebServerHttpRequest request, PathMappingContext mappingContext)
            throws IOException, HttpMessageNotReadableException {
        return getReadObjectMapper(request, mappingContext).readValue(inputMessage.getBody(),
                resolveReadJavaType(type, mappingContext, request));
    }

    private JavaType resolveReadJavaType(Type type, @Nullable PathMappingContext mappingContext,
            WebServerHttpRequest request) {
        ObjectMapper mapper = getReadObjectMapper(request, mappingContext);
        if (mappingContext != null) {
            JavaType cached = mappingContext.get(READ_JAVA_TYPE_CACHE_KEY);
            if (cached != null) {
                return cached;
            }
        }
        JavaType javaType = mapper.getTypeFactory().constructType(type);
        if (mappingContext != null) {
            mappingContext.set(READ_JAVA_TYPE_CACHE_KEY, javaType);
        }
        return javaType;
    }

    @Override
    public boolean canWrite(Type type, Class<?> valueType, MediaType mediaType, WebServerHttpRequest request,
            @Nullable WebServerHttpResponse response, PathMappingContext mappingContext) {
        if (!isJsonMediaType(mediaType)) {
            return false;
        }
        // Jackson 3 移除了 canSerialize()，故不再做能力预判（与 Spring 7 的
        // AbstractJacksonHttpMessageConverter 一致：只判断类型/mediaType 兼容，
        // 真正不可序列化时由 writeValue 抛异常）。
        // 由此 WRITE_TYPE_SERIALIZABLE_CACHE_KEY 这个缓存键失去用途。
        return true;
    }

    @Override
    public void write(Object value, Type type, @Nullable MediaType contentType, HttpOutputMessage outputMessage,
            WebServerHttpRequest request, WebServerHttpResponse response, PathMappingContext mappingContext)
            throws IOException, HttpMessageNotWritableException {
        // Handle MappingJacksonValue wrapper (from dynamic @JsonView / @JsonFilter)
        Object writeValue = value;
        Class<?> viewClass = null;
        if (value instanceof MappingJacksonValue mjv) {
            writeValue = mjv.getValue();
            viewClass = mjv.getSerializationView();
            // 注：MappingJacksonValue.getFilters() 的类型是 Jackson 2 的
            // com.fasterxml.jackson.databind.ser.FilterProvider（Spring 7.0 自身尚未迁到
            // Jackson 3），而本转换器的 ObjectWriter 是 Jackson 3 的
            // tools.jackson.databind.ser.FilterProvider —— 二者不兼容，无法直接 with(filters)。
            // 故此处不支持 @JsonFilter 的动态过滤。若需要，应在 Jackson 3 侧自行构造
            // SimpleFilterProvider 并在 mapper 上配置。
        }

        // String fast path: skip JSON serialization entirely
        if (writeValue instanceof String str) {
            outputMessage.getBody().write(str.getBytes(StandardCharsets.UTF_8));
            return;
        } else if (writeValue instanceof byte[] bytes) {
            outputMessage.getBody().write(bytes);
            return;
        }

        ObjectMapper writeMapper = getWriteObjectMapper(request, mappingContext);
        ObjectWriter writer;
        if (viewClass != null) {
            writer = writeMapper.writerWithView(viewClass);
        } else {
            writer = resolveWriter(writeMapper, mappingContext);
        }

        try {
            writer.writeValue(outputMessage.getBody(), writeValue);
        } catch (InvalidDefinitionException ex) {
            throw new HttpMessageConversionException("Could not write JSON: " + ex.getType(), ex);
        } catch (JacksonException ex) {
            throw new HttpMessageNotWritableException("Could not write JSON: " + ex.getOriginalMessage(), ex);
        }
    }

    private ObjectWriter resolveWriter(ObjectMapper writeMapper, @Nullable PathMappingContext mappingContext) {
        if (mappingContext == null)
            return writeMapper.writer();
        Class<?> viewClass = resolveJsonViewClass(mappingContext);
        if (viewClass != null) {
            return writeMapper.writerWithView(viewClass);
        }
        return writeMapper.writer();
    }

    private Class<?> resolveJsonViewClass(PathMappingContext mappingContext) {
        Class<?> cached = mappingContext.get(JSON_VIEW_CACHE_KEY);
        if (cached != null) {
            return cached != NO_JSON_VIEW ? cached : null;
        }
        JsonView jsonView = AnnotatedElementUtils.findMergedAnnotation(mappingContext.getMethod(), JsonView.class);
        Class<?> viewClass = (jsonView != null && jsonView.value().length > 0) ? jsonView.value()[0] : null;
        mappingContext.set(JSON_VIEW_CACHE_KEY, viewClass != null ? viewClass : NO_JSON_VIEW);
        return viewClass;
    }

    private static boolean isJsonMediaType(@Nullable MediaType mediaType) {
        if (mediaType == null) {
            return true;
        }
        // 与遍历 SUPPORTED_MEDIA_TYPES isCompatibleWith 等价：
        // 通配类型；或 application 的 json / * / *+json 子类型。
        if (mediaType.isWildcardType()) {
            return true;
        }
        if (!"application".equals(mediaType.getType())) {
            return false;
        }
        String subtype = mediaType.getSubtype();
        return "json".equals(subtype) || "*".equals(subtype) || "*+json".equals(subtype);
    }
}
