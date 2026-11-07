package io.springperf.web.support.servlet.context;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.PerfHttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.servlet.FilterChain;

import static org.junit.jupiter.api.Assertions.assertSame;

@ExtendWith(MockitoExtension.class)
class ServletAdapterContextTest {

    @Mock
    PerfHttpServletRequest request;

    @Mock
    PerfHttpServletResponse response;

    @Mock
    FilterChain filterChain;

    @Mock
    WebServerHttpRequest webRequest;

    @Mock
    WebServerHttpResponse webResponse;

    @Test
    void constructor_setsAllFields() {
        ServletAdapterContext ctx = new ServletAdapterContext(request, response, filterChain);

        assertSame(request, ctx.getRequest());
        assertSame(response, ctx.getResponse());
        assertSame(filterChain, ctx.getFilterChain());
    }

    @Test
    void rebindFramework_updatesPerfRequestAndResponse() {
        ServletAdapterContext ctx = new ServletAdapterContext(request, response, filterChain);

        ctx.rebindFrameworkRequest(webRequest);
        ctx.rebindFrameworkResponse(webResponse);

        org.mockito.Mockito.verify(request).rebind(webRequest);
        org.mockito.Mockito.verify(response).rebind(webResponse);
        assertSame(request, ctx.getRequest(), "rebind 不应替换 servlet 包装器本身");
        assertSame(response, ctx.getResponse(), "rebind 不应替换 servlet 包装器本身");
    }
}
