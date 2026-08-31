package io.springperf.web.support.servlet;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PerfWebConnectionTest {

    @Mock ServletInputStream inputStream;
    @Mock ServletOutputStream outputStream;

    @Test
    void getInputStream() throws Exception {
        PerfWebConnection conn = new PerfWebConnection(inputStream, outputStream);
        assertSame(inputStream, conn.getInputStream());
    }

    @Test
    void getOutputStream() throws Exception {
        PerfWebConnection conn = new PerfWebConnection(inputStream, outputStream);
        assertSame(outputStream, conn.getOutputStream());
    }

    @Test
    void close_closesStreams() throws Exception {
        PerfWebConnection conn = new PerfWebConnection(inputStream, outputStream);
        conn.close();
        verify(inputStream).close();
        verify(outputStream).close();
    }

    @Test
    void close_nullInputStream() throws Exception {
        PerfWebConnection conn = new PerfWebConnection(null, outputStream);
        conn.close();
    }

    @Test
    void close_nullOutputStream() throws Exception {
        PerfWebConnection conn = new PerfWebConnection(inputStream, null);
        conn.close();
    }
}