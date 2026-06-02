package io.springperf.web.core.arg;

import io.springperf.web.context.WebComponent;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;

public interface RuntimeArgumentResolver extends WebComponent {
    boolean supportsParameter(MethodParameter parameter, WebServerHttpRequest request, WebServerHttpResponse response);

    Object resolveArgument(MethodParameter parameter, WebServerHttpRequest request, WebServerHttpResponse response) throws Exception;
}
