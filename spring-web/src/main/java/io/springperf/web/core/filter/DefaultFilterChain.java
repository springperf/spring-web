package io.springperf.web.core.filter;

import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.http.BaseWebServerHttpRequest;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

import java.util.List;

public class DefaultFilterChain implements FilterChain {

    private final DispatcherHandler dispatcherHandler;
    private final List<WebFilter> filters;

    public DefaultFilterChain(DispatcherHandler dispatcherHandler, List<WebFilter> filters) {
        this.dispatcherHandler = dispatcherHandler;
        this.filters = filters;
    }

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        BaseWebServerHttpRequest requestContext = (BaseWebServerHttpRequest) request.getRequestContext();
        int index = requestContext.getFilterIndexAndIncrement();
        if (index < filters.size()) {
            WebFilter next = filters.get(index);
            next.doFilter(request, response, this);
        } else {
            dispatcherHandler.handleAfterFilter(request, response, MappingResult.get(request));
        }
    }
}
