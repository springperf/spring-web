package io.springperf.web.support.servlet.context;

import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.PerfHttpServletResponse;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@ExtendWith(MockitoExtension.class)
class ServletAdapterContextTest {

    @Mock
    PerfHttpServletRequest request;

    @Mock
    PerfHttpServletResponse response;

    @Mock
    FilterChain filterChain;

    @Test
    void constructor_setsAllFields() {
        ServletAdapterContext ctx = new ServletAdapterContext(request, response, filterChain);

        assertSame(request, ctx.getRequest());
        assertSame(response, ctx.getResponse());
        assertSame(filterChain, ctx.getFilterChain());
    }

    @Test
    void constructor_acceptsNullFields() {
        ServletAdapterContext ctx = new ServletAdapterContext(null, null, null);

        assertNull(ctx.getRequest());
        assertNull(ctx.getResponse());
        assertNull(ctx.getFilterChain());
    }
}
