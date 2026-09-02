package io.springperf.web.core.mapping.match;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import org.springframework.http.HttpMethod;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class HttpMethodMatcher implements Matcher {

    private Set<HttpMethod> httpMethods;

    public HttpMethodMatcher(HttpMethod[] httpMethods) {
        this.httpMethods = new HashSet<>(Arrays.asList(httpMethods));
    }


    @Override
    public boolean match(WebServerHttpRequest req, PathMappingContext mappingContext) {
        HttpMethod method = req.getMethod();
        if (HttpMethod.HEAD == method) {
            // HEAD 语义：无显式 HEAD handler 时映射到 GET（RFC 7231 §4.3.2，
            // 与 Spring MVC 一致——HEAD 请求返回与 GET 相同的响应头，但无响应体）。
            // 显式声明了 HEAD 或可映射到 GET 均算匹配成功，并统一在路由层标记请求。
            if (httpMethods.contains(HttpMethod.HEAD) || httpMethods.contains(HttpMethod.GET)) {
                req.markAsHeadRequest();
                return true;
            }
            return false;
        }
        return httpMethods.contains(method);
    }

    @Override
    public boolean isSameTypeMatcher(Matcher matcher) {
        return matcher instanceof HttpMethodMatcher;
    }

    @Override
    public boolean haveAmbiguous(Matcher matcher) {
        if (matcher instanceof HttpMethodMatcher) {
            HttpMethodMatcher httpMethodMatcher = (HttpMethodMatcher) matcher;
            return httpMethods.containsAll(httpMethodMatcher.httpMethods) || httpMethodMatcher.httpMethods.containsAll(httpMethods);
        }
        return false;
    }

    public Set<HttpMethod> getHttpMethods() {
        return httpMethods;
    }

    @Override
    public String toString() {
        String[] methods = httpMethods.stream().map(HttpMethod::toString).toArray(String[]::new);
        if (methods.length == 1) {
            return methods[0];
        }
        return Arrays.toString(methods);
    }
}