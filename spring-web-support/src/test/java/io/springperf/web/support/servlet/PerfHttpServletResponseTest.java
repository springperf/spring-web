package io.springperf.web.support.servlet;

import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import javax.servlet.ServletOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerfHttpServletResponseTest {

    @Mock WebServerHttpResponse response;
    @Mock HttpHeaders headers;

    private PerfHttpServletResponse servletResponse;

    @BeforeEach
    void setUp() {
        lenient().when(response.getHeaders()).thenReturn(headers);
        servletResponse = new PerfHttpServletResponse(response);
    }

    @Test void setStatus_delegatesStatusCode() { servletResponse.setStatus(404); verify(response).setStatusCode(HttpStatus.valueOf(404)); }
    @Test void getStatus_returnsStatusValue() { when(response.getStatus()).thenReturn(HttpStatus.CREATED); assertEquals(201, servletResponse.getStatus()); }
    @Test void setHeader_delegatesToHeadersSet() { servletResponse.setHeader("X-Custom", "value"); verify(headers).set("X-Custom", "value"); }
    @Test void addHeader_delegatesToHeadersAdd() { servletResponse.addHeader("X-Custom", "value"); verify(headers).add("X-Custom", "value"); }
    @Test void getHeader_returnsFirstValue() { when(headers.getFirst("X-Custom")).thenReturn("value"); assertEquals("value", servletResponse.getHeader("X-Custom")); }
    @Test void getHeaders_returnsCollection() { java.util.Collection<String> values = java.util.Arrays.asList("a", "b"); when(headers.get("X-Custom")).thenReturn((java.util.List<String>) (java.util.List) values); assertTrue(servletResponse.getHeaders("X-Custom").containsAll(values)); }
    @Test void getHeaderNames_returnsSet() { java.util.Set<String> names = new java.util.HashSet<>(java.util.Arrays.asList("Content-Type", "X-Custom")); when(headers.keySet()).thenReturn(names); assertEquals(names, servletResponse.getHeaderNames()); }
    @Test void setContentType_delegatesToSetHeader() { servletResponse.setContentType("application/json"); verify(headers).set("Content-Type", "application/json"); }
    @Test void getContentType_returnsHeaderValue() { when(headers.getFirst("Content-Type")).thenReturn("text/html"); assertEquals("text/html", servletResponse.getContentType()); }
    @Test void getCharacterEncoding_returnsFromResponse() { when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8); assertEquals("UTF-8", servletResponse.getCharacterEncoding()); }
    @Test void getCharacterEncoding_null_returnsNull() { when(response.getCharacterEncoding()).thenReturn(null); assertNull(servletResponse.getCharacterEncoding()); }
    @Test void setCharacterEncoding_delegatesToResponse() { servletResponse.setCharacterEncoding("ISO-8859-1"); verify(response).setCharacterEncoding(StandardCharsets.ISO_8859_1); }
    @Test void getOutputStream_writesToResponseBody() throws Exception { ByteArrayOutputStream baos = new ByteArrayOutputStream(); when(response.getBody()).thenReturn(baos); ServletOutputStream out = servletResponse.getOutputStream(); out.write(65); out.write("hello".getBytes()); assertArrayEquals(new byte[]{65, 'h', 'e', 'l', 'l', 'o'}, baos.toByteArray()); }
    @Test void getOutputStream_print_writesEncodedString() throws Exception { ByteArrayOutputStream baos = new ByteArrayOutputStream(); when(response.getBody()).thenReturn(baos); when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8); servletResponse.getOutputStream().print("hello"); assertEquals("hello", baos.toString("UTF-8")); }
    @Test void getOutputStream_flush_flushesBody() throws Exception { ByteArrayOutputStream baos = new ByteArrayOutputStream(); when(response.getBody()).thenReturn(baos); servletResponse.getOutputStream().flush(); }
    @Test void getOutputStream_setWriteListener_throwsUnsupported() throws Exception { assertThrows(UnsupportedOperationException.class, () -> servletResponse.getOutputStream().setWriteListener(null)); }
    @Test void getOutputStream_isReady_returnsTrue() throws Exception { assertTrue(servletResponse.getOutputStream().isReady()); }
    @Test void getWriter_writesToResponseBody() throws Exception { ByteArrayOutputStream baos = new ByteArrayOutputStream(); when(response.getBody()).thenReturn(baos); when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8); PrintWriter writer = servletResponse.getWriter(); writer.print("test content"); writer.flush(); assertEquals("test content", baos.toString("UTF-8")); }
    @Test void flushBuffer_delegatesToResponseFlush() throws Exception { servletResponse.flushBuffer(); verify(response).flush(); }
    @Test void getBufferSize_returnsFromResponse() { when(response.getBufferSize()).thenReturn(4096); assertEquals(4096, servletResponse.getBufferSize()); }
    @Test void setBufferSize_doesNothing() { servletResponse.setBufferSize(8192); }
    @Test void isCommitted_returnsFalse() { assertFalse(servletResponse.isCommitted()); }
    @Test void resetBuffer_delegatesToResponse() { servletResponse.resetBuffer(); verify(response).resetBuffer(); }
    @Test void reset_delegatesToResponseResetBuffer() { servletResponse.reset(); verify(response).resetBuffer(); }
    @Test void sendError_withStatus_delegatesToResponse() { servletResponse.sendError(500); verify(response).sendError(HttpStatus.valueOf(500)); }
    @Test void sendError_withStatusAndMessage_delegatesToResponse() { servletResponse.sendError(400, "Bad Request"); verify(response).sendError(HttpStatus.valueOf(400), "Bad Request"); }
}