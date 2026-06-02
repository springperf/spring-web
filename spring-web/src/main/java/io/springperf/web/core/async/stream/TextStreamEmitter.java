package io.springperf.web.core.async.stream;

import lombok.SneakyThrows;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;

public class TextStreamEmitter extends StreamEmitter<CharSequence> {

    public TextStreamEmitter() {
    }

    public TextStreamEmitter(Long timeout) {
        super(timeout);
    }

    @Override
    protected void extendResponse(ServerHttpResponse response) {
        HttpHeaders headers = response.getHeaders();
        if (headers.getContentType() == null) {
            headers.setContentType(MediaType.TEXT_PLAIN);
        }
        headers.setCacheControl("no-cache");
    }

    @SneakyThrows
    protected CharSequence encodeToString(CharSequence data) {
        if (data == null) {
            return "\n";
        }
        if (data instanceof Appendable) {
            ((Appendable) data).append('\n');
        } else {
            data = data.toString() + '\n';
        }
        return data;
    }

    @Override
    protected int getMaxFlushBytes() {
        return 1024 * 32;
    }
}
