package io.springperf.web.core.async;

import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AsyncSupportUtilsTest {

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    @Mock
    RequestContext requestContext;

    @Test
    void isAsyncRequest_noAttribute_returnsFalse() {
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(AsyncSupportUtils.WEB_ASYNC_REQUEST_ATTRIBUTE)).thenReturn(null);

        assertFalse(AsyncSupportUtils.isAsyncRequest(request));
    }

    @Test
    void isAsyncRequest_notStarted_returnsFalse() {
        PerfAsyncWebRequest asyncWebRequest = mock(PerfAsyncWebRequest.class);
        when(asyncWebRequest.isAsyncStarted()).thenReturn(false);
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(AsyncSupportUtils.WEB_ASYNC_REQUEST_ATTRIBUTE)).thenReturn(asyncWebRequest);

        assertFalse(AsyncSupportUtils.isAsyncRequest(request));
    }

    @Test
    void isAsyncRequest_started_returnsTrue() {
        PerfAsyncWebRequest asyncWebRequest = mock(PerfAsyncWebRequest.class);
        when(asyncWebRequest.isAsyncStarted()).thenReturn(true);
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(AsyncSupportUtils.WEB_ASYNC_REQUEST_ATTRIBUTE)).thenReturn(asyncWebRequest);

        assertTrue(AsyncSupportUtils.isAsyncRequest(request));
    }

    @Test
    void getAsyncWebRequest_existing_returnsSame() {
        PerfAsyncWebRequest asyncWebRequest = mock(PerfAsyncWebRequest.class);
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(AsyncSupportUtils.WEB_ASYNC_REQUEST_ATTRIBUTE)).thenReturn(asyncWebRequest);

        assertSame(asyncWebRequest, AsyncSupportUtils.getAsyncWebRequest(request, response));
        verify(requestContext, never()).setAttribute(anyString(), any());
    }

    @Test
    void getAsyncWebRequest_notExists_createsNew() {
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(AsyncSupportUtils.WEB_ASYNC_REQUEST_ATTRIBUTE)).thenReturn(null);

        PerfAsyncWebRequest result = AsyncSupportUtils.getAsyncWebRequest(request, response);
        assertNotNull(result);
        verify(requestContext).setAttribute(eq(AsyncSupportUtils.WEB_ASYNC_REQUEST_ATTRIBUTE), same(result));
    }
}
