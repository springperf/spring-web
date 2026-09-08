package io.springperf.web.support.servlet;

import io.springperf.web.http.support.HttpInputMessagePart;
import javax.servlet.http.Part;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;

public class ServletPartAdapter implements Part {

    private final HttpInputMessagePart part;

    public ServletPartAdapter(HttpInputMessagePart part) {
        this.part = part;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return part.getBody();
    }

    @Override
    public String getContentType() {
        return part.getHeaders().getContentType() != null
                ? part.getHeaders().getContentType().toString() : null;
    }

    @Override
    public String getName() {
        return part.getName();
    }

    @Override
    public String getSubmittedFileName() {
        return part.getSubmittedFileName();
    }

    @Override
    public long getSize() {
        return part.getSize();
    }

    @Override
    public void write(String fileName) throws IOException {
        throw new UnsupportedOperationException("write is not supported");
    }

    @Override
    public void delete() throws IOException {
    }

    @Override
    public String getHeader(String name) {
        return part.getHeaders().getFirst(name);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        return part.getHeaders().get(name);
    }

    @Override
    public Collection<String> getHeaderNames() {
        return part.getHeaders().keySet();
    }
}