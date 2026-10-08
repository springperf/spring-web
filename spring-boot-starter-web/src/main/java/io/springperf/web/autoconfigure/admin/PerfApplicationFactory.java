package io.springperf.web.autoconfigure.admin;

import de.codecentric.boot.admin.client.config.InstanceProperties;
import de.codecentric.boot.admin.client.registration.Application;
import de.codecentric.boot.admin.client.registration.ApplicationFactory;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties;
import org.springframework.core.env.Environment;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 框架感知的 {@link ApplicationFactory} 实现。
 * <p>
 * 替代 SBA Client 的 {@code ServletApplicationFactory}，在不依赖 Servlet 容器的情况下 从本框架配置中自动构建正确的
 * serviceUrl、managementUrl、healthUrl。
 * </p>
 * <p>
 * URL 构造优先级：
 * </p>
 * <ol>
 * <li>用户显式配置（spring.boot.admin.client.instance.*）</li>
 * <li>自动从 ServerProperties / ManagementServerProperties 计算</li>
 * </ol>
 * <p>
 * <b>跨版本注意</b>：{@code ServerProperties} 在 Spring Boot 3 位于 {@code org.springframework.boot.autoconfigure.web}， 在 Boot 4
 * 移到 {@code org.springframework.boot.web.server.autoconfigure}（且随 {@code spring-boot-web-server} artifact 拆分）。
 * 编译期无法同时引用两个包名，故此处以 {@link Object} 持有并反射调用其两个用到的取值方法 （{@code getSsl()} 与 {@code getServlet()}）。
 * </p>
 *
 * @author huangcanda
 *
 * @since 1.0.4
 */
public class PerfApplicationFactory implements ApplicationFactory {

    private final InstanceProperties instanceProperties;
    private final ManagementServerProperties managementServerProperties;
    /** Boot 3/4 包名不同的 {@code ServerProperties}：以 Object 持有，反射取值。 */
    private final Object serverProperties;
    private final WebEndpointProperties webEndpointProperties;
    private final Environment environment;

    public PerfApplicationFactory(InstanceProperties instanceProperties,
            ManagementServerProperties managementServerProperties, Object serverProperties,
            WebEndpointProperties webEndpointProperties, Environment environment) {
        this.instanceProperties = instanceProperties;
        this.managementServerProperties = managementServerProperties;
        this.serverProperties = serverProperties;
        this.webEndpointProperties = webEndpointProperties;
        this.environment = environment;
    }

    @Override
    public Application createApplication() {
        String name = resolveApplicationName();
        String serviceUrl = resolveServiceUrl();
        String managementUrl = resolveManagementUrl();
        String healthUrl = managementUrl + "/health";

        // 优先使用用户显式配置的 health-url（取一次判一次，避免重复调用被判为可能 NPE）
        String configuredHealthUrl = instanceProperties.getHealthUrl();
        if (configuredHealthUrl != null) {
            healthUrl = configuredHealthUrl;
        }

        return Application.create(name).healthUrl(healthUrl).managementUrl(managementUrl).serviceUrl(serviceUrl)
                .build();
    }

    // ---- Application Name ----

    private String resolveApplicationName() {
        String name = instanceProperties.getName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return environment.getProperty("spring.application.name", "application");
    }

    // ---- Service URL ----

    private String resolveServiceUrl() {
        // 1. 用户显式配置完整 service-url
        if (instanceProperties.getServiceUrl() != null) {
            return instanceProperties.getServiceUrl();
        }
        return resolveServiceBaseUrl() + resolveServicePath();
    }

    private String resolveServiceBaseUrl() {
        if (instanceProperties.getServiceBaseUrl() != null) {
            return instanceProperties.getServiceBaseUrl();
        }
        return resolveScheme(serverSsl()) + "://" + resolveServiceHost() + ":" + resolveServerPort();
    }

    private String resolveServicePath() {
        if (instanceProperties.getServicePath() != null) {
            return instanceProperties.getServicePath();
        }
        String ctxPath = serverContextPath();
        return (ctxPath != null && !ctxPath.isEmpty() && !"/".equals(ctxPath)) ? ctxPath : "";
    }

    /** 反射取 {@code ServerProperties.getSsl()}（Boot 3/4 包名不同，故不编译期引用）。 */
    private Object serverSsl() {
        return invoke(serverProperties, "getSsl");
    }

    /** 反射取 {@code ServerProperties.getServlet().getContextPath()}。 */
    private String serverContextPath() {
        Object servlet = invoke(serverProperties, "getServlet");
        if (servlet == null) {
            return null;
        }
        Object ctx = invoke(servlet, "getContextPath");
        return ctx == null ? null : ctx.toString();
    }

    private static Object invoke(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to call " + target.getClass().getName() + "." + method + "()", e);
        }
    }

    // ---- Management URL ----

    private String resolveManagementUrl() {
        // 1. 用户显式配置完整 management-url
        if (instanceProperties.getManagementUrl() != null) {
            return instanceProperties.getManagementUrl();
        }
        return resolveManagementBaseUrl() + webEndpointProperties.getBasePath();
    }

    private String resolveManagementBaseUrl() {
        if (instanceProperties.getManagementBaseUrl() != null) {
            return instanceProperties.getManagementBaseUrl();
        }
        if (isManagementPortEqual()) {
            return resolveServiceBaseUrl() + resolveServicePath();
        }
        return resolveScheme(managementServerProperties.getSsl()) + "://" + resolveManagementHost() + ":"
                + resolveManagementPort();
    }

    private boolean isManagementPortEqual() {
        Integer mgmtPort = managementServerProperties.getPort();
        return mgmtPort == null || mgmtPort.equals(resolveServerPort());
    }

    // ---- Host ----

    private String resolveHost() {
        if (instanceProperties.getServiceHostType() != null) {
            switch (instanceProperties.getServiceHostType()) {
                case HOST_NAME:
                    return getLocalHostName();
                case IP:
                    return getLocalHostAddress();
                default:
                    return getLocalHostAddress();
            }
        }
        return getLocalHostAddress();
    }

    private String resolveServiceHost() {
        return resolveHost();
    }

    private String resolveManagementHost() {
        return resolveHost();
    }

    private static String getLocalHostAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }

    private static String getLocalHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }

    // ---- Port ----

    private int resolveServerPort() {
        // 反射取 ServerProperties.getPort()：该类在 Boot 3/4 位于不同包（见类 Javadoc）
        Object port = invoke(serverProperties, "getPort");
        return port instanceof Integer i ? i : 8080;
    }

    private int resolveManagementPort() {
        Integer port = managementServerProperties.getPort();
        return port != null ? port : resolveServerPort();
    }

    // ---- Scheme ----

    /** 反射判定 SSL 是否启用（{@code Ssl} 类型在 Boot 3/4 位于同一包名，但为避免编译期依赖仍走反射）。 */
    private static String resolveScheme(Object ssl) {
        if (ssl == null) {
            return "http";
        }
        Object enabled = invoke(ssl, "isEnabled");
        return Boolean.TRUE.equals(enabled) ? "https" : "http";
    }
}
