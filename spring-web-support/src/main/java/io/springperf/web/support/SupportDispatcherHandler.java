package io.springperf.web.support;

import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.PerfHttpServletResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import org.springframework.core.Ordered;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class SupportDispatcherHandler extends DispatcherHandler {

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 30000;
    }

    @Override
    protected boolean initContextHolders(WebServerHttpRequest req, WebServerHttpResponse resp) {
        boolean init = super.initContextHolders(req, resp);
        ServletRequestAttributes requestAttributes = buildRequestAttributes(req, resp);
        if (requestAttributes != null) {
            RequestContextHolder.setRequestAttributes(requestAttributes, this.threadContextInheritable);
        }
        return init || requestAttributes != null;
    }

    @Override
    protected void removeContextHolders(WebServerHttpRequest req, WebServerHttpResponse resp) {
        super.removeContextHolders(req, resp);
        RequestContextHolder.resetRequestAttributes();
    }

    protected ServletRequestAttributes buildRequestAttributes(WebServerHttpRequest req, WebServerHttpResponse resp) {
        ServletAdapterContext adapterContext = ServletAttribute.getAdapterContext(req.getRequestContext());
        if (adapterContext != null) {
            return new ServletRequestAttributes(adapterContext.getRequest(), adapterContext.getResponse());
        }
        PerfHttpServletRequest restRequest = new PerfHttpServletRequest(req);
        PerfHttpServletResponse restResponse = new PerfHttpServletResponse(resp);
        return new ServletRequestAttributes(restRequest, restResponse);
    }


    @Override
    public String getComponentName() {
        return DispatcherHandler.class.getSimpleName();
    }
}
