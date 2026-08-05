package io.springperf.web.core.codec;

import io.springperf.web.context.WebComponentContainer;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.codec.interceptor.HttpBodyCodecInterceptorRegistry;
import io.springperf.web.core.mapping.MappingCacheKey;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.http.support.BodyHttpInputMessage;
import io.springperf.web.util.MediaTypeUtils;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.*;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Manages HttpBodyConverters and media type negotiation for reading request bodies and writing response bodies.
 */
public class HttpBodyCodecRegistry extends WebComponentContainer {

    private static final List<MediaType> MEDIA_TYPE_LIST_ALL = Collections.singletonList(MediaType.ALL);

    private static final Set<HttpMethod> SUPPORTED_METHODS = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH);

    private static final Object NO_VALUE = new Object();
    protected final List<HttpBodyConverter> converters = new ArrayList<>();

    public static final MappingCacheKey<Type> TARGET_TYPE_CACHE_KEY = MappingCacheKey.createMethodCacheKey(Type.class);

    public static final MappingCacheKey<HttpBodyConverter> READ_BODY_CONVERTER_CACHE_KEY =
            MappingCacheKey.createMethodCacheKey(HttpBodyConverter.class);

    // 方法级内容协商缓存（仅 @Optimize 方法启用，激进优化 opt-in）：
    // 存进 ctx 的 methodCache 数组槽，外层查找是数组索引读（PathMappingContext 按方法共享
    // 实例，首次 computeIfAbsent 后 getCache 即字段读），比 registry 自持
    // ConcurrentHashMap<Method, ...> 更快。命中后仍需对实际 valueType 二次 canWrite 兜底，
    // 兜住方法内运行时类型变化。
    // key 空间 = @Optimize 方法数 × 每方法 Accept 变体数，客户端可控的是后者，故按方法设上限。
    private static final int NEGOTIATION_CACHE_MAX_PER_METHOD = 64;

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static final MappingCacheKey<Map<String, NegotiationCacheEntry>> WRITE_NEGOTIATION_CACHE_KEY =
            (MappingCacheKey) MappingCacheKey.createMethodCacheKey(Map.class);

    // registerConverter 失效用的活表：静态 methodCacheInstanceMap 私有不可遍历，
    // registry 记录自己创建的 inner map，converter 集合变更时逐个 clear。
    // （后续可替换为统一封装。）
    private final Set<Map<String, NegotiationCacheEntry>> liveNegotiationCaches = ConcurrentHashMap.newKeySet();

    protected List<MediaType> allSupportedMediaTypes = new ArrayList<>();

    protected HttpBodyCodecInterceptorRegistry interceptorRegistry;

    private static final class NegotiationCacheEntry {
        final HttpBodyConverter converter;
        final MediaType mediaType;

        NegotiationCacheEntry(HttpBodyConverter converter, MediaType mediaType) {
            this.converter = converter;
            this.mediaType = mediaType;
        }
    }


    private static List<MediaType> getAllSupportedMediaTypes(List<HttpBodyConverter> messageConverters) {
        Set<MediaType> allSupportedMediaTypes = new LinkedHashSet<>();
        for (HttpBodyConverter messageConverter : messageConverters) {
            allSupportedMediaTypes.addAll(messageConverter.getSupportedMediaTypes());
        }
        List<MediaType> result = new ArrayList<>(allSupportedMediaTypes);
        result.sort((a, b) -> {
            int paramsComp = Integer.compare(b.getParameters().size(), a.getParameters().size());
            if (paramsComp != 0) return paramsComp;
            int typeComp = a.getType().compareToIgnoreCase(b.getType());
            if (typeComp != 0) return typeComp;
            return a.getSubtype().compareToIgnoreCase(b.getSubtype());
        });
        return Collections.unmodifiableList(result);
    }

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        interceptorRegistry = webContext.getWebComponentWithDefault(HttpBodyCodecInterceptorRegistry.class, new HttpBodyCodecInterceptorRegistry());
        // 注册高性能 Jackson 转换器
        registerWebComponent(new JacksonHttpBodyConverter());
        registerWebComponent(HttpMessageConverter.class, this::toHttpBodyConverter);
        registerWebComponent(HttpBodyConverter.class, Function.identity());
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
            PathMappingContext ctx = PathMappingContext.get(request);
            HttpBodyConverter cachedConverter = ctx != null ? ctx.get(READ_BODY_CONVERTER_CACHE_KEY) : null;
            if (cachedConverter != null && cachedConverter.canRead(targetType, parameter.getContainingClass(), contentType, request, ctx)) {
                if (msg.hasBody()) {
                    HttpInputMessage msgToUse = interceptorRegistry.beforeBodyRead(request, msg, parameter, targetType, cachedConverter);
                    body = cachedConverter.read(targetType, parameter.getContainingClass(), msgToUse, request, ctx);
                    body = interceptorRegistry.afterBodyRead(request, body, msgToUse, parameter, targetType, cachedConverter);
                } else {
                    body = interceptorRegistry.handleEmptyBodyRead(request, null, msg, parameter, targetType, cachedConverter);
                }
            } else {
                for (HttpBodyConverter converter : this.converters) {
                    if (converter.canRead(targetType, parameter.getContainingClass(), contentType, request, ctx)) {
                        if (ctx != null) {
                            ctx.set(READ_BODY_CONVERTER_CACHE_KEY, converter);
                        }
                        if (msg.hasBody()) {
                            HttpInputMessage msgToUse = interceptorRegistry.beforeBodyRead(request, msg, parameter, targetType, converter);
                            body = converter.read(targetType, parameter.getContainingClass(), msgToUse, request, ctx);
                            body = interceptorRegistry.afterBodyRead(request, body, msgToUse, parameter, targetType, converter);
                        } else {
                            body = interceptorRegistry.handleEmptyBodyRead(request, null, msg, parameter, targetType, converter);
                        }
                        break;
                    }
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
            throw new HttpMessageNotReadableException("not support contentType :" + contentType, null, request);
        }
        return body;
    }

    public void writeBody(Object value, MethodParameter returnType, WebServerHttpRequest request, WebServerHttpResponse response) throws IOException {
        if (value == null) {
            return;
        }
        // 运行时 Optional 解包（方法签名未声明 Optional 但实际返回了）
        if (value instanceof Optional<?> opt) {
            if (opt.isEmpty()) {
                return;
            }
            value = opt.get();
        }
        PathMappingContext ctx = PathMappingContext.get(request);
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
            targetType = ctx != null ? ctx.get(TARGET_TYPE_CACHE_KEY) : null;
            if (targetType == null) {
                targetType = GenericTypeResolver.resolveType(getGenericType(returnType), returnType.getContainingClass());
                if (ctx != null) {
                    ctx.set(TARGET_TYPE_CACHE_KEY, targetType);
                }
            }
        }
        // 1. Content-Type 已设置 → 直接找匹配 converter 写
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType != null && contentType.isConcrete()) {
            writeWithConverter(body, targetType, valueType, contentType, returnType, request, response, ctx);
            return;
        }

        // 2. Content-Type 未设置 → 内容协商（2a produces / 2b 无 produces 统一走方法级协商缓存）
        // 仅 @Optimize 方法启用缓存（ctx.isOptimize() O(1) 判定），key = Accept 头归一，
        // 条目 = (converter, mediaType)。2a 候选集是 produces（声明式收敛），2b 是各 converter
        // 支持的媒体类型（探索式）；miss 时协商逻辑不同，命中后共用"二次 canWrite 兜底 + 写出"，
        // 使 2a 也省去每次请求的 converter 反查遍历（原 writeWithConverter）。
        // 命中后对实际 valueType 二次 canWrite 兜底；失败回退全量协商且不覆盖缓存，
        // 避免同一方法不同运行时类型相互污染缓存条目。ctx 为 null 或非 @Optimize 时降级为全量协商。
        boolean cacheEnabled = ctx != null && ctx.isOptimize();
        String acceptKey = null;
        Map<String, NegotiationCacheEntry> methodCache = null;
        NegotiationCacheEntry cached = null;
        if (cacheEnabled) {
            acceptKey = normalizeAcceptKey(request);
            methodCache = ctx.get(WRITE_NEGOTIATION_CACHE_KEY);
            if (methodCache != null) {
                cached = methodCache.get(acceptKey);
            }
        }
        MediaType bestMediaType = null;
        HttpBodyConverter bestConverter = null;
        if (cached != null && cached.converter.canWrite(targetType, valueType, cached.mediaType, request, null, ctx)) {
            bestMediaType = cached.mediaType;
            bestConverter = cached.converter;
        } else {
            List<MediaType> acceptableTypes = getAcceptableMediaTypes(request);
            List<MediaType> producesTypes = ctx != null ? ctx.getProducibleMediaTypes() : null;
            if (producesTypes != null) {
                // 2a: produces 限定候选媒体类型，先定 mediaType 再按 contentType 反查 converter。
                // 与旧算法一致：只保留 concrete 或 */*、application/*（可映射 JSON）的候选，
                // 跳过 application/*+json 等非 concrete 且不在 ALL 列表中的类型。
                bestMediaType = findBestMatch(acceptableTypes, producesTypes);
                if (bestMediaType != null && !bestMediaType.isConcrete()) {
                    if (isAllApplicationMediaType(bestMediaType)) {
                        bestMediaType = MediaType.APPLICATION_JSON;
                    } else {
                        bestMediaType = null;
                    }
                }
                if (bestMediaType != null) {
                    // 反查 canWrite 传 null response（第 5 参），与下方缓存命中二次校验保持一致：
                    // 若 miss 用真实 response、命中用 null，命中校验会因参数不同而误判 → 每次回退全量。
                    // 内置 converter（Jackson 用 request/ctx，Wrapped/Adapted 委托 3 参）均不依赖 response。
                    for (HttpBodyConverter converter : converters) {
                        if (converter.canWrite(targetType, valueType, bestMediaType, request, null, ctx)) {
                            bestConverter = converter;
                            break;
                        }
                    }
                }
            } else {
                // 2b: 遍历 converters，canWrite + findBestMatch 选最优 (converter, mediaType)
                for (HttpBodyConverter converter : converters) {
                    if (!converter.canWrite(targetType, valueType, null, request, null, ctx)) {
                        continue;
                    }
                    MediaType match = findBestMatch(acceptableTypes, converter.getSupportedMediaTypes());
                    if (match != null) {
                        if (bestMediaType == null || isBetterMatch(match, bestMediaType)) {
                            bestMediaType = match;
                            bestConverter = converter;
                        }
                    }
                }
            }
        }
        if (bestMediaType == null) {
            throw new HttpMessageNotWritableException("only support :" + allSupportedMediaTypes);
        }
        if (!bestMediaType.isConcrete()) {
            if (isAllApplicationMediaType(bestMediaType)) {
                bestMediaType = MediaType.APPLICATION_JSON;
            } else {
                throw new HttpMessageNotWritableException("only support :" + allSupportedMediaTypes);
            }
        }
        if (bestConverter == null) {
            // 仅 2a 可能：produces 协商出 concrete mediaType 但无 converter 支持 → 静默不写
            // （与原 writeWithConverter 找不到 converter 的行为一致）。
            return;
        }
        // 协商成功（含命中校验失败回退）后写缓存；命中成功路径 cached != null，直接跳过。
        // 必须在此处（bestMediaType 已 concrete 化）写入，保证缓存条目与尾段写出的 mediaType 一致。
        if (cached == null && cacheEnabled) {
            if (methodCache == null) {
                methodCache = new ConcurrentHashMap<>();
                ctx.set(WRITE_NEGOTIATION_CACHE_KEY, methodCache);
                liveNegotiationCaches.add(methodCache);
            }
            if (methodCache.size() >= NEGOTIATION_CACHE_MAX_PER_METHOD) {
                methodCache.clear();
            }
            methodCache.put(acceptKey, new NegotiationCacheEntry(bestConverter, bestMediaType));
        }
        bestMediaType = resetContentTypeWithCharset(response.getHeaders(), bestMediaType, response.getCharacterEncoding());
        if (response.getHeaders().getContentType() == null) {
            response.getHeaders().setContentType(bestMediaType);
        }
        body = interceptorRegistry.beforeBodyWrite(body, returnType, bestMediaType, bestConverter, request, response);
        if (body != null) {
            bestConverter.write(body, targetType, bestMediaType, response, request, response, ctx);
        }
    }

    private void writeWithConverter(Object body, Type targetType, Class<?> valueType, MediaType contentType,
                                     MethodParameter returnType, WebServerHttpRequest request,
                                     WebServerHttpResponse response, PathMappingContext ctx) throws IOException {
        for (HttpBodyConverter converter : converters) {
            if (converter.canWrite(targetType, valueType, contentType, request, response, ctx)) {
                body = interceptorRegistry.beforeBodyWrite(body, returnType, contentType, converter, request, response);
                if (body != null) {
                    converter.write(body, targetType, contentType, response, request, response, ctx);
                }
                return;
            }
        }
        // Content-Type 已明确指定但无匹配 converter 时，保持与旧实现一致：静默不写响应体。
        // （旧 chooseWriteMediaType 路径在 content-type concrete 时同样找不到 converter 也会静默返回。）
    }

    protected List<MediaType> getAcceptableMediaTypes(WebServerHttpRequest request) {
        return request.getHeaders().getAccept();
    }

    /**
     * Accept 头归一为缓存 key：缺失/空白统一为通配类型，大小写归一。
     * （MediaType 解析对 type/subtype 不区分大小写，raw 字符串归一后可复用同一条目。）
     * <p>必须用规范大小写 "Accept" 读取：旧拷贝到大小写敏感的 LinkedMultiValueMap 后，
     * 小写 key 读取永远 miss（Netty 迭代保留原始大小写），导致缓存 key 恒为通配类型，
     * 多格式 endpoint 下不同 Accept 会跨条目复用错误 converter。</p>
     */
    private static String normalizeAcceptKey(WebServerHttpRequest request) {
        String accept = request.getHeaders().getFirst("Accept");
        if (accept == null) {
            return "*/*";
        }
        String normalized = accept.trim();
        if (normalized.isEmpty()) {
            return "*/*";
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private MediaType findBestMatch(List<MediaType> acceptableTypes, List<MediaType> supportedTypes) {
        if (acceptableTypes.isEmpty()) {
            acceptableTypes = MEDIA_TYPE_LIST_ALL;
        }
        // 快捷路径：Accept 全 concrete 时，兼容 supported 的协商结果恒等于 acceptable 本身
        // （getMostSpecificMediaType 因 acceptable 不弱于 produce 而返回 acceptable），
        // 省去对每个 supported 的 getMostSpecificMediaType 调用，仅按 isBetterMatch 在 acceptable 间选优。
        if (isAllConcrete(acceptableTypes)) {
            MediaType best = null;
            for (MediaType acceptable : acceptableTypes) {
                if (isCompatibleWithAny(acceptable, supportedTypes)
                        && (best == null || isBetterMatch(acceptable, best))) {
                    best = acceptable;
                }
            }
            return best;
        }
        MediaType best = null;
        for (MediaType acceptable : acceptableTypes) {
            for (MediaType supported : supportedTypes) {
                if (acceptable.isCompatibleWith(supported)) {
                    MediaType result = getMostSpecificMediaType(acceptable, supported);
                    // 只保留可用候选：concrete，或 */*、application/*（可映射为 JSON）。
                    // 跳过 application/*+json 等非 concrete 且不可映射的类型，
                    // 与旧算法“排序后取第一个 concrete/ALL 候选”行为一致。
                    if (result.isConcrete() || isAllApplicationMediaType(result)) {
                        if (best == null || isBetterMatch(result, best)) {
                            best = result;
                        }
                    }
                }
            }
        }
        return best;
    }

    private static boolean isAllConcrete(List<MediaType> types) {
        for (MediaType type : types) {
            if (!type.isConcrete()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCompatibleWithAny(MediaType acceptable, List<MediaType> supportedTypes) {
        for (MediaType supported : supportedTypes) {
            if (acceptable.isCompatibleWith(supported)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 等价于 {@code mediaType.isPresentIn(ALL_APPLICATION_MEDIA_TYPES)}：
     * 无参数的 *&#47;* 或 application/*。避免 MediaType.equals 的完整参数比较与集合遍历。
     */
    private static boolean isAllApplicationMediaType(MediaType mediaType) {
        if (!mediaType.getParameters().isEmpty()) {
            return false;
        }
        return mediaType.isWildcardSubtype()
                && (mediaType.isWildcardType() || "application".equalsIgnoreCase(mediaType.getType()));
    }

    /**
     * 比较两个 MediaType，用与旧算法 sort 一致的优先级：
     * quality(高→低) → params(多→少) → type(A→Z) → subtype(A→Z)
     */
    private static boolean isBetterMatch(MediaType candidate, MediaType current) {
        int cmp = Double.compare(candidate.getQualityValue(), current.getQualityValue());
        if (cmp != 0) return cmp > 0;
        cmp = Integer.compare(candidate.getParameters().size(), current.getParameters().size());
        if (cmp != 0) return cmp > 0;
        cmp = candidate.getType().compareToIgnoreCase(current.getType());
        if (cmp != 0) return cmp < 0;
        cmp = candidate.getSubtype().compareToIgnoreCase(current.getSubtype());
        return cmp < 0;
    }

    protected MediaType getMostSpecificMediaType(MediaType acceptType, MediaType produceType) {
        MediaType produceTypeToUse = produceType.copyQualityValue(acceptType);
        return (compareSpecificity(acceptType, produceTypeToUse) <= 0 ? acceptType : produceTypeToUse);
    }

    private static int compareSpecificity(MediaType a, MediaType b) {
        return MediaTypeUtils.compareSpecificity(a, b);
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
        // converter 集合变更 → 所有方法的协商结果可能变化，清空 registry 创建的协商缓存
        for (Map<String, NegotiationCacheEntry> c : liveNegotiationCaches) {
            c.clear();
        }
    }
}
