package io.springperf.web.support.servlet;

import io.springperf.web.http.WebServerHttpResponse;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ServletInvokerTest {

    @Mock
    Servlet servlet;

    @Test
    void getHandleMethod_returnsVoidServiceMethod() throws Exception {
        ServletInvoker invoker = new ServletInvoker(servlet);

        assertEquals(Servlet.class.getMethod("service", ServletRequest.class, ServletResponse.class),
                invoker.getHandleMethod());
        assertEquals(void.class, invoker.getHandleMethod().getReturnType());
    }

    @Test
    void getType_returnsServlet() {
        assertEquals("Servlet", new ServletInvoker(servlet).getType());
    }

    @Test
    void getServlet_returnsWrappedServlet() {
        assertSame(servlet, new ServletInvoker(servlet).getServlet());
    }

    @Test
    void invoke_callsServiceWithArgs() throws Throwable {
        ServletRequest request = mock(ServletRequest.class);
        ServletResponse response = mock(ServletResponse.class);
        ServletInvoker invoker = new ServletInvoker(servlet);

        Object result = invoker.invoke(new Object[]{request, response});

        assertNull(result);
        verify(servlet).service(request, response);
    }

    @Test
    void invoke_flushesPerfHttpServletResponseAfterService() throws Throwable {
        WebServerHttpResponse webResponse = mock(WebServerHttpResponse.class);
        PerfHttpServletResponse perfResponse = new PerfHttpServletResponse(webResponse);
        ServletRequest request = mock(ServletRequest.class);
        ServletInvoker invoker = new ServletInvoker(servlet);

        invoker.invoke(new Object[]{request, perfResponse});

        verify(servlet).service(request, perfResponse);
        verify(webResponse).flush();
    }
}
