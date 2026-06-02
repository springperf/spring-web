package io.springperf.web.core.mapping.match;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import org.springframework.http.HttpMethod;

import java.util.Arrays;
import java.util.EnumSet;

public class HttpMethodMatcher implements Matcher {

    private EnumSet<HttpMethod> httpMethods;

    public HttpMethodMatcher(HttpMethod[] httpMethods) {
        this.httpMethods = EnumSet.noneOf(HttpMethod.class);
        for (HttpMethod httpMethod : httpMethods) {
            this.httpMethods.add(httpMethod);
        }
    }


    @Override
    public boolean match(WebServerHttpRequest req, PathMappingContext mappingContext) {
        return httpMethods.contains(req.getMethod());
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

    public EnumSet<HttpMethod> getHttpMethods() {
        return httpMethods;
    }

    @Override
    public String toString() {
        String[] methods = httpMethods.stream().map(HttpMethod::name).toArray(String[]::new);
        if (methods.length == 1) {
            return methods[0];
        }
        return Arrays.toString(methods);
    }
}
