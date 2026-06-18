package io.springperf.web.support.async.stream;

import io.springperf.web.context.LifecycleWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.async.stream.StreamEmitter;
import io.springperf.web.core.async.stream.StreamEmitterReturnValueResolver;
import io.springperf.web.core.codec.HttpBodyCodecRegistry;
import io.springperf.web.core.codec.HttpBodyConverter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.AdapterUtil;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class ResponseBodyEmitterReturnValueResolver extends StreamEmitterReturnValueResolver implements LifecycleWebComponent {

    private HttpBodyCodecRegistry codecRegistry;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        codecRegistry = webContext.getWebComponentWithDefault(HttpBodyCodecRegistry.class, new HttpBodyCodecRegistry());
    }

    @Override
    protected void preInitializeEmitter(StreamEmitter emitter, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        super.preInitializeEmitter(emitter, req, resp);
        if (emitter instanceof ResponseBodyEmitter) {
            ResponseBodyEmitter responseBodyEmitter = (ResponseBodyEmitter) emitter;
            HttpHeaders mutableHeaders = new HttpHeaders(resp.getHeaders());
            AdapterUtil.setEncodeToBytesFunction(responseBodyEmitter, (data) -> encodeToBytes(mutableHeaders, data));
        }
    }

    protected byte[] encodeToBytes(HttpHeaders mutableHeaders, Object data) throws IOException {
        MediaType selectedMediaType = null;
        if (data instanceof ResponseBodyEmitter.DataWithMediaType) {
            ResponseBodyEmitter.DataWithMediaType dataWithMediaType = (ResponseBodyEmitter.DataWithMediaType) data;
            selectedMediaType = dataWithMediaType.getMediaType();
            data = dataWithMediaType.getData();
        }
        for (HttpBodyConverter converter : codecRegistry.getConverters()) {
            if (converter.canWrite(null, data.getClass(), selectedMediaType)) {
                StreamingHttpOutputMessage outputMessage = new StreamingHttpOutputMessage(mutableHeaders);
                converter.write(data, null, selectedMediaType, outputMessage);
                return outputMessage.getBytes();
            }
        }
        if (data instanceof String) {
            return ((String) data).getBytes(StandardCharsets.UTF_8);
        }
        if (data instanceof byte[]) {
            return (byte[]) data;
        }
        throw new IllegalArgumentException("No suitable converter for " + data.getClass()
                + " and no fallback encoding available");
    }

    @Override
    public String getComponentName() {
        return StreamEmitterReturnValueResolver.class.getSimpleName();
    }

    private static class StreamingHttpOutputMessage implements HttpOutputMessage {

        private final HttpHeaders mutableHeaders;

        private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        public StreamingHttpOutputMessage(HttpHeaders mutableHeaders) {
            this.mutableHeaders = mutableHeaders;
        }

        @Override
        public OutputStream getBody() throws IOException {
            return outputStream;
        }

        @Override
        public HttpHeaders getHeaders() {
            return mutableHeaders;
        }

        public byte[] getBytes() {
            return outputStream.toByteArray();
        }
    }
}
