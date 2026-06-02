package io.springperf.web.core.cors;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.http.WebServerHttpResponseWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.Nullable;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Slf4j
public class PerfCorsProcessor extends DefaultCorsProcessor implements WebCorsProcessor {

    private static final List<String> VARY_HEADERS = Arrays.asList(
            HttpHeaders.ORIGIN, HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);

    @Override
    public boolean process(@Nullable CorsConfiguration configuration, WebServerHttpRequest request, WebServerHttpResponse response) throws IOException {
        HttpHeaders responseHeaders = response.getHeaders();
        List<String> varyHeaders = responseHeaders.get(HttpHeaders.VARY);
        if (varyHeaders == null) {
            responseHeaders.addAll(HttpHeaders.VARY, VARY_HEADERS);
        } else {
            for (String header : VARY_HEADERS) {
                if (!varyHeaders.contains(header)) {
                    responseHeaders.add(HttpHeaders.VARY, header);
                }
            }
        }

        if (!CorsUtils.isCorsRequest(request)) {
            return true;
        }

        if (responseHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) != null) {
            log.trace("Skip: response already contains \"Access-Control-Allow-Origin\"");
            return true;
        }

        boolean preFlightRequest = CorsUtils.isPreFlightRequest(request);
        if (configuration == null) {
            if (preFlightRequest) {
                rejectRequest(response);
                return false;
            } else {
                return true;
            }
        }
        return handleInternal(request, new NoFlushWebServerHttpResponse(response), configuration, preFlightRequest);
    }

    private static class NoFlushWebServerHttpResponse extends WebServerHttpResponseWrapper {

        public NoFlushWebServerHttpResponse(WebServerHttpResponse response) {
            super(response);
        }

        @Override
        public void flush() throws IOException {
            log.debug("Skip: flush");
        }
    }
}