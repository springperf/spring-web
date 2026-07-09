package io.springperf.web.http.support;

import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.multipart.FileUpload;
import lombok.SneakyThrows;
import org.springframework.http.HttpHeaders;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

public class NettyMultipartFile implements MultipartFile, HttpInputMessagePart {

    private final FileUpload fileUpload;

    private HttpHeaders headers;

    public NettyMultipartFile(FileUpload fileUpload) {
        this.fileUpload = fileUpload;
    }

    @Override
    public String getName() {
        return fileUpload.getName();
    }

    @Override
    public String getOriginalFilename() {
        return fileUpload.getFilename();
    }

    @Override
    public String getContentType() {
        return fileUpload.getContentType();
    }

    @Override
    public boolean isEmpty() {
        return fileUpload.length() == 0;
    }

    @Override
    public long getSize() {
        return fileUpload.length();
    }

    @Override
    public String getSubmittedFileName() {
        return fileUpload.getFilename();
    }

    @Override
    public byte[] getBytes() throws IOException {
        return fileUpload.get();
    }

    @Override
    public java.io.InputStream getInputStream() {
        return new ByteBufInputStream(fileUpload.content());
    }

    @Override
    public void transferTo(java.io.File dest) throws IOException {
        fileUpload.renameTo(dest);
    }

    @Override
    public InputStream getBody() throws IOException {
        return new ByteBufInputStream(fileUpload.getByteBuf());
    }

    @Override
    public HttpHeaders getHeaders() {
        if (headers == null) {
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.set(HttpHeaders.CONTENT_DISPOSITION, buildContentDisposition(fileUpload.getName(), fileUpload.getFilename()));
            if (fileUpload.getContentType() != null) {
                httpHeaders.set(HttpHeaders.CONTENT_TYPE, fileUpload.getContentType());
            }
            long size = fileUpload.length();
            if (size >= 0) {
                httpHeaders.setContentLength(size);
            }
            if (fileUpload.getContentTransferEncoding() != null) {
                httpHeaders.set("Content-Transfer-Encoding", fileUpload.getContentTransferEncoding());
            }
            headers = httpHeaders;
        }
        return headers;
    }

    protected static String buildContentDisposition(String name, String filename) {
        StringBuilder sb = new StringBuilder("form-data; ");
        sb.append("name=\"").append(sanitize(name)).append("\"");
        if (filename != null) {
            sb.append("; filename=\"").append(sanitize(filename)).append("\"");
        }
        return sb.toString();
    }

    private static String sanitize(String value) {
        return value.replace("\r", "").replace("\n", "");
    }

    @Override
    @SneakyThrows
    public boolean hasBody() {
        return fileUpload.getByteBuf().isReadable();
    }
}
