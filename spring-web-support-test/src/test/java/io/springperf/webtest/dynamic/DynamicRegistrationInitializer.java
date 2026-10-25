package io.springperf.webtest.dynamic;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import org.springframework.stereotype.Component;

import java.util.EnumSet;

/**
 * 在规范允许的窗口内（{@code contextInitialized}）动态注册 servlet 与 filter。
 * <p>
 * 本类就是「用户代码」：框架必须在派发 {@code contextInitialized} 之后才扫描/认领注册项， 否则这里登记的路由会被静默丢弃。
 * </p>
 */
@Component
public class DynamicRegistrationInitializer implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext();

        context.addServlet("dynHello", new DynHelloServlet()).addMapping("/dyn/hello");

        FilterRegistration.Dynamic filter = context.addFilter("dynHeader", new DynHeaderFilter());
        filter.setInitParameter("headerValue", "on");
        filter.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/dyn/*");
    }
}
