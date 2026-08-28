package io.springperf.web.support.servlet;

import io.springperf.web.core.invoker.CustomInvoker;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.lang.reflect.Method;

/**
 * 将 {@link Servlet#service(ServletRequest, ServletResponse)} 桥接为框架的可调用目标。
 *
 * <p>作为 {@link CustomInvoker} 接入核心的非控制器路由机制：handleMethod 返回
 * {@code Servlet.service}（返回 {@code void}），因此框架的 void 返回值处理
 * （{@code ReturnValueResolverRegistry.skipResolve}）会自动 {@code setHandled()}，
 * 使 servlet 直接写入的响应体能够被 {@code flushResponse} 正常发出。
 *
 * <p>两个参数（{@code ServletRequest}/{@code ServletResponse}）由
 * {@code ServletRequestProvider}/{@code ServletResponseProvider} 解析注入。
 */
public class ServletInvoker implements CustomInvoker {

    private static final Method SERVICE_METHOD = resolveServiceMethod();

    private final Servlet servlet;

    public ServletInvoker(Servlet servlet) {
        this.servlet = servlet;
    }

    @Override
    public Object invoke(Object[] args) throws Throwable {
        ServletRequest servletRequest = (ServletRequest) args[0];
        ServletResponse servletResponse = (ServletResponse) args[1];
        servlet.service(servletRequest, servletResponse);
        servletResponse.flushBuffer();
        return null;
    }

    @Override
    public Method getHandleMethod() {
        return SERVICE_METHOD;
    }

    @Override
    public String getType() {
        return "Servlet";
    }

    public Servlet getServlet() {
        return servlet;
    }

    private static Method resolveServiceMethod() {
        try {
            return Servlet.class.getMethod("service", ServletRequest.class, ServletResponse.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Servlet.service not found", e);
        }
    }
}
