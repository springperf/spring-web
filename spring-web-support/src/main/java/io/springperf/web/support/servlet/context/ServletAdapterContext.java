package io.springperf.web.support.servlet.context;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.PerfHttpServletResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;

@Getter
public class ServletAdapterContext {

    /** 框架层面的原始请求包装，始终可用于 rebind（不受 servlet Filter 替换 request 的影响） */
    private final PerfHttpServletRequest perfRequest;

    /** 框架层面的原始响应包装 */
    private final PerfHttpServletResponse perfResponse;

    /** 当前生效的 servlet request（可能是 perfRequest，也可能是 javax.servlet.Filter 包装后的） */
    private HttpServletRequest request;

    /** 当前生效的 servlet response */
    private HttpServletResponse response;

    private FilterChain filterChain;

    public ServletAdapterContext(PerfHttpServletRequest perfRequest, PerfHttpServletResponse perfResponse, FilterChain filterChain) {
        this.perfRequest = perfRequest;
        this.perfResponse = perfResponse;
        this.request = perfRequest;
        this.response = perfResponse;
        this.filterChain = filterChain;
    }

    public HttpServletRequest getRequest() {
        return request;
    }

    public void setRequest(HttpServletRequest request) {
        this.request = request;
    }

    public HttpServletResponse getResponse() {
        return response;
    }

    public void setResponse(HttpServletResponse response) {
        this.response = response;
    }

    public FilterChain getFilterChain() {
        return filterChain;
    }

    public void setFilterChain(FilterChain filterChain) {
        this.filterChain = filterChain;
    }

    /**
     * 更新框架请求委托。当 WebFilter 包装了 {@link WebServerHttpRequest} 后调用，
     * 使原始的 {@code PerfHttpServletRequest} 指向包装后的请求。
     * <p>如果当前 request 已被 javax.servlet.Filter 替换，替换后的 servlet 包装器
     * 内部仍委托到此 {@code PerfHttpServletRequest}，因此 rebind 效果仍能传递。</p>
     */
    public void rebindFrameworkRequest(WebServerHttpRequest webRequest) {
        perfRequest.rebind(webRequest);
    }

    /**
     * 更新框架响应委托，与 {@link #rebindFrameworkRequest} 对称。
     */
    public void rebindFrameworkResponse(WebServerHttpResponse webResponse) {
        perfResponse.rebind(webResponse);
    }
}