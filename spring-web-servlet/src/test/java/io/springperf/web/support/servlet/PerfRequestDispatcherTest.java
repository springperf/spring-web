package io.springperf.web.support.servlet;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.SupportDispatcherHandler;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletMapping;
import javax.servlet.http.MappingMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerfRequestDispatcherTest {

    @Mock WebServerHttpRequest webRequest;
    @Mock WebServerHttpResponse webResponse;
    @Mock WebContext webContext;
    @Mock SupportDispatcherHandler dispatcherHandler;
    @Mock io.springperf.web.http.RequestContext requestContext;

    private org.springframework.http.HttpHeaders headers;

    /** 模拟真实 RequestContext 的属性表：setAttribute(name, null) 等价于移除（见 BaseWebServerHttpRequest）。 */
    private final Map<String, Object> attributes = new HashMap<>();

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

        // getAttribute/setAttribute 落到 Map：让「include 期间可见 / 返回后恢复」可被真实断言，而不是只验证调用次数
        lenient().when(requestContext.getAttribute(anyString()))
                .thenAnswer(inv -> attributes.get(inv.<String>getArgument(0)));
        lenient().doAnswer(inv -> {
            String name = inv.getArgument(0);
            Object value = inv.getArgument(1);
            if (value == null) {
                attributes.remove(name);
            } else {
                attributes.put(name, value);
            }
            return null;
        }).when(requestContext).setAttribute(anyString(), any());

        servletRequest = new PerfHttpServletRequest(webRequest);
        servletResponse = new PerfHttpServletResponse(webResponse);
        dispatcher = new PerfRequestDispatcher("/target");
    }

    /** 取 include 执行期间被包含目标可见的 INCLUDE_* 快照（属性在 include 返回后即恢复，必须在此期间取样）。 */
    private Map<String, Object> captureIncludeAttributesDuringDispatch() throws Exception {
        Map<String, Object> snapshot = new HashMap<>();
        doAnswer(inv -> {
            assertEquals(DispatcherType.INCLUDE, servletRequest.getDispatcherType(),
                    "include 执行期间 dispatcherType 必须为 INCLUDE");
            for (String name : INCLUDE_ATTRIBUTE_NAMES) {
                Object value = attributes.get(name);
                if (value != null) {
                    snapshot.put(name, value);
                }
            }
            return null;
        }).when(dispatcherHandler).include(any(), any(), any());
        return snapshot;
    }

    private static final String[] INCLUDE_ATTRIBUTE_NAMES = {
            RequestDispatcher.INCLUDE_REQUEST_URI,
            RequestDispatcher.INCLUDE_CONTEXT_PATH,
            RequestDispatcher.INCLUDE_SERVLET_PATH,
            RequestDispatcher.INCLUDE_PATH_INFO,
            RequestDispatcher.INCLUDE_QUERY_STRING,
            RequestDispatcher.INCLUDE_MAPPING,
    };

    private void assertNoIncludeAttributesLeaked() {
        for (String name : INCLUDE_ATTRIBUTE_NAMES) {
            assertFalse(attributes.containsKey(name), "include 返回后不得残留属性: " + name);
        }
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
    void include_setsTargetAttributesAndRestores() throws Exception {
        // 属性在 include 期间必须可见（假绿防御：只在返回后断言无法发现「属性没写进 context」）
        Map<String, Object> snapshot = captureIncludeAttributesDuringDispatch();

        dispatcher.include(servletRequest, servletResponse);

        // INCLUDE_* 描述「被包含的目标资源」，不是原始请求：REQUEST_URI 必须是 contextPath + 目标路径
        assertEquals("/api/target", snapshot.get(RequestDispatcher.INCLUDE_REQUEST_URI));
        assertEquals("/api", snapshot.get(RequestDispatcher.INCLUDE_CONTEXT_PATH));
        assertEquals("/target", snapshot.get(RequestDispatcher.INCLUDE_SERVLET_PATH));
        assertEquals("", snapshot.get(RequestDispatcher.INCLUDE_PATH_INFO));
        // Servlet 规范：INCLUDE_QUERY_STRING 只写纯查询串，不含 path、不含前导 '?'
        assertEquals("page=1", snapshot.get(RequestDispatcher.INCLUDE_QUERY_STRING));

        HttpServletMapping mapping = (HttpServletMapping) snapshot.get(RequestDispatcher.INCLUDE_MAPPING);
        assertNotNull(mapping, "Servlet 4.0+ 必须设置 INCLUDE_MAPPING（HttpServletMapping）");
        assertEquals("/target", mapping.getPattern());
        assertEquals("target", mapping.getMatchValue(), "EXACT 的 matchValue 必须省略前导 '/'");
        assertEquals(MappingMatch.EXACT, mapping.getMappingMatch());
        assertEquals("", mapping.getServletName(), "include 目标由 MVC 路由处理，没有 Servlet 名称");

        assertEquals(DispatcherType.REQUEST, servletRequest.getDispatcherType(), "返回后应恢复 REQUEST");
        assertNoIncludeAttributesLeaked();
        verify(dispatcherHandler).include(eq(webRequest), any(WebServerHttpResponse.class), eq("/target"));
    }

    @Test
    void include_emptyContextPath_requestUriIsTargetPath() throws Exception {
        when(webContext.getContextPath()).thenReturn("");

        Map<String, Object> snapshot = captureIncludeAttributesDuringDispatch();
        dispatcher.include(servletRequest, servletResponse);

        assertEquals("/target", snapshot.get(RequestDispatcher.INCLUDE_REQUEST_URI));
        assertEquals("", snapshot.get(RequestDispatcher.INCLUDE_CONTEXT_PATH));
    }

    @Test
    void include_nested_restoresOuterIncludeAttributes() throws Exception {
        HttpServletMapping outerMapping = mock(HttpServletMapping.class);
        attributes.put(RequestDispatcher.INCLUDE_REQUEST_URI, "/outer/uri");
        attributes.put(RequestDispatcher.INCLUDE_MAPPING, outerMapping);

        Map<String, Object> snapshot = captureIncludeAttributesDuringDispatch();
        dispatcher.include(servletRequest, servletResponse);

        assertEquals("/api/target", snapshot.get(RequestDispatcher.INCLUDE_REQUEST_URI), "内层 include 覆盖外层值");
        assertEquals("/outer/uri", attributes.get(RequestDispatcher.INCLUDE_REQUEST_URI),
                "返回后必须恢复外层值，而不是一律移除");
        assertSame(outerMapping, attributes.get(RequestDispatcher.INCLUDE_MAPPING));
        assertFalse(attributes.containsKey(RequestDispatcher.INCLUDE_SERVLET_PATH), "外层没有的属性仍应移除");
    }

    @Test
    void include_dispatcherPathQueryString_usesTargetQueryString() throws Exception {
        // 目标路径自带查询串时，规范优先取「目标」的查询串，且 servletPath 不得含 '?'
        PerfRequestDispatcher withQuery = new PerfRequestDispatcher("/target?x=1");
        Map<String, Object> snapshot = captureIncludeAttributesDuringDispatch();

        withQuery.include(servletRequest, servletResponse);

        assertEquals("x=1", snapshot.get(RequestDispatcher.INCLUDE_QUERY_STRING));
        assertEquals("/target", snapshot.get(RequestDispatcher.INCLUDE_SERVLET_PATH));
        assertEquals("/api/target", snapshot.get(RequestDispatcher.INCLUDE_REQUEST_URI), "REQUEST_URI 不得带查询串");
        // path 原样下传：ForwardWebServerHttpRequest 会自行拆出纯路径
        verify(dispatcherHandler).include(eq(webRequest), any(WebServerHttpResponse.class), eq("/target?x=1"));
        assertNoIncludeAttributesLeaked();
    }

    @Test
    void include_noQueryString_removesIncludeQueryStringAttribute() throws Exception {
        when(webRequest.getUriStrWithQuery()).thenReturn("/original");

        Map<String, Object> snapshot = captureIncludeAttributesDuringDispatch();
        dispatcher.include(servletRequest, servletResponse);

        // 无查询串时属性必须不存在（RequestContext.setAttribute(name, null) 等价于移除）
        assertFalse(snapshot.containsKey(RequestDispatcher.INCLUDE_QUERY_STRING));
    }

    @Test
    void include_emptyQueryString_setsEmptyString() throws Exception {
        when(webRequest.getUriStrWithQuery()).thenReturn("/original?");

        Map<String, Object> snapshot = captureIncludeAttributesDuringDispatch();
        dispatcher.include(servletRequest, servletResponse);

        assertTrue(snapshot.containsKey(RequestDispatcher.INCLUDE_QUERY_STRING), "空查询串应写入空串而非移除属性");
        assertEquals("", snapshot.get(RequestDispatcher.INCLUDE_QUERY_STRING));
    }

    @Test
    void include_rawQueryString_isKeptVerbatim() throws Exception {
        // 只切第一个 '?'：编码、'&' 以及后续的 '?' 都必须原样保留，不得解码或截断
        when(webRequest.getUriStrWithQuery()).thenReturn("/original?a=1&b=%20x&c=a%3Db&d=a?b");

        Map<String, Object> snapshot = captureIncludeAttributesDuringDispatch();
        dispatcher.include(servletRequest, servletResponse);

        assertEquals("a=1&b=%20x&c=a%3Db&d=a?b", snapshot.get(RequestDispatcher.INCLUDE_QUERY_STRING));
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
    void include_restoresDispatcherTypeAndAttributesOnException() {
        attributes.put(RequestDispatcher.INCLUDE_REQUEST_URI, "/outer/uri");
        doAnswer(inv -> {
            assertEquals(DispatcherType.INCLUDE, servletRequest.getDispatcherType(),
                    "异常路径下 include 执行期间也必须是 INCLUDE");
            assertEquals("/api/target", attributes.get(RequestDispatcher.INCLUDE_REQUEST_URI),
                    "异常路径下属性也必须已写入目标值");
            throw new RuntimeException("include failed");
        }).when(dispatcherHandler).include(any(), any(), any());

        assertThrows(RuntimeException.class, () -> dispatcher.include(servletRequest, servletResponse));
        assertEquals(DispatcherType.REQUEST, servletRequest.getDispatcherType());
        assertEquals("/outer/uri", attributes.get(RequestDispatcher.INCLUDE_REQUEST_URI), "异常路径同样要恢复外层值");
        assertFalse(attributes.containsKey(RequestDispatcher.INCLUDE_MAPPING));
    }

    @Test
    void include_nonSupportDispatcher_throwsWithoutLeakingAttributes() {
        when(webContext.getDispatcherHandler()).thenReturn(new DispatcherHandler());

        assertThrows(ServletException.class, () -> dispatcher.include(servletRequest, servletResponse));

        // 校验前移后，抛异常时属性表必须干净（原实现是写完属性才校验）
        assertTrue(attributes.isEmpty(), "校验失败时不得写入 INCLUDE_* 属性");
    }

    @Test
    void forward_setsMappingOfOriginalRequest() throws Exception {
        when(webResponse.isCommitted()).thenReturn(false);

        dispatcher.forward(servletRequest, servletResponse);

        HttpServletMapping mapping = (HttpServletMapping) attributes.get(RequestDispatcher.FORWARD_MAPPING);
        assertNotNull(mapping, "Servlet 4.0+ forward 也必须设置 FORWARD_MAPPING（HttpServletMapping）");
        assertEquals("/original", mapping.getPattern(), "FORWARD_MAPPING 描述原始请求，而非转发目标");
        assertEquals("original", mapping.getMatchValue());
        assertEquals(MappingMatch.EXACT, mapping.getMappingMatch());
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
