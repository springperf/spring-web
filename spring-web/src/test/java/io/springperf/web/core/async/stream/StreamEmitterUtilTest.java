package io.springperf.web.core.async.stream;

import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.core.async.AsyncSupportUtils;
import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StreamEmitterUtilTest {

    @Mock
    StreamEmitter emitter;

    @Mock
    WebServerHttpResponse serverHttpResponse;

    @Mock
    StreamSender streamSender;

    @Mock
    StreamSenderFactory streamSenderFactory;

    @Mock
    AsyncSupportRegistry asyncSupportRegistry;

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    @Mock
    PerfAsyncWebRequest asyncWebRequest;

    @Mock
    RequestContext requestContext;

    @Test
    void initializeWithStreamSender_delegatesToEmitter() throws Exception {
        StreamEmitterUtil.initializeWithStreamSender(emitter, streamSender);

        verify(emitter).initialize(streamSender);
    }

    @Test
    void initStreamSenderAndStartAsync_happyPath() throws Exception {
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(AsyncSupportUtils.WEB_ASYNC_REQUEST_ATTRIBUTE)).thenReturn(asyncWebRequest);
        when(streamSenderFactory.create(emitter, asyncWebRequest)).thenReturn(streamSender);

        StreamSender result = StreamEmitterUtil.initStreamSenderAndStartAsync(
                emitter, streamSenderFactory, asyncSupportRegistry, request, response);

        assertSame(streamSender, result);
        verify(asyncWebRequest).addWriteCallbackHandler(any());
        verify(asyncSupportRegistry).startDeferredResultProcessing(asyncWebRequest, emitter.getDeferredResult());
        verify(emitter, never()).initializeWithError(any());
    }

    @Test
    void initStreamSenderAndStartAsync_exception_callsInitializeWithError() throws Exception {
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(AsyncSupportUtils.WEB_ASYNC_REQUEST_ATTRIBUTE)).thenReturn(asyncWebRequest);
        RuntimeException ex = new RuntimeException("test error");
        doThrow(ex).when(asyncSupportRegistry).startDeferredResultProcessing(any(), any());

        try {
            StreamEmitterUtil.initStreamSenderAndStartAsync(
                    emitter, streamSenderFactory, asyncSupportRegistry, request, response);
            fail("Expected exception");
        } catch (RuntimeException actual) {
            assertSame(ex, actual);
        }

        verify(emitter).initializeWithError(ex);
    }
}
