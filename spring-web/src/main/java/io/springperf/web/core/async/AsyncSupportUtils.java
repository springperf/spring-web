package io.springperf.web.core.async;

import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

public class AsyncSupportUtils {

    public static final RequestAttribute<PerfAsyncWebRequest> WEB_ASYNC_REQUEST_ATTRIBUTE =
            RequestAttribute.createAttribute(PerfAsyncWebRequest.class);

    public static boolean isAsyncRequest(WebServerHttpRequest request) {
        PerfAsyncWebRequest asyncWebRequest = request.getRequestContext().getAttribute(WEB_ASYNC_REQUEST_ATTRIBUTE);
        if (asyncWebRequest == null) {
            return false;
        }
        return asyncWebRequest.isAsyncStarted();
    }

    public static PerfAsyncWebRequest getAsyncWebRequest(WebServerHttpRequest request, WebServerHttpResponse response) {
        PerfAsyncWebRequest asyncWebRequest = request.getRequestContext().getAttribute(WEB_ASYNC_REQUEST_ATTRIBUTE);
        if (asyncWebRequest == null) {
            asyncWebRequest = new PerfAsyncWebRequest(request, response);
            request.getRequestContext().setAttribute(WEB_ASYNC_REQUEST_ATTRIBUTE, asyncWebRequest);
        }
        return asyncWebRequest;
    }
}
