package io.springperf.web.filter;

import io.springperf.web.context.WebComponentContainer;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.server.HttpHandler;

import java.util.ArrayList;
import java.util.List;

public class WebFilterRegistry extends WebComponentContainer implements HttpHandler {

    protected final List<WebFilter> filters = new ArrayList<>();

    protected FilterChain filterChain;

    public WebFilterRegistry() {
        autoRegisterWebComponent(WebFilter.class);
    }

    @Override
    public void initComponentPhase3() throws Exception {
        super.initComponentPhase3();
        initRealComponentList(filters, WebFilter.class);
        filterChain = new DefaultFilterChain(filters, webContext.getWebComponent(DispatcherHandler.class));
    }

    @Override
    public void httpHandle(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        doFilter(request, response);
    }

    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        filterChain.doFilter(request, response);
    }
}
