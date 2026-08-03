package io.springperf.web.core.codec;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.ser.FilterProvider;
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
 * High-performance Jackson {@link HttpBodyConverter} that caches {@link JavaType} objects
 * via {@link MappingCacheKey} in {@link PathMappingContext}.
 * <p>
 * Unlike per-instance caches, {@link MappingCacheKey} uses index-based array access
 * on the per-method cache in {@link PathMappingContext}, providing O(1) lookup
 * with zero hashing overhead.
 * <p>
 * Register as a Spring bean to replace Spring's {@code MappingJackson2HttpMessageConverter}
 * with higher priority.
 */
public class JacksonHttpBodyConverter extends BaseWebComponent implements HttpBodyConverter {

    private static final List<MediaType> SUPPORTED_MEDIA_TYPES = Collections.unmodifiableList(
            Arrays.asList(MediaType.APPLICATION_JSON, new MediaType("application", "*+json")));

    private static final MappingCacheKey<JavaType> READ_JAVA_TYPE_CACHE_KEY =
            MappingCacheKey.createMethodCacheKey(JavaType.class);
    private static final MappingCacheKey<Boolean> WRITE_TYPE_SERIALIZABLE_CACHE_KEY =
            MappingCacheKey.createMethodCacheKey(Boolean.class);

    private static final MappingCacheKey<Class<?>> JSON_VIEW_CACHE_KEY =
            MappingCacheKey.createMethodCacheKey((Class) Class.class);
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
                mapper = new ObjectMapper();
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
        if (!isJsonMediaType(mediaType)) return false;
        // String 类型应由 StringHttpMessageConverter 处理，而非 Jackson
        if (type == String.class) return false;
        return getReadObjectMapper(request, mappingContext)
                .canDeserialize(resolveReadJavaType(type, mappingContext, request));
    }

    @Override
    public Object read(Type type, @Nullable Class<?> contextClass, HttpInputMessage inputMessage,
                       WebServerHttpRequest request, PathMappingContext mappingContext)
            throws IOException, HttpMessageNotReadableException {
        return getReadObjectMapper(request, mappingContext).readValue(
                inputMessage.getBody(), resolveReadJavaType(type, mappingContext, request));
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
    public boolean canWrite(Type type, Class<?> valueType, MediaType mediaType,
                             WebServerHttpRequest request, WebServerHttpResponse response,
                             PathMappingContext mappingContext) {
        if (!isJsonMediaType(mediaType)) {
            return false;
        }
        // 声明返回类型是具体类 → 缓存 canSerialize 结果，后续跳过
        // 声明返回类型是 Object/接口/泛型变量 → 每次按运行时 valueType 判断
        ObjectMapper writeMapper = getWriteObjectMapper(request, mappingContext);
        if (mappingContext != null) {
            Boolean cached = mappingContext.get(WRITE_TYPE_SERIALIZABLE_CACHE_KEY);
            if (cached != null) {
                if (cached) return true;
                // false → 声明类型不可序列化，但运行时类型可能不同，继续 valueType 判断
            } else {
                Class<?> rawClass = writeMapper.getTypeFactory().constructType(type).getRawClass();
                if (rawClass != Object.class) {
                    boolean serializable = writeMapper.canSerialize(rawClass);
                    mappingContext.set(WRITE_TYPE_SERIALIZABLE_CACHE_KEY, serializable);
                    if (serializable) return true;
                    // false → 缓存结果避免重复 constructType，但继续 valueType 判断
                }
            }
        }
        return writeMapper.canSerialize(valueType);
    }

    @Override
    public void write(Object value, Type type, @Nullable MediaType contentType, HttpOutputMessage outputMessage,
                       WebServerHttpRequest request, WebServerHttpResponse response,
                       PathMappingContext mappingContext)
            throws IOException, HttpMessageNotWritableException {
        // Handle MappingJacksonValue wrapper (from dynamic @JsonView / @JsonFilter)
        Object writeValue = value;
        Class<?> viewClass = null;
        FilterProvider filters = null;
        if (value instanceof MappingJacksonValue) {
            MappingJacksonValue mjv = (MappingJacksonValue) value;
            writeValue = mjv.getValue();
            viewClass = mjv.getSerializationView();
            filters = mjv.getFilters();
        }

        // String fast path: skip JSON serialization entirely
        if (writeValue instanceof String) {
            outputMessage.getBody().write(((String) writeValue).getBytes(StandardCharsets.UTF_8));
            return;
        }

        ObjectMapper writeMapper = getWriteObjectMapper(request, mappingContext);
        ObjectWriter writer;
        if (viewClass != null) {
            writer = writeMapper.writerWithView(viewClass);
        } else {
            writer = resolveWriter(writeMapper, mappingContext);
        }
        if (filters != null) {
            writer = writer.with(filters);
        }

        try {
            writer.writeValue(outputMessage.getBody(), writeValue);
        } catch (InvalidDefinitionException ex) {
            throw new HttpMessageConversionException(
                    "Could not write JSON: " + ex.getType(), ex);
        } catch (JsonProcessingException ex) {
            throw new HttpMessageNotWritableException(
                    "Could not write JSON: " + ex.getOriginalMessage(), ex);
        }
    }

    private ObjectWriter resolveWriter(ObjectMapper writeMapper, @Nullable PathMappingContext mappingContext) {
        if (mappingContext == null) return writeMapper.writer();
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
        JsonView jsonView = AnnotatedElementUtils.findMergedAnnotation(
                mappingContext.getMethod(), JsonView.class);
        Class<?> viewClass = (jsonView != null && jsonView.value().length > 0)
                ? jsonView.value()[0] : null;
        mappingContext.set(JSON_VIEW_CACHE_KEY, viewClass != null ? viewClass : NO_JSON_VIEW);
        return viewClass;
    }

    private static boolean isJsonMediaType(@Nullable MediaType mediaType) {
        if (mediaType == null) {
            return true;
        }
        for (MediaType supported : SUPPORTED_MEDIA_TYPES) {
            if (supported.isCompatibleWith(mediaType)) {
                return true;
            }
        }
        return false;
    }
}