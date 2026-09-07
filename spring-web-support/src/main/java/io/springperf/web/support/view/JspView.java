package io.springperf.web.support.view;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import io.springperf.web.view.View;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.Map;

/**
 * 鍩轰簬 JSP 鐨?{@link View} 瀹炵幇銆?
 *
 * <p>灏?model 鍐欏叆 request attribute锛圝SP EL 缁?{@code request.getAttribute} 璁块棶锛夛紝
 * 鍐嶉€氳繃 {@code RequestDispatcher.forward} 杞彂鍒?JSP 璺緞鈥斺€旂敱 {@code PerfRequestDispatcher}
 * 閲嶆柊鍒嗗彂锛屽懡涓?*.jsp 璺敱鍚庣敱 Jasper 娓叉煋銆?/p>
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
