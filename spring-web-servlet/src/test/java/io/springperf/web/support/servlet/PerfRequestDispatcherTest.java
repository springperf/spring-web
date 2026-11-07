package io.springperf.web.support.servlet;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.SupportDispatcherHandler;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerfRequestDispatcherTest {

    @Mock WebServerHttpRequest webRequest;
    @Mock WebServerHttpResponse webResponse;
    @Mock WebContext webContext;
    @Mock SupportDispatcherHandler dispatcherHandler;
    @Mock io.springperf.web.http.RequestContext requestContext;

    private org.springframework.http.HttpHeaders headers;

    private PerfHttpServletRequest servletRequest;
    private PerfHttpServletResponse servletResponse;
    private PerfRequestDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        lenient().when(webRequest.getWebContext()).thenReturn(webContext);
        lenient().when(webRequest.getRequestContext()).thenReturn(requestContext);
        lenient().when(webRequest.getUriStr()).thenReturn("/original");
        lenient().when(webRequest.getPath()).thenReturn("/original");
        lenient().when(webRequest.getUriStrWithQuery()).thenReturn("/original?page=1");
        lenient().when(webContext.getContextPath()).thenReturn("/api");
        lenient().when(webContext.getDispatcherHandler()).thenReturn(dispatcherHandler);
        lenient().when(webResponse.getHeaders()).thenReturn(headers = mock(org.springframework.http.HttpHeaders.class));

        servletRequest = new PerfHttpServletRequest(webRequest);
        servletResponse = new PerfHttpServletResponse(webResponse);
        dispatcher = new PerfRequestDispatcher("/target");
    }

    @Test
    void forward_setsDispatcherTypeAndAttributes() throws Exception {
        when(webResponse.isCommitted()).thenReturn(false);

        dispatcher.forward(servletRequest, servletResponse);

        assertEquals(DispatcherType.FORWARD, servletRequest.getDispatcherType());
        verify(requestContext).setAttribute(RequestDispatcher.FORWARD_REQUEST_URI, "/original");
        verify(requestContext).setAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH, "/api");
        // 2-20：FORWARD 属性应捕获原始 servletPath/pathInfo（不得颠倒），query 不带 '?'
        verify(requestContext).setAttribute(RequestDispatcher.FORWARD_SERVLET_PATH, "/original");
        verify(requestContext).setAttribute(RequestDispatcher.FORWARD_PATH_INFO, "");
        verify(requestContext).setAttribute(RequestDispatcher.FORWARD_QUERY_STRING, "page=1");
        // 2-19：forward 仅重置 buffer，【不应】清空已写响应头、【不应】强制状态 200
        verify(webResponse).resetBuffer();
        verify(webResponse, never()).setStatusCode(org.springframework.http.HttpStatus.OK);
        verify(headers, never()).clear();
        verify(dispatcherHandler).forward(eq(webRequest), eq(webResponse), eq("/target"));
    }

    @Test
    void forward_committed_throws() {
        when(webResponse.isCommitted()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> dispatcher.forward(servletRequest, servletResponse));
    }

    @Test
    void include_setsDispatcherTypeAndRestores() throws Exception {
        // 只在 include() 返回后断言 REQUEST 是假绿：删掉生产里的 setDispatcherType(INCLUDE) 仍会通过。
        // 必须在 dispatcherHandler.include() 执行期检查 dispatcherType。
        doAnswer(inv -> {
            assertEquals(DispatcherType.INCLUDE, servletRequest.getDispatcherType(),
                    "include 执行期间 dispatcherType 必须为 INCLUDE");
            return null;
        }).when(dispatcherHandler).include(any(), any(), any());

        dispatcher.include(servletRequest, servletResponse);

        assertEquals(DispatcherType.REQUEST, servletRequest.getDispatcherType(), "返回后应恢复 REQUEST");
        verify(requestContext).setAttribute(RequestDispatcher.INCLUDE_REQUEST_URI, "/original");
        verify(requestContext).setAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH, "/api");
        verify(requestContext).setAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH, "/target");
        verify(requestContext).setAttribute(RequestDispatcher.INCLUDE_PATH_INFO, "");
        // Servlet 规范：INCLUDE_QUERY_STRING 只写纯查询串，不含 path、不含前导 '?'
        verify(requestContext).setAttribute(RequestDispatcher.INCLUDE_QUERY_STRING, "page=1");
        verify(dispatcherHandler).include(eq(webRequest), any(WebServerHttpResponse.class), eq("/target"));
    }

    @Test
    void include_dispatcherPathQueryString_usesTargetQueryString() throws Exception {
        // 目标路径自带查询串时，规范优先取「目标」的查询串，且 servletPath 不得含 '?'
        PerfRequestDispatcher withQuery = new PerfRequestDispatcher("/target?x=1");

        withQuery.include(servletRequest, servletResponse);

        verify(requestContext).setAttribute(RequestDispatcher.INCLUDE_QUERY_STRING, "x=1");
        verify(requestContext).setAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH, "/target");
        // path 原样下传：ForwardWebServerHttpRequest 会自行拆出纯路径
        verify(dispatcherHandler).include(eq(webRequest), any(WebServerHttpResponse.class), eq("/target?x=1"));
    }

    @Test
    void include_noQueryString_removesIncludeQueryStringAttribute() throws Exception {
        when(webRequest.getUriStrWithQuery()).thenReturn("/original");

        dispatcher.include(servletRequest, servletResponse);

        // 无查询串时属性必须不存在（RequestContext.setAttribute(name, null) 等价于移除）
        verify(requestContext).setAttribute(RequestDispatcher.INCLUDE_QUERY_STRING, null);
        verify(requestContext, never()).setAttribute(RequestDispatcher.INCLUDE_QUERY_STRING, "/original");
    }

    @Test
    void include_emptyQueryString_setsEmptyString() throws Exception {
        when(webRequest.getUriStrWithQuery()).thenReturn("/original?");

        dispatcher.include(servletRequest, servletResponse);

        verify(requestContext).setAttribute(RequestDispatcher.INCLUDE_QUERY_STRING, "");
    }

    @Test
    void include_rawQueryString_isKeptVerbatim() throws Exception {
        // 只切第一个 '?'：编码、'&' 以及后续的 '?' 都必须原样保留，不得解码或截断
        when(webRequest.getUriStrWithQuery()).thenReturn("/original?a=1&b=%20x&c=a%3Db&d=a?b");

        dispatcher.include(servletRequest, servletResponse);

        verify(requestContext).setAttribute(RequestDispatcher.INCLUDE_QUERY_STRING,
                "a=1&b=%20x&c=a%3Db&d=a?b");
    }

    @Test
    void forward_noQueryString_removesForwardQueryStringAttribute() throws Exception {
        when(webResponse.isCommitted()).thenReturn(false);
        when(webRequest.getUriStrWithQuery()).thenReturn("/original");

        dispatcher.forward(servletRequest, servletResponse);

        verify(requestContext).setAttribute(RequestDispatcher.FORWARD_QUERY_STRING, null);
        verify(requestContext, never()).setAttribute(RequestDispatcher.FORWARD_QUERY_STRING, "/original");
    }

    @Test
    void include_restoresDispatcherTypeOnException() {
        doAnswer(inv -> {
            assertEquals(DispatcherType.INCLUDE, servletRequest.getDispatcherType(),
                    "异常路径下 include 执行期间也必须是 INCLUDE");
            throw new RuntimeException("include failed");
        }).when(dispatcherHandler).include(any(), any(), any());

        assertThrows(RuntimeException.class, () -> dispatcher.include(servletRequest, servletResponse));
        assertEquals(DispatcherType.REQUEST, servletRequest.getDispatcherType());
    }

    @Test
    void getRequestDispatcher_returnsDispatcher() {
        javax.servlet.RequestDispatcher rd = servletRequest.getRequestDispatcher("/path");
        assertInstanceOf(PerfRequestDispatcher.class, rd);
        assertEquals("/path", org.springframework.test.util.ReflectionTestUtils.getField(rd, "path"),
                "dispatcher 必须绑定传入的 path");
    }

    @Test
    void getRequestDispatcher_null_returnsNull() {
        assertNull(servletRequest.getRequestDispatcher(null));
    }

    @Test
    void forward_wrappedRequest_unwrapsPerfRequest() throws Exception {
        when(webResponse.isCommitted()).thenReturn(false);
        javax.servlet.http.HttpServletRequestWrapper wrapped =
                new javax.servlet.http.HttpServletRequestWrapper(servletRequest);

        dispatcher.forward(wrapped, servletResponse);

        verify(dispatcherHandler).forward(eq(webRequest), eq(webResponse), eq("/target"));
    }

    @Test
    void forward_wrappedResponse_unwrapsPerfResponse() throws Exception {
        when(webResponse.isCommitted()).thenReturn(false);
        javax.servlet.http.HttpServletResponseWrapper wrapped =
                new javax.servlet.http.HttpServletResponseWrapper(servletResponse);

        dispatcher.forward(servletRequest, wrapped);

        verify(dispatcherHandler).forward(eq(webRequest), eq(webResponse), eq("/target"));
    }

    @Test
    void include_wrappedResponse_unwrapsPerfResponse() throws Exception {
        javax.servlet.http.HttpServletResponseWrapper wrapped =
                new javax.servlet.http.HttpServletResponseWrapper(servletResponse);

        dispatcher.include(servletRequest, wrapped);

        verify(dispatcherHandler).include(eq(webRequest), any(WebServerHttpResponse.class), eq("/target"));
    }

    @Test
    void forward_unresolvableRequest_throwsServletException() {
        javax.servlet.ServletRequest plainRequest = mock(javax.servlet.ServletRequest.class);
        assertThrows(javax.servlet.ServletException.class,
                () -> dispatcher.forward(plainRequest, servletResponse));
    }

    @Test
    void include_unresolvableResponse_throwsServletException() {
        javax.servlet.ServletResponse plainResponse = mock(javax.servlet.ServletResponse.class);
        assertThrows(javax.servlet.ServletException.class,
                () -> dispatcher.include(servletRequest, plainResponse));
    }

    @Test
    void forward_nonSupportDispatcher_throwsServletException() throws Exception {
        when(webResponse.isCommitted()).thenReturn(false);
        when(webContext.getDispatcherHandler()).thenReturn(new io.springperf.web.core.DispatcherHandler());

        assertThrows(javax.servlet.ServletException.class,
                () -> dispatcher.forward(servletRequest, servletResponse));
    }
}
