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
        // 显式不支持：文件转存走 MultipartFile.transferTo。若在此实现需自行处理路径校验与
        // FileUpload 所有权转移，会与请求末尾 decoder.destroy() 的清理链冲突。
        throw new UnsupportedOperationException("write is not supported");
    }

    @Override
    public void delete() throws IOException {
        // 有意 no-op：上传临时文件由容器在请求结束统一清理
        // （NettyMultipartWebRequest.release() → HttpPostRequestDecoder.destroy() → cleanRequestHttpData）。
        // Servlet 规范中 Part.delete() 是可选的提前释放；提前删除会与请求末尾的清理链
        // 重复操作同一 HttpData，破坏引用计数不变式。
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
