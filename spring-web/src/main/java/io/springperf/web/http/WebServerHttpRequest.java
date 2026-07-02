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