package io.springperf.web.core.async;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

public class AsyncSupportUtils {

    public static final String WEB_ASYNC_REQUEST_ATTRIBUTE =
            PerfAsyncWebRequest.class.getName() + ".WEB_ASYNC_REQUEST";

    public static boolean isAsyncRequest(WebServerHttpRequest request) {
        PerfAsyncWebRequest asyncWebRequest = (PerfAsyncWebRequest) request.getRequestContext().getAttribute(WEB_ASYNC_REQUEST_ATTRIBUTE);
        if (asyncWebRequest == null) {
            return false;
        }
        return asyncWebRequest.isAsyncStarted();
    }

    public static PerfAsyncWebRequest getAsyncWebRequest(WebServerHttpRequest request, WebServerHttpResponse response) {
        PerfAsyncWebRequest asyncWebRequest = (PerfAsyncWebRequest) request.getRequestContext().getAttribute(WEB_ASYNC_REQUEST_ATTRIBUTE);
        if (asyncWebRequest == null) {
            asyncWebRequest = new PerfAsyncWebRequest(request, response);
            request.getRequestContext().setAttribute(WEB_ASYNC_REQUEST_ATTRIBUTE, asyncWebRequest);
        }
        return asyncWebRequest;
    }
}
