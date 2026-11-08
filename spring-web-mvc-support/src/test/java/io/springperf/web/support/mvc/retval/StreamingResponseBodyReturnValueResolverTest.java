package io.springperf.web.support.mvc.retval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.core.async.AsyncSupportUtils;
import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.core.async.stream.StreamSender;
import io.springperf.web.core.async.stream.StreamSenderFactory;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamingResponseBodyReturnValueResolverTest {

    @Mock
    WebContext webContext;
    @Mock
    AsyncSupportRegistry asyncSupportRegistry;
    @Mock
    StreamSenderFactory senderFactory;
    @Mock
    WebServerHttpRequest req;
    @Mock
    WebServerHttpResponse resp;
    @Mock
    RequestContext requestContext;
    @Mock
    PerfAsyncWebRequest asyncWebRequest;

    HttpHeaders headers;

    RecordingSender sender;

    StreamingResponseBodyReturnValueResolver resolver;

    /** 调用序列（dispatch 与 sender.complete 的先后关系由它锁死）。 */
    List<String> events;

    @BeforeEach
    void setUp() {
        headers = new HttpHeaders();
        events = new ArrayList<>();
        sender = new RecordingSender(events);
        lenient().when(webContext.getWebComponentWithDefault(eq(AsyncSupportRegistry.class), any()))
                .thenReturn(asyncSupportRegistry);
        lenient().when(webContext.getWebComponentWithDefault(eq(StreamSenderFactory.class), any()))
                .thenReturn(senderFactory);
        lenient().when(senderFactory.create(any(), any())).thenReturn(sender);
        lenient().when(req.getRequestContext()).thenReturn(requestContext);
        lenient().when(req.getMethod()).thenReturn(HttpMethod.GET);
        lenient().when(resp.getHeaders()).thenReturn(headers);

        // 用真实的属性表模拟 RequestContext：框架里异步持有者与 MappingResult 都走 RequestAttribute，
        // 单纯 when(getAttribute(any())) 无法区分不同属性，会让 produces 相关用例读到错误的值。
        Map<Integer, Object> attributes = new HashMap<>();
        attributes.put(AsyncSupportUtils.WEB_ASYNC_REQUEST_ATTRIBUTE.getIndex(), asyncWebRequest);
        lenient().when(requestContext.getAttribute(any(RequestAttribute.class))).thenAnswer(invocation -> {
            RequestAttribute<?> attribute = invocation.getArgument(0);
            return attributes.get(attribute.getIndex());
        });
        lenient().doAnswer(invocation -> {
            RequestAttribute<?> attribute = invocation.getArgument(0);
            attributes.put(attribute.getIndex(), invocation.getArgument(1));
            return null;
        }).when(requestContext).setAttribute(any(RequestAttribute.class), any());

        resolver = new StreamingResponseBodyReturnValueResolver();
        resolver.initWithWebContext(webContext);
    }

    @Test
    void supportsReturnType_directStreamingResponseBody() throws Exception {
        assertThat(resolver.supportsReturnType(param("direct"), null)).isTrue();
    }

    @Test
    void supportsReturnType_responseEntityOfStreamingResponseBody() throws Exception {
        assertThat(resolver.supportsReturnType(param("wrapped"), null)).isTrue();
    }

    @Test
    void supportsReturnType_otherType() throws Exception {
        assertThat(resolver.supportsReturnType(param("other"), null)).isFalse();
    }

    @Test
    void supportsReturnValue_unwrapsResponseEntity() {
        StreamingResponseBody body = out -> {
        };

        assertThat(resolver.supportsReturnValue(body, req, resp)).isTrue();
        assertThat(resolver.supportsReturnValue(ResponseEntity.ok(body), req, resp)).isTrue();
        assertThat(resolver.supportsReturnValue("text", req, resp)).isFalse();
    }

    @Test
    void resolveReturnValue_streamsFramesThroughSenderAndCompletes() throws Exception {
        resolver.resolveReturnValue(body(out -> {
            out.write(new byte[] { 1, 2, 3 });
            out.flush();
            out.write(new byte[] { 4 });
        }), null, req, resp);

        verify(resp).flushChunked();
        runCallable();

        assertThat(sender.sent).containsExactly(new byte[] { 1, 2, 3 }, new byte[] { 4 });
        assertThat(sender.completeCount).isEqualTo(1);
        assertThat(sender.failure).isNull();
    }

    @Test
    void resolveReturnValue_responseEntityAppliesStatusAndHeaders() throws Exception {
        ResponseEntity<StreamingResponseBody> entity = ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Trace", "abc").body(out -> out.write(1));

        resolver.resolveReturnValue(entity, null, req, resp);

        verify(resp).setStatusCode(HttpStatus.CREATED);
        assertThat(headers.getFirst("X-Trace")).isEqualTo("abc");
    }

    @Test
    void resolveReturnValue_writeFailureTerminatesStreamWithError() throws Exception {
        resolver.resolveReturnValue(body(out -> {
            out.write(new byte[] { 9 });
            throw new IOException("disk gone");
        }), null, req, resp);

        runCallable();

        assertThat(sender.failure).isInstanceOf(IOException.class).hasMessageContaining("disk gone");
    }

    @Test
    void resolveReturnValue_headRequestDoesNotWriteBody() throws Exception {
        when(req.getMethod()).thenReturn(HttpMethod.HEAD);

        resolver.resolveReturnValue(body(out -> out.write(new byte[] { 1, 2 })), null, req, resp);
        runCallable();

        assertThat(sender.sent).isEmpty();
        assertThat(sender.completeCount).isEqualTo(1);
    }

    /**
     * 顺序不变式：先派发结果（触发 postHandle / afterCompletion / 指标），再写终止块。
     * <p>
     * 反过来时终止块的写完成回调会先把异步状态推到 COMPLETED，使 {@code dispatch()} 的 CAS 失败， 异步收尾被整个跳过。这两行的顺序只能靠测试锁死——调换后没有任何编译或运行时信号。
     * </p>
     */
    @Test
    void resolveReturnValue_dispatchesResultBeforeWritingTerminalChunk() throws Exception {
        doAnswer(invocation -> {
            events.add("dispatch");
            return null;
        }).when(asyncWebRequest).setConcurrentResultAndDispatch(any());

        resolver.resolveReturnValue(body(out -> out.write(new byte[] { 1 })), null, req, resp);
        runCallable();

        assertThat(events).containsExactly("dispatch", "sender.complete");
    }

    // ---- 分帧边界：FrameOutputStream 的攒帧循环是手写的，8191/8192/8193 各差一个字节行为不同 ----

    @Test
    void resolveReturnValue_exactlyOneFrameWhenPayloadEqualsFrameSize() throws Exception {
        resolver.resolveReturnValue(body(out -> out.write(new byte[8192])), null, req, resp);
        runCallable();

        assertThat(frameSizes()).containsExactly(8192);
    }

    @Test
    void resolveReturnValue_oneByteShortOfFrameEmitsSingleFrame() throws Exception {
        resolver.resolveReturnValue(body(out -> out.write(new byte[8191])), null, req, resp);
        runCallable();

        assertThat(frameSizes()).containsExactly(8191);
    }

    @Test
    void resolveReturnValue_oneByteOverFrameSplitsIntoFullFramePlusRemainder() throws Exception {
        resolver.resolveReturnValue(body(out -> out.write(new byte[8193])), null, req, resp);
        runCallable();

        assertThat(frameSizes()).containsExactly(8192, 1);
    }

    @Test
    void resolveReturnValue_multiFrameWriteSplitsAtEveryBoundary() throws Exception {
        // 单次 write 跨 4 帧：8192 + 8192 + 8192 + 3616
        resolver.resolveReturnValue(body(out -> out.write(new byte[20000])), null, req, resp);
        runCallable();

        assertThat(frameSizes()).containsExactly(8192, 8192, 3616);
    }

    @Test
    void resolveReturnValue_singleByteWritesFlushExactlyAtBoundary() throws Exception {
        resolver.resolveReturnValue(body(out -> {
            for (int i = 0; i < 8193; i++) {
                out.write(i);
            }
        }), null, req, resp);
        runCallable();

        assertThat(frameSizes()).containsExactly(8192, 1);
    }

    @Test
    void resolveReturnValue_flushOnEmptyBufferEmitsNoFrame() throws Exception {
        resolver.resolveReturnValue(body(out -> {
            out.flush();
            out.flush();
        }), null, req, resp);
        runCallable();

        assertThat(sender.sent).isEmpty();
        assertThat(sender.completeCount).isEqualTo(1);
    }

    // ---- Content-Type 三档规则 ----

    @Test
    void resolveReturnValue_keepsAlreadySetContentType() throws Exception {
        headers.setContentType(MediaType.APPLICATION_JSON);
        givenProduces(MediaType.TEXT_PLAIN);

        resolver.resolveReturnValue(body(out -> out.write(1)), null, req, resp);

        assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }

    @Test
    void resolveReturnValue_singleConcreteProducesIsWritten() throws Exception {
        givenProduces(MediaType.TEXT_PLAIN);

        resolver.resolveReturnValue(body(out -> out.write(1)), null, req, resp);

        assertThat(headers.getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
    }

    @Test
    void resolveReturnValue_multipleProducesDoesNotInventContentType() throws Exception {
        givenProduces(MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON);

        resolver.resolveReturnValue(body(out -> out.write(1)), null, req, resp);

        assertThat(headers.getContentType()).isNull();
    }

    @Test
    void resolveReturnValue_nonConcreteProducesDoesNotSetContentType() throws Exception {
        givenProduces(MediaType.ALL);

        resolver.resolveReturnValue(body(out -> out.write(1)), null, req, resp);

        assertThat(headers.getContentType()).isNull();
    }

    // ---- 空 body / 类型识别边界 ----

    @Test
    void resolveReturnValue_responseEntityWithoutBodyDoesNotStartAsync() throws Exception {
        resolver.resolveReturnValue(ResponseEntity.ok().build(), null, req, resp);

        verify(asyncSupportRegistry, never()).startCallableProcessing(any(), any());
        verify(resp, never()).flushChunked();
    }

    @Test
    void supportsReturnType_wildcardGenericIsNotClaimed() throws Exception {
        // ResponseEntity<?>：泛型无法解析为具体类型 → 不认领（交由 codec / 其它 resolver 处理）
        assertThat(resolver.supportsReturnType(param("wildcard"), null)).isFalse();
    }

    private List<Integer> frameSizes() {
        List<Integer> sizes = new ArrayList<>();
        for (byte[] frame : sender.sent) {
            sizes.add(frame.length);
        }
        return sizes;
    }

    private void givenProduces(MediaType... types) {
        PathMappingContext ctx = org.mockito.Mockito.mock(PathMappingContext.class);
        // lenient：断言「已设置的 Content-Type 优先」时本方法不应被调用（提前 return）
        lenient().when(ctx.getProducibleMediaTypes()).thenReturn(Arrays.asList(types));
        MappingResult.set(req, MappingResult.matched(ctx));
    }

    private void runCallable() throws Exception {
        ArgumentCaptor<WebAsyncTask> captor = ArgumentCaptor.forClass(WebAsyncTask.class);
        verify(asyncSupportRegistry).startCallableProcessing(eq(asyncWebRequest), captor.capture());
        captor.getValue().getCallable().call();
    }

    private MethodParameter param(String name) throws Exception {
        return new MethodParameter(Handler.class.getMethod(name), -1);
    }

    private static StreamingResponseBody body(StreamingResponseBody body) {
        return body;
    }

    @SuppressWarnings("unused")
    public static class Handler {

        public StreamingResponseBody direct() {
            return null;
        }

        public ResponseEntity<StreamingResponseBody> wrapped() {
            return null;
        }

        public ResponseEntity<?> wildcard() {
            return null;
        }

        public String other() {
            return null;
        }
    }

    private static final class RecordingSender implements StreamSender {

        private final List<byte[]> sent = new ArrayList<>();

        private final List<String> events;

        private Throwable failure;

        private int completeCount;

        private RecordingSender(List<String> events) {
            this.events = events;
        }

        @Override
        public void send(Object data) {
            sent.add((byte[]) data);
        }

        @Override
        public void sendAll(Collection<?> data) {
            data.forEach(this::send);
        }

        @Override
        public void complete(boolean closeChannelOnComplete, Throwable failure) {
            this.failure = failure;
            this.completeCount++;
            events.add("sender.complete");
        }

        @Override
        public int queueSize() {
            return 0;
        }
    }
}
