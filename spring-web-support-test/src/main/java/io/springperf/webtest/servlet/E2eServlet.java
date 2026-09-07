package io.springperf.webtest.servlet;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 琚?{@code SupportServletRegistry} 鎵弿娉ㄥ唽涓鸿矾鐢辩殑 Servlet銆?
 * 閫氳繃 {@code @WebServlet} 澹版槑 url-pattern锛屼綔涓?Spring Bean 琚鏋惰嚜鍔ㄨ矾鐢便€?
 */
@Component
@WebServlet(name = "e2eServlet", urlPatterns = {"/e2e-servlet", "/e2e-servlet/*"})
public class E2eServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json;charset=UTF-8");
        String echo = req.getParameter("echo");
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        String serverInfo = req.getServletContext().getServerInfo();
        resp.getWriter().write("{"
                + "\"uri\":\"" + uri + "\","
                + "\"ctx\":\"" + ctx + "\","
                + "\"serverInfo\":\"" + serverInfo + "\","
                + "\"echo\":\"" + (echo == null ? "" : echo) + "\""
                + "}");
    }
}
