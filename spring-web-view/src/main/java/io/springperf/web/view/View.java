package io.springperf.web.view;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.lang.Nullable;

import java.util.Map;

public interface View {

    @Nullable
    default String getContentType() {
        return null;
    }

    void render(Map<String, ?> model, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception;
}