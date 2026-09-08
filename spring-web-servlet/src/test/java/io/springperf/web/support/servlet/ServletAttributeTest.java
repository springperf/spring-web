package io.springperf.web.support.servlet;

import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServletAttributeTest {

    @Mock WebServerHttpRequest request;
    @Mock WebServerHttpResponse response;

    @Test
    void getAdapterContext_createsAndStores() {
        TestRequestContext ctx = new TestRequestContext();
        when(request.getRequestContext()).thenReturn(ctx);
        ServletAdapterContext adapterCtx = ServletAttribute.getAdapterContext(request, response);
        assertNotNull(adapterCtx);
        assertNotNull(adapterCtx.getPerfRequest());
        assertNotNull(adapterCtx.getPerfResponse());
    }

    @Test
    void getAdapterContext_reusesExisting() {
        TestRequestContext ctx = new TestRequestContext();
        when(request.getRequestContext()).thenReturn(ctx);
        ServletAdapterContext first = ServletAttribute.getAdapterContext(request, response);
        ServletAdapterContext second = ServletAttribute.getAdapterContext(request, response);
        assertSame(first, second);
    }

    @Test
    void createPerfRequest_returnsPerfHttpServletRequest() {
        PerfHttpServletRequest perfRequest = ServletAttribute.createPerfRequest(request);
        assertNotNull(perfRequest);
        assertSame(request, perfRequest.getDelegateRequest());
    }

    @Test
    void getRequest_returnsNull() {
        TestRequestContext ctx = new TestRequestContext();
        assertNull(ServletAttribute.getRequest(ctx));
    }

    @Test
    void getRequest_afterSet() {
        TestRequestContext ctx = new TestRequestContext();
        when(request.getRequestContext()).thenReturn(ctx);
        ServletAdapterContext adapterCtx = ServletAttribute.getAdapterContext(request, response);
        assertSame(adapterCtx.getRequest(), ServletAttribute.getRequest(ctx));
    }

    @Test
    void getResponse_returnsNull() {
        TestRequestContext ctx = new TestRequestContext();
        assertNull(ServletAttribute.getResponse(ctx));
    }

    /**
     * 简化的 RequestContext 实现，用于测试。
     */
    static class TestRequestContext implements RequestContext {
        private final Map<String, Object> attrs = new HashMap<>();
        private final Object[] fastAttrs = new Object[64];

        @Override public Map<String, Object> getAttributes() { return attrs; }
        @Override public Object getAttribute(String name) { return attrs.get(name); }
        @Override public void setAttribute(String name, Object o) { attrs.put(name, o); }
        @Override public Object removeAttribute(String name) { return attrs.remove(name); }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getAttribute(io.springperf.web.http.RequestAttribute<T> key) {
            int idx = key.getIndex();
            if (idx < fastAttrs.length) {
                return (T) fastAttrs[idx];
            }
            return (T) attrs.get("__fast_" + idx);
        }

        @Override
        public <T> void setAttribute(io.springperf.web.http.RequestAttribute<T> key, T value) {
            int idx = key.getIndex();
            if (idx < fastAttrs.length) {
                fastAttrs[idx] = value;
            } else {
                attrs.put("__fast_" + idx, value);
            }
        }
    }
}