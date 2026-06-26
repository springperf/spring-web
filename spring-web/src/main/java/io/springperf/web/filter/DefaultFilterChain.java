package io.springperf.web.filter;

import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.http.BaseWebServerHttpRequest;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

import java.util.List;

public class DefaultFilterChain implements FilterChain {

    private final List<WebFilter> filters;
    private final WebFilterRegistry.FilterChainTerminal terminal;
    private final MappingResult mappingResult;

    public DefaultFilterChain(List<WebFilter> filters, MappingResult mappingResult,
                              WebFilterRegistry.FilterChainTerminal terminal) {
        this.filters = filters;
        this.mappingResult = mappingResult;
        this.terminal = terminal;
    }

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        BaseWebServerHttpRequest requestContext = (BaseWebServerHttpRequest) request.getRequestContext();
        int index = requestContext.getFilterIndexAndIncrement();
        if (index < filters.size()) {
            WebFilter next = filters.get(index);
            next.doFilter(request, response, this);
        } else {
            terminal.doFilter(request, response, mappingResult);
        }
    }
}
