package io.springperf.web.core.arg;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

public interface StaticArgumentResolver {

    Object resolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception;
}

