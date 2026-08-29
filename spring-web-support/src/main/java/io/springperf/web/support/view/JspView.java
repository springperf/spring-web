package io.springperf.web.support.view;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import io.springperf.web.view.View;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Map;

/**
 * 基于 JSP 的 {@link View} 实现。
 *
 * <p>将 model 写入 request attribute（JSP EL 经 {@code request.getAttribute} 访问），
 * 再通过 {@code RequestDispatcher.forward} 转发到 JSP 路径——由 {@code PerfRequestDispatcher}
 * 重新分发，命中 *.jsp 路由后由 Jasper 渲染。</p>
 */
public class JspView implements View {

    private final String path;

    public JspView(String path) {
        this.path = path;
    }

    @Override
    public String getContentType() {
        return "text/html";
    }

    @Override
    public void render(Map<String, ?> model, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        if (model != null) {
            for (Map.Entry<String, ?> entry : model.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    req.getRequestContext().setAttribute(entry.getKey(), entry.getValue());
                }
            }
        }
        ServletAdapterContext adapterContext = ServletAttribute.getAdapterContext(req, resp);
        HttpServletRequest servletRequest = adapterContext.getRequest();
        HttpServletResponse servletResponse = adapterContext.getResponse();
        servletRequest.getRequestDispatcher(path).forward(servletRequest, servletResponse);
        resp.setHandled();
    }
}
