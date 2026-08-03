package io.springperf.web.core.async.stream;

import io.springperf.web.util.IoUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class TextStreamEmitter extends StreamEmitter<CharSequence> {

    public TextStreamEmitter() {
    }

    public TextStreamEmitter(Long timeout) {
        super(timeout);
    }

    public TextStreamEmitter(boolean earlyEncode) {
        super(earlyEncode);
    }

    public TextStreamEmitter(Long timeout, boolean earlyEncode) {
        super(timeout, earlyEncode);
    }

    @Override
    protected void extendResponse(ServerHttpResponse response) {
        HttpHeaders headers = response.getHeaders();
        if (headers.getContentType() == null) {
            headers.setContentType(MediaType.TEXT_PLAIN);
        }
        headers.setCacheControl("no-cache");
    }

    @Override
    public void encode(Object data, OutputStream out) throws IOException {
        if (data == null) {
            out.write('\n');
            return;
        }
        IoUtils.writeCharSequence(out, (CharSequence) data, StandardCharsets.UTF_8);
        out.write('\n');
    }

    @Override
    protected int getMaxFlushBytes() {
        return 1024 * 32;
    }
}