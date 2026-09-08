package io.springperf.web.support.view;

import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JspViewTest {

    @Mock WebServerHttpRequest req;
    @Mock WebServerHttpResponse resp;
    @Mock RequestContext requestContext;
    @Mock ServletAdapterContext adapterContext;
    @Mock HttpServletRequest servletRequest;
    @Mock HttpServletResponse servletResponse;

    private final Map<io.springperf.web.http.RequestAttribute<?>, Object> fastAttrs = new HashMap<>();

    private void bindAdapter() {
        when(requestContext.getAttribute(any(io.springperf.web.http.RequestAttribute.class)))
                .thenAnswer(inv -> fastAttrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            fastAttrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(requestContext).setAttribute(any(io.springperf.web.http.RequestAttribute.class), any());
        when(req.getRequestContext()).thenReturn(requestContext);
        ServletAttribute.setAdapterContext(requestContext, adapterContext);
        when(adapterContext.getRequest()).thenReturn(servletRequest);
        when(adapterContext.getResponse()).thenReturn(servletResponse);
    }

    @Test
    void getContentType_isHtml() {
        assertEquals("text/html", new JspView("/jsp/a.jsp").getContentType());
    }

    @Test
    void render_writesModelToRequestAttributesAndForwards() throws Exception {
        bindAdapter();
        RequestDispatcher dispatcher = mock(RequestDispatcher.class);
        when(servletRequest.getRequestDispatcher("/jsp/a.jsp")).thenReturn(dispatcher);

        Map<String, Object> model = new HashMap<>();
        model.put("name", "value");
        new JspView("/jsp/a.jsp").render(model, req, resp);

        verify(requestContext).setAttribute("name", "value");
        verify(dispatcher).forward(servletRequest, servletResponse);
        verify(resp).setHandled();
    }

    @Test
    void render_nullAttributeValues_skipped() throws Exception {
        bindAdapter();
        RequestDispatcher dispatcher = mock(RequestDispatcher.class);
        when(servletRequest.getRequestDispatcher("/jsp/b.jsp")).thenReturn(dispatcher);

        Map<String, Object> model = new HashMap<>();
        model.put("nullKey", null);
        model.put(null, "nullName");
        model.put("ok", "present");
        new JspView("/jsp/b.jsp").render(model, req, resp);

        verify(requestContext, never()).setAttribute(eq("nullKey"), any());
        verify(requestContext, never()).setAttribute(org.mockito.ArgumentMatchers.isNull(String.class), any());
        verify(requestContext).setAttribute("ok", "present");
        verify(resp).setHandled();
    }

    @Test
    void render_nullModel_onlyForwards() throws Exception {
        bindAdapter();
        RequestDispatcher dispatcher = mock(RequestDispatcher.class);
        when(servletRequest.getRequestDispatcher(anyString())).thenReturn(dispatcher);

        assertDoesNotThrow(() -> new JspView("/jsp/c.jsp").render(null, req, resp));

        verify(resp).setHandled();
    }
}
