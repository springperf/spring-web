package io.springperf.webtest.dynamic;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 动态注册的 servlet：用于验证 addServlet + addMapping 之后请求真的能落到它。
 */
public class DynHelloServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("dynamic-servlet");
    }
}
