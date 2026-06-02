package io.springperf.web.filter;

import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.http.BaseWebServerHttpRequest;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

import java.util.List;

public class DefaultFilterChain implements FilterChain {

    private final List<WebFilter> filters;
    private final DispatcherHandler dispatcher;


    public DefaultFilterChain(List<WebFilter> filters, DispatcherHandler dispatcher) {
        this.filters = filters;
        this.dispatcher = dispatcher;
    }

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        BaseWebServerHttpRequest requestContext = (BaseWebServerHttpRequest) request.getRequestContext();
        int index = requestContext.getFilterIndexAndIncrement();
        if (index < filters.size()) {
            WebFilter next = filters.get(index);
            next.doFilter(request, response, this);
        } else {
            dispatcher.handle(request, response);
        }
    }
}

