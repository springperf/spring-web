package io.springperf.web.http;

import io.springperf.web.context.WebContext;
import io.springperf.web.http.support.BodyHttpInputMessage;
import io.springperf.web.http.support.HttpInputMessagePart;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side HTTP request abstraction for the perf web framework.
 * <p>
 * Extends Spring's {@link ServerHttpRequest} with additional methods for
 * accessing URI parts, parameters, multipart files, locales, and the
 * underlying {@link WebContext}. Also extends {@link BodyHttpInputMessage}
 * for body reading support.
 */
public interface WebServerHttpRequest extends ServerHttpRequest, BodyHttpInputMessage {

    /**
     * Return the full request URI including the query string.
     *
     * @return the URI with query string (e.g. {@code /api/users?id=1})
     */
    String getUriStrWithQuery();

    /**
     * Return the request URI path without the query string.
     *
     * @return the URI path (e.g. {@code /api/users})
     */
    String getUriStr();

    /**
     * Return the decoded request path, with context path stripped.
     *
     * @return the decoded path
     */
    String getPath();

    /**
     * Return all request parameters as a multi-value map.
     *
     * @return the parameter map (never {@code null})
     */
    MultiValueMap<String, String> getParameterMap();

    /**
     * Return all request parameters as a String array map.
     *
     * @return the parameter map (never {@code null})
     */
    Map<String, String[]> getParameterMapArray();

    /**
     * Return the first value of the named parameter.
     *
     * @param name the parameter name
     * @return the first value, or {@code null} if the parameter is absent
     */
    String getParameter(String name);

    /**
     * Return all values of the named parameter.
     *
     * @param name the parameter name
     * @return the values array, or {@code null} if the parameter is absent
     */
    String[] getParameterValues(String name);

    /**
     * Return the uploaded files as a multi-value map.
     *
     * @return the multipart file map (never {@code null})
     */
    MultiValueMap<String, MultipartFile> getMultiFileMap();

    /**
     * Return the multipart parts as a multi-value map.
     *
     * @return the part map (never {@code null})
     */
    MultiValueMap<String, HttpInputMessagePart> getPartMap();

    /**
     * Return the character encoding of the request body.
     *
     * @return the character encoding, or {@code null} if not set
     */
    Charset getCharacterEncoding();

    /**
     * Set the character encoding for reading the request body.
     *
     * @param characterEncoding the encoding to use
     */
    void setCharacterEncoding(Charset characterEncoding);

    /**
     * Return the content length in bytes.
     *
     * @return the content length, or -1 if unknown
     */
    int getContentLength();

    /**
     * Return the list of locales from the {@code Accept-Language} header.
     *
     * @return the locales list (never {@code null})
     */
    List<Locale> getLocales();

    /**
     * Return the preferred locale from the {@code Accept-Language} header.
     *
     * @return the preferred locale
     */
    Locale getLocale();

    /**
     * Return the {@link WebContext} associated with this request.
     *
     * @return the web context
     */
    WebContext getWebContext();

    /**
     * Return the {@link RequestContext} for this request.
     *
     * <p>The request context holds request-scoped attributes that live for
     * the duration of the request lifecycle.</p>
     *
     * @return the request context
     */
    RequestContext getRequestContext();

    /**
     * Return the attributes map for this request.
     *
     * <p>Spring 6.2+ {@code HttpRequest} declares this method as abstract.
     * This declaration ensures cross-version compatibility without {@code @Override}.
     */
    Map<String, Object> getAttributes();

    /**
     * Return the HTTP method value as a String.
     * <p>Default implementation derives the value from {@link #getMethod()}.</p>
     *
     * @return the HTTP method value (e.g. "GET", "POST"), or {@code null} if not available
     */
    default String getMethodValue() {
        HttpMethod method = getMethod();
        return method != null ? method.name() : null;
    }

    /**
     * 是否为 HEAD 请求（已按 RFC 7231 §4.3.2 映射到支持 GET 的处理器）。
     * <p>由 {@link io.springperf.web.core.mapping.match.HttpMethodMatcher} 在路由匹配时统一标记，
     * 后续处理（资源元数据、响应 body 抑制等）只需读此标志，无需重复判断 HTTP 方法。</p>
     *
     * @return {@code true} 表示当前是 HEAD 请求
     */
    default boolean isHeadRequest() {
        return false;
    }

    /**
     * 标记当前请求为 HEAD 请求。由路由层在匹配到支持 GET（或显式 HEAD）的处理器时调用。
     * <p>默认空实现（对未实现字段的请求无害）；{@link BaseWebServerHttpRequest} 覆盖以记录标志。</p>
     */
    default void markAsHeadRequest() {
        // 默认无操作
    }

    /**
     * Retain the underlying Netty ByteBuf reference count.
     *
     * <p>Must be called before offloading request processing to a separate
     * thread (e.g., a business thread pool) to prevent premature release
     * by the I/O thread.</p>
     */
    void acquire();

    /**
     * Release the underlying Netty ByteBuf reference count.
     *
     * <p>Each call to {@link #acquire()} must be paired with a corresponding
     * call to {@code release()} when the offloaded processing completes.</p>
     *
     * @return {@code true} if the reference count reached zero and the buffer was freed
     */
    boolean release();
}