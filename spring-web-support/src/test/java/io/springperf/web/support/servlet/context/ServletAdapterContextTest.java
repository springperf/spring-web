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

        assertSame(request, ctx.getRequest());
        assertSame(response, ctx.getResponse());
    }
}
