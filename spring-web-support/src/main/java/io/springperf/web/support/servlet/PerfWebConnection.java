package io.springperf.web.support.servlet;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.WebConnection;

import java.io.IOException;

public class PerfWebConnection implements WebConnection {

    private final ServletInputStream inputStream;
    private final ServletOutputStream outputStream;

    public PerfWebConnection(ServletInputStream inputStream, ServletOutputStream outputStream) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        return inputStream;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        return outputStream;
    }

    @Override
    public void close() throws Exception {
        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } finally {
            if (outputStream != null) {
                outputStream.close();
            }
        }
    }
}