package io.springperf.web.http;

import io.springperf.web.context.WebContext;
import io.springperf.web.http.support.BodyHttpInputMessage;
import io.springperf.web.http.support.HttpInputMessagePart;
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

    String getUriStrWithQuery();

    String getUriStr();

    String getPath();

    MultiValueMap<String, String> getParameterMap();

    Map<String, String[]> getParameterMapArray();

    String getParameter(String name);

    String[] getParameterValues(String name);

    MultiValueMap<String, MultipartFile> getMultiFileMap();

    MultiValueMap<String, HttpInputMessagePart> getPartMap();

    Charset getCharacterEncoding();

    void setCharacterEncoding(Charset characterEncoding);

    int getContentLength();

    List<Locale> getLocales();

    Locale getLocale();

    WebContext getWebContext();

    RequestContext getRequestContext();

    void complete();

    boolean isCompleted();
}