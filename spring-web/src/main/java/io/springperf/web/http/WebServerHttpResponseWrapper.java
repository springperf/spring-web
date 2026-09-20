package io.springperf.web.http;

import io.springperf.web.context.WebContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.concurrent.ScheduledFuture;

public class WebServerHttpResponseWrapper implements WebServerHttpResponse {

    private final WebServerHttpResponse response;

    public WebServerHttpResponseWrapper(WebServerHttpResponse response) { this.response = response; }
    public WebServerHttpResponse getResponse() { return response; }

    @Override public boolean isHandled() { return response.isHandled(); }
    @Override public boolean isCommitted() { return response.isCommitted(); }
    @Override public HttpStatusCode getStatus() { return response.getStatus(); }
    @Override public Charset getCharacterEncoding() { return response.getCharacterEncoding(); }
    @Override public void setCharacterEncoding(Charset characterEncoding) { response.setCharacterEncoding(characterEncoding); }
    @Override public int getBufferSize() { return response.getBufferSize(); }
    @Override public boolean resetBuffer() { return response.resetBuffer(); }
    @Override public ScheduledFuture setTimeout(Runnable task, long delay) { return response.setTimeout(task, delay); }
    @Override public WebContext getWebContext() { return response.getWebContext(); }
    @Override public void setWriteRespEventListener(WriteRespEventListener writeRespEventListener) { response.setWriteRespEventListener(writeRespEventListener); }
    @Override public void addWriteRespEventListener(WriteRespEventListener writeRespEventListener) { response.addWriteRespEventListener(writeRespEventListener); }
    @Override public boolean setHandled() { return response.setHandled(); }
    @Override public void sendError(HttpStatus statusCode) { response.sendError(statusCode); }
    @Override public void sendError(HttpStatus statusCode, String message) { response.sendError(statusCode, message); }
    @Override public void sendError(HttpStatusCode statusCode, String message) { response.sendError(statusCode, message); }
    @Override public void writeStream(InputStream input) { response.writeStream(input); }
    @Override public void writeStream(InputStream input, long contentLength) { response.writeStream(input, contentLength); }
    @Override public void writeBytes(byte[] data) { response.writeBytes(data); }
    @Override public void writeFile(File file) { response.writeFile(file); }
    @Override public void flush(boolean chunked) throws IOException {response.flush(chunked);}
    @Override public void flushChunked() throws IOException { response.flushChunked(); }
    @Override public void endStream() { response.endStream(); }
    @Override public boolean isStreaming() { return response.isStreaming(); }
    @Override public boolean markStreamCompleted() { return response.markStreamCompleted(); }
    @Override public void setBeforeCommit(Runnable callback) { response.setBeforeCommit(callback); }
    @Override public void setStatusCode(HttpStatusCode statusCode) { response.setStatusCode(statusCode); }
    @Override public void flush() throws IOException { response.flush(); }
    @Override public void close() { response.close(); }
    @Override public OutputStream getBody() throws IOException { return response.getBody(); }
    @Override public HttpHeaders getHeaders() { return response.getHeaders(); }

    /**
     * 委托级联释放。接口默认实现为空操作，若不覆盖则一旦本包装器被传入
     * {@code NettyServerHttpRequest.setResponse()}，请求 release() 时响应未提交 buf 将无法
     * 兜底释放，造成池化 ByteBuf 静默泄漏。与 {@code WebServerHttpRequestWrapper} 的 release()
     * 对称，使包装器在引用计数级联上保持透明。
     */
    @Override public void release() { response.release(); }
}