package io.springperf.web.support.servlet;

import io.springperf.web.http.support.HttpInputMessagePart;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServletPartAdapterTest {

    private HttpInputMessagePart mockPart() {
        HttpInputMessagePart part = mock(HttpInputMessagePart.class);
        when(part.getSize()).thenReturn(5L);
        when(part.getName()).thenReturn("file1");
        when(part.getSubmittedFileName()).thenReturn("a.txt");
        return part;
    }

    @Test
    void getInputStream_delegatesToBody() throws Exception {
        HttpInputMessagePart part = mockPart();
        InputStream body = new ByteArrayInputStream(new byte[]{1, 2, 3});
        when(part.getBody()).thenReturn(body);
        ServletPartAdapter adapter = new ServletPartAdapter(part);
        assertSame(body, adapter.getInputStream());
    }

    @Test
    void getContentType_withContentType_returnsString() {
        HttpInputMessagePart part = mockPart();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        when(part.getHeaders()).thenReturn(headers);
        ServletPartAdapter adapter = new ServletPartAdapter(part);
        assertEquals("text/plain", adapter.getContentType());
    }

    @Test
    void getContentType_withoutContentType_returnsNull() {
        HttpInputMessagePart part = mockPart();
        when(part.getHeaders()).thenReturn(new HttpHeaders());
        ServletPartAdapter adapter = new ServletPartAdapter(part);
        assertNull(adapter.getContentType());
    }

    @Test
    void getName_and_getSubmittedFileName_and_getSize() {
        HttpInputMessagePart part = mockPart();
        ServletPartAdapter adapter = new ServletPartAdapter(part);
        assertEquals("file1", adapter.getName());
        assertEquals("a.txt", adapter.getSubmittedFileName());
        assertEquals(5L, adapter.getSize());
    }

    @Test
    void write_throwsUnsupported() {
        ServletPartAdapter adapter = new ServletPartAdapter(mockPart());
        assertThrows(UnsupportedOperationException.class, () -> adapter.write("x"));
    }

    @Test
    void delete_noOp() throws Exception {
        ServletPartAdapter adapter = new ServletPartAdapter(mockPart());
        assertDoesNotThrow(adapter::delete);
    }

    @Test
    void getHeader_getHeaders_getHeaderNames() {
        HttpInputMessagePart part = mockPart();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Custom", "value1");
        headers.add("X-Multi", "a");
        headers.add("X-Multi", "b");
        when(part.getHeaders()).thenReturn(headers);

        ServletPartAdapter adapter = new ServletPartAdapter(part);
        assertEquals("value1", adapter.getHeader("X-Custom"));
        Collection<String> multi = adapter.getHeaders("X-Multi");
        assertNotNull(multi);
        assertTrue(multi.contains("a") && multi.contains("b"));
        assertTrue(adapter.getHeaderNames().contains("X-Custom"));
    }

    @Test
    void getHeader_missing_returnsNull() {
        HttpInputMessagePart part = mockPart();
        when(part.getHeaders()).thenReturn(new HttpHeaders());
        ServletPartAdapter adapter = new ServletPartAdapter(part);
        assertNull(adapter.getHeader("Missing"));
    }
}