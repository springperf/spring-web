package io.springperf.web.http;

import io.springperf.web.context.WebContext;
import io.springperf.web.http.support.HttpInputMessagePart;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.ServerHttpAsyncRequestControl;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WebServerHttpRequestWrapper implements WebServerHttpRequest {

    private final WebServerHttpRequest request;

    public WebServerHttpRequestWrapper(WebServerHttpRequest request) { this.request = request; }
    public WebServerHttpRequest getRequest() { return request; }

    @Override public String getUriStrWithQuery() { return request.getUriStrWithQuery(); }
    @Override public String getUriStr() { return request.getUriStr(); }
    @Override public String getPath() { return request.getPath(); }
    @Override public MultiValueMap<String, String> getParameterMap() { return request.getParameterMap(); }
    @Override public Map<String, String[]> getParameterMapArray() { return request.getParameterMapArray(); }
    @Override public String getParameter(String name) { return request.getParameter(name); }
    @Override public String[] getParameterValues(String name) { return request.getParameterValues(name); }
    @Override public MultiValueMap<String, MultipartFile> getMultiFileMap() { return request.getMultiFileMap(); }
    @Override public MultiValueMap<String, HttpInputMessagePart> getPartMap() { return request.getPartMap(); }
    @Override public Charset getCharacterEncoding() { return request.getCharacterEncoding(); }
    @Override public void setCharacterEncoding(Charset characterEncoding) { request.setCharacterEncoding(characterEncoding); }
    @Override public int getContentLength() { return request.getContentLength(); }
    @Override public List<Locale> getLocales() { return request.getLocales(); }
    @Override public Locale getLocale() { return request.getLocale(); }
    @Override public WebContext getWebContext() { return request.getWebContext(); }
    @Override public RequestContext getRequestContext() { return request.getRequestContext(); }
    @Override @Nullable public Principal getPrincipal() { return request.getPrincipal(); }
    @Override public InetSocketAddress getLocalAddress() { return request.getLocalAddress(); }
    @Override public InetSocketAddress getRemoteAddress() { return request.getRemoteAddress(); }
    @Override public ServerHttpAsyncRequestControl getAsyncRequestControl(ServerHttpResponse response) { return request.getAsyncRequestControl(response); }
    @Override @Nullable public HttpMethod getMethod() { return request.getMethod(); }
    @Override public String getMethodValue() { return request.getMethodValue(); }
    @Override public URI getURI() { return request.getURI(); }
    @Override public HttpHeaders getHeaders() { return request.getHeaders(); }
    @Override public InputStream getBody() throws IOException { return request.getBody(); }
    @Override public boolean hasBody() { return request.hasBody(); }
    @Override public void complete() { request.complete(); }
    @Override public boolean isCompleted() { return request.isCompleted(); }
}