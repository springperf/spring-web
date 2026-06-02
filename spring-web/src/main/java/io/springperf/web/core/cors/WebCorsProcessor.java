package io.springperf.web.core.cors;

import io.springperf.web.context.WebComponent;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.web.cors.CorsConfiguration;

import java.io.IOException;

public interface WebCorsProcessor extends WebComponent {

    boolean process(@Nullable CorsConfiguration configuration, WebServerHttpRequest request, WebServerHttpResponse response) throws IOException;
}
