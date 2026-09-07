package io.springperf.web.view;

import io.springperf.web.context.WebComponent;
import io.springperf.web.http.WebServerHttpRequest;
import org.springframework.lang.Nullable;

import java.util.Locale;

public interface ViewResolver extends WebComponent {

    @Nullable
    View resolveViewName(String viewName, Locale locale, WebServerHttpRequest req) throws Exception;
}