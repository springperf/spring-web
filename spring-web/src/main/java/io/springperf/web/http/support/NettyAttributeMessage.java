package io.springperf.web.http.support;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.http.HttpHeaders;

import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.multipart.Attribute;
import lombok.SneakyThrows;

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
        // 与 NettyMultipartFile 同款：duplicate 隔离 readerIndex，false = 关闭不 release，
        // 所有权归 decoder.destroy()（P0，见该处注释）。
        return new ByteBufInputStream(attribute.getByteBuf().duplicate(), false);
    }

    @Override
    public HttpHeaders getHeaders() {
        if (headers == null) {
            HttpHeaders httpHeaders = new HttpHeaders();
            // Content-Disposition
            httpHeaders.set(HttpHeaders.CONTENT_DISPOSITION,
                    NettyMultipartFile.buildContentDisposition(attribute.getName(), null));

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
