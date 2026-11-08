package io.springperf.web.support.mvc.retval;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.async.AsyncSupportUtils;
import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.core.async.stream.DefaultStreamSenderFactory;
import io.springperf.web.core.async.stream.StreamSenderFactory;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.retval.resolver.async.BaseAsyncReturnValueResolver;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

/**
 * {@link StreamingResponseBody} 返回值解析器。
 * <p>
 * 写法对标两条既有路径：异步生命周期复用 {@code CallableReturnValueResolver} 的
 * {@code startCallableProcessing}（线程池、超时、拦截器链、结果槽、引用计数全部现成），
 * 帧写出复用 {@code ReactiveReturnValueResolver} 使用的 {@code StreamSender}（背压、终止块、
 * HEAD、断连清理全部现成）。
 * </p>
 * <p>
 * 与 Spring MVC 的差异：Spring 由 Servlet 容器在 async 完成时写终止块，此处没有容器兜底，
 * 故由 {@link ByteStreamEmitter#complete()} 交给 sender 的 {@code LastHttpContent} 写出。
 * </p>
 */
public class StreamingResponseBodyReturnValueResolver extends BaseAsyncReturnValueResolver {

    private static final int FRAME_SIZE = 8192;

    private StreamSenderFactory streamSenderFactory;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        streamSenderFactory = webContext.getWebComponentWithDefault(StreamSenderFactory.class,
                new DefaultStreamSenderFactory());
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        // 与 Spring 的 StreamingResponseBodyReturnValueHandler 一致：ResponseEntity 先取泛型
        Class<?> bodyType = ResponseEntity.class.isAssignableFrom(returnType.getParameterType())
                ? ResolvableType.forMethodParameter(returnType).getGeneric().resolve()
                : returnType.getParameterType();
        return bodyType != null && StreamingResponseBody.class.isAssignableFrom(bodyType);
    }

    @Override
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        if (returnValue instanceof ResponseEntity) {
            returnValue = ((ResponseEntity<?>) returnValue).getBody();
        }
        return returnValue instanceof StreamingResponseBody;
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req,
            WebServerHttpResponse resp) throws Exception {
        if (returnValue instanceof ResponseEntity) {
            ResponseEntity<?> responseEntity = (ResponseEntity<?>) returnValue;
            resp.setStatusCode(responseEntity.getStatusCode());
            resp.getHeaders().putAll(responseEntity.getHeaders());
            returnValue = responseEntity.getBody();
        }
        // ResponseEntity.getBody() 合法可为 null：无 body 就没有可写的流
        if (returnValue == null) {
            return;
        }
        StreamingResponseBody body = (StreamingResponseBody) returnValue;
        applyContentTypeIfAbsent(req, resp);

        PerfAsyncWebRequest asyncWebRequest = AsyncSupportUtils.getAsyncWebRequest(req, resp);
        ByteStreamEmitter emitter = new ByteStreamEmitter(asyncWebRequest, streamSenderFactory,
                HttpMethod.HEAD == req.getMethod());

        // 与 ResponseBodyEmitterReturnValueResolver 一致：启动异步前先进入 chunked 流式状态。
        // 这正是「无需改动 DispatcherHandler」的原因——doHandle 收尾的 flushResponse 会走
        // isStreaming 分支，异步未完成时直接 return，不会提交 Content-Length: 0 顶掉后续写入。
        resp.flushChunked();

        asyncSupportRegistry.startCallableProcessing(asyncWebRequest, new WebAsyncTask<>(() -> {
            FrameOutputStream out = new FrameOutputStream(emitter);
            try {
                body.writeTo(out);
                out.finish();
            } catch (Throwable ex) {
                // 超时/断连会以中断形式到达：清标志后再交给 sender 的关连接路径
                Thread.interrupted();
                emitter.completeWithError(ex);
                return null;
            }
            // 顺序与 StreamEmitter#complete 一致：先派发结果（postHandle/afterCompletion/指标），
            // 再由 sender 写终止块。反过来写终止块的完成回调会把状态推到 COMPLETED，
            // 使 dispatch 的 CAS 失败，异步收尾被静默跳过。
            asyncWebRequest.setConcurrentResultAndDispatch(null);
            emitter.complete();
            return null;
        }));
    }

    /**
     * Content-Type 规则：已设置则保留；{@code produces} 恰好一个具体类型则写它；否则不写。
     * <p>
     * 不做 Accept 二次协商：映射阶段 {@code ConsumeOrProduceMatcher} 已按 Accept 匹配过，
     * 能进入本方法即说明该类型可接受。也不兜底 octet-stream——那是替用户编造事实。
     * </p>
     */
    private void applyContentTypeIfAbsent(WebServerHttpRequest req, WebServerHttpResponse resp) {
        if (resp.getHeaders().getContentType() != null) {
            return;
        }
        PathMappingContext ctx = PathMappingContext.get(req);
        List<MediaType> produces = ctx != null ? ctx.getProducibleMediaTypes() : null;
        if (produces != null && produces.size() == 1 && produces.get(0).isConcrete()) {
            resp.getHeaders().setContentType(produces.get(0));
        }
    }

    /** 8KB 攒帧；每帧复制一份交给 emitter，避免复用缓冲被后续写入覆盖。 */
    private static final class FrameOutputStream extends OutputStream {

        private final ByteStreamEmitter emitter;

        private final byte[] buf = new byte[FRAME_SIZE];

        private int count;

        private FrameOutputStream(ByteStreamEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void write(int b) throws IOException {
            if (count == buf.length) {
                flushFrame();
            }
            buf[count++] = (byte) b;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int n = Math.min(len, buf.length - count);
                System.arraycopy(b, off, buf, count, n);
                count += n;
                off += n;
                len -= n;
                if (count == buf.length) {
                    flushFrame();
                }
            }
        }

        @Override
        public void flush() throws IOException {
            flushFrame();
        }

        @Override
        public void close() {
            // 不关闭响应：收尾由 emitter.complete() 负责
        }

        private void finish() throws IOException {
            flushFrame();
        }

        private void flushFrame() throws IOException {
            if (count == 0) {
                return;
            }
            emitter.sendFrame(Arrays.copyOf(buf, count));
            count = 0;
        }
    }
}
