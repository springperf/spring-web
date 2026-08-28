package io.springperf.webtest.servlet;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 被 {@code SupportServletRegistry} 扫描注册为路由的 Servlet。
 * 通过 {@code @WebServlet} 声明 url-pattern，作为 Spring Bean 被框架自动路由。
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
