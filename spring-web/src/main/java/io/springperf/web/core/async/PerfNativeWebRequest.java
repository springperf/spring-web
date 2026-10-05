package io.springperf.web.core.async;

import java.security.Principal;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import org.springframework.lang.Nullable;
import org.springframework.web.context.request.NativeWebRequest;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

public class PerfNativeWebRequest implements NativeWebRequest {

    protected WebServerHttpRequest request;

    protected WebServerHttpResponse response;

    PerfNativeWebRequest(WebServerHttpRequest request, @Nullable WebServerHttpResponse response) {
        this.request = request;
        this.response = response;
    }

    @Override
    public Object getNativeRequest() {
        return request;
    }

    @Override
    public Object getNativeResponse() {
        return response;
    }

    @Override
    public <T> T getNativeRequest(@Nullable Class<T> requiredType) {
        // NativeWebRequest 契约允许 requiredType 为 null（上游标注 @Nullable），语义为“不限定类型”
        if (requiredType == null || requiredType.isAssignableFrom(request.getClass())) {
            return (T) request;
        }
        return null;
    }

    @Override
    public <T> T getNativeResponse(@Nullable Class<T> requiredType) {
        if (requiredType == null || requiredType.isAssignableFrom(response.getClass())) {
            return (T) response;
        }
        return null;
    }

    @Override
    public Object getAttribute(String name, int scope) {
        return request.getRequestContext().getAttribute(name);
    }

    @Override
    public void setAttribute(String name, Object value, int scope) {
        request.getRequestContext().setAttribute(name, value);
    }

    @Override
    public void removeAttribute(String name, int scope) {
        request.getRequestContext().removeAttribute(name);
    }

    @Override
    public String[] getAttributeNames(int scope) {
        return request.getRequestContext().getAttributes().keySet().toArray(new String[0]);
    }

    @Override
    public String getHeader(String headerName) {
        return request.getHeaders().getFirst(headerName);
    }

    @Override
    public String[] getHeaderValues(String headerName) {
        // MultiValueMap.get 声明为 @Nullable：缺失的头应返回空数组，而不是 NPE
        java.util.List<String> values = request.getHeaders().get(headerName);
        return values != null ? values.toArray(new String[0]) : new String[0];
    }

    @Override
    public Iterator<String> getHeaderNames() {
        // Spring 7 移除了 HttpHeaders.keySet()（不再是 MultiValueMap）。改用 headerSet() ——
        // 该 API 在 Spring 6 / 7 均存在且签名一致，返回头名到多值的条目集合。
        Iterator<java.util.Map.Entry<String, java.util.List<String>>> it = request.getHeaders().headerSet().iterator();
        return new Iterator<String>() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public String next() {
                return it.next().getKey();
            }
        };
    }

    @Override
    public String getParameter(String paramName) {
        return request.getParameter(paramName);
    }

    @Override
    public String[] getParameterValues(String paramName) {
        return request.getParameterValues(paramName);
    }

    @Override
    public Iterator<String> getParameterNames() {
        return request.getParameterMap().keySet().iterator();
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return request.getParameterMapArray();
    }

    @Override
    public Locale getLocale() {
        return request.getLocale();
    }

    @Override
    public String getContextPath() {
        return request.getWebContext().getContextPath();
    }

    @Override
    public String getRemoteUser() {
        return null;
    }

    @Override
    public Principal getUserPrincipal() {
        return null;
    }

    @Override
    public boolean isUserInRole(String role) {
        return false;
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public boolean checkNotModified(long lastModifiedTimestamp) {
        return false;
    }

    @Override
    public boolean checkNotModified(String etag) {
        return false;
    }

    @Override
    public boolean checkNotModified(String etag, long lastModifiedTimestamp) {
        return false;
    }

    @Override
    public String getDescription(boolean includeClientInfo) {
        return null;
    }

    @Override
    public void registerDestructionCallback(String name, Runnable callback, int scope) {

    }

    @Override
    public Object resolveReference(String key) {
        return null;
    }

    @Override
    public String getSessionId() {
        return null;
    }

    @Override
    public Object getSessionMutex() {
        return null;
    }
}
