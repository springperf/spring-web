package io.springperf.web.support.servlet;

import io.springperf.web.core.invoker.CustomInvoker;
import javax.servlet.Servlet;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import java.lang.reflect.Method;

/**
 * 灏?{@link Servlet#service(ServletRequest, ServletResponse)} 妗ユ帴涓烘鏋剁殑鍙皟鐢ㄧ洰鏍囥€?
 *
 * <p>浣滀负 {@link CustomInvoker} 鎺ュ叆鏍稿績鐨勯潪鎺у埗鍣ㄨ矾鐢辨満鍒讹細handleMethod 杩斿洖
 * {@code Servlet.service}锛堣繑鍥?{@code void}锛夛紝鍥犳妗嗘灦鐨?void 杩斿洖鍊煎鐞?
 * 锛坽@code ReturnValueResolverRegistry.skipResolve}锛変細鑷姩 {@code setHandled()}锛?
 * 浣?servlet 鐩存帴鍐欏叆鐨勫搷搴斾綋鑳藉琚?{@code flushResponse} 姝ｅ父鍙戝嚭銆?
 *
 * <p>涓や釜鍙傛暟锛坽@code ServletRequest}/{@code ServletResponse}锛夌敱
 * {@code ServletRequestProvider}/{@code ServletResponseProvider} 瑙ｆ瀽娉ㄥ叆銆?
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
