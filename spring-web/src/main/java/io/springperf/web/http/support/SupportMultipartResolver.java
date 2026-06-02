package io.springperf.web.http.support;

import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SupportMultipartResolver {

    protected HttpDataFactory factory = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);

    protected HttpRequest request;

    protected HttpHeaders trailingHeader = EmptyHttpHeaders.INSTANCE;
    protected HttpPostRequestDecoder decoder;
    boolean currentMultipart = false;

    public boolean isMultipartMode() {
        return currentMultipart;
    }

    public boolean isMultipart(HttpRequest request) {
        return HttpPostRequestDecoder.isMultipart(request);
    }

    public void start(HttpRequest request) {
        currentMultipart = true;
        this.request = request;
        this.decoder = new HttpPostRequestDecoder(factory, request);
    }

    public void consume(HttpContent content) {
        if (content instanceof LastHttpContent) {
            trailingHeader = ((LastHttpContent) content).trailingHeaders();
        }
        try {
            decoder.offer(content);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public NettyMultipartWebRequest finish() {
        NettyMultipartWebRequest multipart = new NettyMultipartWebRequest(request, decoder, trailingHeader);
        currentMultipart = false;
        return multipart;
    }

    public void abort() {
        if (currentMultipart) {
            currentMultipart = false;
            trailingHeader = EmptyHttpHeaders.INSTANCE;
            request = null;
            if (decoder != null) {
                decoder.destroy();
                decoder = null;
            }
        }
    }
}
