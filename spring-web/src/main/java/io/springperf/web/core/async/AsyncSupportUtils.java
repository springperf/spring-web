package io.springperf.web.core.async;

import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

public class AsyncSupportUtils {

    // 引用计数不变式：异步持有者 = PerfAsyncWebRequest —— startAsync() 的 CAS 成功分支
    // request.acquire() 一次（恰好一次），进入 COMPLETED 时由 releaseRequestOnce() release 一次：
    // · 响应写完成 completeSuccessCallback / 写失败 completeErrorCallback；
    // · 流式（SSE）正常结束 → onAllDataWritten → addRespEventListener(f,true) → completeSuccessCallback；
    // · 流式异常结束 → onAllDataFailed 只关连接，故由 writeStreamErrorCallback 补触发。
    // 由此入站 buf 存活覆盖整个异步生命周期（异步阶段读 body 含大 body 的 duplicate 共享视图均安全），
    // 且释放点晚于响应写出，不会早释放正在被写的响应缓冲。
    // 详见 PerfAsyncWebRequest.releaseRequestOnce() 与 NettyServerHttpRequest.release()。

    public static final RequestAttribute<PerfAsyncWebRequest> WEB_ASYNC_REQUEST_ATTRIBUTE = RequestAttribute
            .createAttribute(PerfAsyncWebRequest.class);

    public static boolean isAsyncRequest(WebServerHttpRequest request) {
        PerfAsyncWebRequest asyncWebRequest = request.getRequestContext().getAttribute(WEB_ASYNC_REQUEST_ATTRIBUTE);
        if (asyncWebRequest == null) {
            return false;
        }
        return asyncWebRequest.isAsyncStarted() || asyncWebRequest.isAsyncDispatched();
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
