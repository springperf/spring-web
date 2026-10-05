package io.springperf.web.support.servlet;

import io.springperf.web.http.support.HttpInputMessagePart;
import jakarta.servlet.http.Part;

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
        // 取一次判一次：原先调用两次 getContentType（重复计算，且静态分析无法关联两次调用）
        Object contentType = part.getHeaders().getContentType();
        return contentType != null ? contentType.toString() : null;
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
        // Spring 7 移除了 HttpHeaders.keySet，改用 headerSet()（两版本签名一致）
        Collection<String> names = new java.util.LinkedHashSet<>();
        for (java.util.Map.Entry<String, java.util.List<String>> e : part.getHeaders().headerSet()) {
            names.add(e.getKey());
        }
        return names;
    }
}
