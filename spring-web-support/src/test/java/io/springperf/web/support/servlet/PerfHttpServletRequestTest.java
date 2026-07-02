package io.springperf.web.support.servlet;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;

import javax.servlet.ServletInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerfHttpServletRequestTest {

    @Mock WebServerHttpRequest request;
    @Mock HttpHeaders headers;
    @Mock WebContext webContext;
    @Mock ApplicationProperties props;
    @Mock RequestContext requestContext;

    private PerfHttpServletRequest servletRequest;

    @BeforeEach
    void setUp() {
        lenient().when(request.getHeaders()).thenReturn(headers);
        lenient().when(request.getWebContext()).thenReturn(webContext);
        lenient().when(webContext.getProps()).thenReturn(props);
        lenient().when(request.getRequestContext()).thenReturn(requestContext);
        servletRequest = new PerfHttpServletRequest(request);
    }

    @Test void getMethod_returnsMethodValue() { when(request.getMethodValue()).thenReturn("POST"); assertEquals("POST", servletRequest.getMethod()); }
    @Test void getRequestURI_returnsUriStr() { when(request.getUriStr()).thenReturn("/api/users"); assertEquals("/api/users", servletRequest.getRequestURI()); }
    @Test void getQueryString_withQuery() { when(request.getUriStrWithQuery()).thenReturn("/api/users?page=1&size=10"); assertEquals("page=1&size=10", servletRequest.getQueryString()); }
    @Test void getQueryString_withoutQuery_returnsNull() { when(request.getUriStrWithQuery()).thenReturn("/api/users"); assertNull(servletRequest.getQueryString()); }
    @Test void getPathInfo_returnsPath() { when(request.getPath()).thenReturn("/api/users"); assertEquals("/api/users", servletRequest.getPathInfo()); }
    @Test void getContextPath_returnsWebContextPath() { when(webContext.getContextPath()).thenReturn("/app"); assertEquals("/app", servletRequest.getContextPath()); }
    @Test void getHeader_returnsFirstValue() { when(headers.getFirst("Accept")).thenReturn("application/json"); assertEquals("application/json", servletRequest.getHeader("Accept")); }
    @Test void getHeader_missing_returnsNull() { when(headers.getFirst("X-Missing")).thenReturn(null); assertNull(servletRequest.getHeader("X-Missing")); }
    @Test void getHeaders_returnsEnumeration() { when(headers.get("Accept-Language")).thenReturn(Arrays.asList("en-US", "en")); assertTrue(Collections.list(servletRequest.getHeaders("Accept-Language")).containsAll(Arrays.asList("en-US", "en"))); }
    @Test void getHeaderNames_returnsEnumeration() { Set<String> names = new HashSet<>(Arrays.asList("Content-Type", "Accept")); when(headers.keySet()).thenReturn(names); assertEquals(names, new HashSet<>(Collections.list(servletRequest.getHeaderNames()))); }
    @Test void getDateHeader_rfc1123_returnsTimestamp() { when(headers.getFirst("Date")).thenReturn("Thu, 01 Dec 2022 00:00:00 GMT"); assertTrue(servletRequest.getDateHeader("Date") > 0); }
    @Test void getDateHeader_rfc1036_returnsTimestamp() { when(headers.getFirst("Date")).thenReturn("Thursday, 01-Dec-22 00:00:00 GMT"); assertTrue(servletRequest.getDateHeader("Date") > 0); }
    @Test void getDateHeader_asctime_returnsTimestamp() { when(headers.getFirst("Date")).thenReturn("Thu Dec 1 00:00:00 2022"); assertTrue(servletRequest.getDateHeader("Date") > 0); }
    @Test void getDateHeader_invalid_returnsMinusOne() { when(headers.getFirst("Date")).thenReturn("not-a-date"); assertEquals(-1L, servletRequest.getDateHeader("Date")); }
    @Test void getDateHeader_missing_returnsMinusOne() { when(headers.getFirst("Date")).thenReturn(null); assertEquals(-1L, servletRequest.getDateHeader("Date")); }
    @Test void getIntHeader_valid_returnsValue() { when(headers.getFirst("X-Count")).thenReturn("42"); assertEquals(42, servletRequest.getIntHeader("X-Count")); }
    @Test void getIntHeader_missing_returnsMinusOne() { when(headers.getFirst("X-Count")).thenReturn(null); assertEquals(-1, servletRequest.getIntHeader("X-Count")); }
    @Test void getContentType_returnsContentTypeHeader() { when(headers.getFirst("Content-Type")).thenReturn("application/json"); assertEquals("application/json", servletRequest.getContentType()); }
    @Test void getContentLength_returnsValue() { when(request.getContentLength()).thenReturn(100); assertEquals(100, servletRequest.getContentLength()); assertEquals(100L, servletRequest.getContentLengthLong()); }
    @Test void getInputStream_returnsServletInputStream() throws Exception { when(request.getBody()).thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))); ServletInputStream in = servletRequest.getInputStream(); byte[] buf = new byte[5]; assertEquals(5, in.read(buf)); assertEquals("hello", new String(buf, StandardCharsets.UTF_8)); }
    @Test void getReader_returnsBufferedReader() throws Exception { when(request.getBody()).thenReturn(new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8))); when(request.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8); assertEquals("test", servletRequest.getReader().readLine()); }
    @Test void getCharacterEncoding_returnsFromRequest() { when(request.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8); assertEquals("UTF-8", servletRequest.getCharacterEncoding()); }
    @Test void getCharacterEncoding_null_returnsNull() { when(request.getCharacterEncoding()).thenReturn(null); assertNull(servletRequest.getCharacterEncoding()); }
    @Test void setCharacterEncoding_delegatesToRequest() { servletRequest.setCharacterEncoding("ISO-8859-1"); verify(request).setCharacterEncoding(Charset.forName("ISO-8859-1")); }
    @Test void getLocale_returnsFirstLocale() { when(request.getLocales()).thenReturn(Collections.singletonList(Locale.US)); assertEquals(Locale.US, servletRequest.getLocale()); }
    @Test void getLocales_returnsEnumeration() { when(request.getLocales()).thenReturn(Arrays.asList(Locale.US, Locale.FRANCE)); assertEquals(Arrays.asList(Locale.US, Locale.FRANCE), Collections.list(servletRequest.getLocales())); }
    @Test void getParameter_returnsValue() { when(request.getParameter("name")).thenReturn("John"); assertEquals("John", servletRequest.getParameter("name")); }
    @Test void getParameterMap_returnsMap() { Map<String, String[]> map = new HashMap<>(); map.put("key", new String[]{"val1", "val2"}); when(request.getParameterMapArray()).thenReturn(map); assertArrayEquals(new String[]{"val1", "val2"}, servletRequest.getParameterMap().get("key")); }
    @Test void getParameterValues_returnsValues() { when(request.getParameterValues("tag")).thenReturn(new String[]{"a", "b"}); assertArrayEquals(new String[]{"a", "b"}, servletRequest.getParameterValues("tag")); }
    @SuppressWarnings("unchecked") @Test void getParameterNames_returnsEnumeration() { MultiValueMap<String, String> paramMap = mock(MultiValueMap.class); when(paramMap.keySet()).thenReturn(new HashSet<>(Arrays.asList("a", "b"))); when(request.getParameterMap()).thenReturn(paramMap); Set<String> nameSet = new HashSet<>(Collections.list(servletRequest.getParameterNames())); assertTrue(nameSet.contains("a")); assertTrue(nameSet.contains("b")); }
    @Test void getAttribute_delegatesToRequestContext() { when(requestContext.getAttribute("foo")).thenReturn("bar"); assertEquals("bar", servletRequest.getAttribute("foo")); }
    @Test void setAttribute_delegatesToRequestContext() { servletRequest.setAttribute("key", "value"); verify(requestContext).setAttribute("key", "value"); }
    @Test void removeAttribute_delegatesToRequestContext() { servletRequest.removeAttribute("key"); verify(requestContext).removeAttribute("key"); }
    @Test void getAttributeNames_delegatesToRequestContext() { Map<String, Object> attrs = new HashMap<>(); attrs.put("k1", "v1"); attrs.put("k2", "v2"); when(requestContext.getAttributes()).thenReturn(attrs); Set<String> nameSet = new HashSet<>(Collections.list(servletRequest.getAttributeNames())); assertTrue(nameSet.containsAll(Arrays.asList("k1", "k2"))); }
    @Test void getServerPort_returnsFromProps() { when(props.getInt(PropertiesConstant.SERVER_PORT)).thenReturn(9090); assertEquals(9090, servletRequest.getServerPort()); }
    @Test void getScheme_alwaysHttp() { assertEquals("http", servletRequest.getScheme()); }
    @Test void getServerName_fromHostHeader() { when(headers.getFirst("Host")).thenReturn("example.com"); assertEquals("example.com", servletRequest.getServerName()); }
    @Test void getServerName_withoutHostHeader_defaultsToLocalhost() { when(headers.getFirst("Host")).thenReturn(null); assertEquals("localhost", servletRequest.getServerName()); }
    @Test void getLocalAddr_returns127() { assertEquals("127.0.0.1", servletRequest.getLocalAddr()); }
    @Test void getRemoteAddr_returns127() { assertEquals("127.0.0.1", servletRequest.getRemoteAddr()); }
    @Test void getRemoteHost_returnsRemoteAddr() { assertEquals("127.0.0.1", servletRequest.getRemoteHost()); }
    @Test void nettyServletInputStream_isFinished_whenAvailableZero() { assertTrue(new PerfHttpServletRequest.NettyServletInputStream(new ByteArrayInputStream(new byte[0])).isFinished()); }
    @Test void nettyServletInputStream_isReady_returnsTrue() { assertTrue(new PerfHttpServletRequest.NettyServletInputStream(new ByteArrayInputStream("data".getBytes())).isReady()); }
    @Test void nettyServletInputStream_setReadListener_throwsUnsupported() { assertThrows(UnsupportedOperationException.class, () -> new PerfHttpServletRequest.NettyServletInputStream(new ByteArrayInputStream(new byte[0])).setReadListener(null)); }
    @Test void httpHeaderDateParser_null_returnsMinusOne() { assertEquals(-1L, PerfHttpServletRequest.HttpHeaderDateParser.parseDate(null)); }
    @Test void httpHeaderDateParser_empty_returnsMinusOne() { assertEquals(-1L, PerfHttpServletRequest.HttpHeaderDateParser.parseDate("")); }
    @Test void httpHeaderDateParser_garbage_returnsMinusOne() { assertEquals(-1L, PerfHttpServletRequest.HttpHeaderDateParser.parseDate("this is not a date")); }
    @Test void httpHeaderDateParser_rfc1123() { assertTrue(PerfHttpServletRequest.HttpHeaderDateParser.parseDate("Thu, 01 Dec 2022 00:00:00 GMT") > 0); }
    @Test void getProtocol_returnsHttp11() { assertEquals("HTTP/1.1", servletRequest.getProtocol()); }
    @Test void getCookies_returnsEmptyArray() { assertEquals(0, servletRequest.getCookies().length); }
    @Test void isSecure_returnsFalse() { assertFalse(servletRequest.isSecure()); }
    @Test void getDispatcherType_returnsRequest() { assertEquals(javax.servlet.DispatcherType.REQUEST, servletRequest.getDispatcherType()); }
    @Test void getServletPath_returnsEmptyString() { assertEquals("", servletRequest.getServletPath()); }
    @Test void getRemotePort_returnsMinusOne() { assertEquals(-1, servletRequest.getRemotePort()); }
    @Test void getLocalPort_returnsMinusOne() { assertEquals(-1, servletRequest.getLocalPort()); }
    @Test void getAuthType_returnsNull() { assertNull(servletRequest.getAuthType()); }
    @Test void getSession_throwsUnsupported() { assertThrows(UnsupportedOperationException.class, () -> servletRequest.getSession()); assertThrows(UnsupportedOperationException.class, () -> servletRequest.getSession(true)); }
    @Test void getRequestDispatcher_throwsUnsupported() { assertThrows(UnsupportedOperationException.class, () -> servletRequest.getRequestDispatcher("/path")); }
    @Test void startAsync_throwsUnsupported() { assertThrows(UnsupportedOperationException.class, () -> servletRequest.startAsync()); }
    @Test void getServletContext_throwsUnsupported() { assertThrows(UnsupportedOperationException.class, () -> servletRequest.getServletContext()); }
    @Test void login_throwsUnsupported() { assertThrows(UnsupportedOperationException.class, () -> servletRequest.login("u", "p")); }
    @Test void logout_throwsUnsupported() { assertThrows(UnsupportedOperationException.class, () -> servletRequest.logout()); }
}