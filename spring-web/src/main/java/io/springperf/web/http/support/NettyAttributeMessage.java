package io.springperf.web.http.support;

import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.multipart.Attribute;
import lombok.SneakyThrows;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.io.InputStream;

public class NettyAttributeMessage implements HttpInputMessagePart {

    private final Attribute attribute;

    private HttpHeaders headers;

    public NettyAttributeMessage(Attribute attribute) {
        this.attribute = attribute;
    }

    public String getName() {
        return attribute.getName();
    }

    @Override
    public long getSize() {
        return attribute.length();
    }

    @Override
    public String getSubmittedFileName() {
        return null;
    }

    public String getValue() throws IOException {
        return attribute.getValue();
    }

    @Override
    public InputStream getBody() throws IOException {
        return new ByteBufInputStream(attribute.getByteBuf());
    }

    @Override
    public HttpHeaders getHeaders() {
        if (headers == null) {
            HttpHeaders httpHeaders = new HttpHeaders();
            // Content-Disposition
            httpHeaders.set(HttpHeaders.CONTENT_DISPOSITION, NettyMultipartFile.buildContentDisposition(attribute.getName(), null));

            long length = attribute.length();
            if (length >= 0) {
                httpHeaders.setContentLength(length);
            }
            headers = httpHeaders;
        }
        return headers;
    }

    @Override
    @SneakyThrows
    public boolean hasBody() {
        return attribute.getByteBuf().isReadable();
    }
}
