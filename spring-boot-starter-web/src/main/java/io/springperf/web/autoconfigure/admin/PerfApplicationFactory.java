package io.springperf.web.autoconfigure.admin;

import de.codecentric.boot.admin.client.config.InstanceProperties;
import de.codecentric.boot.admin.client.registration.Application;
import de.codecentric.boot.admin.client.registration.ApplicationFactory;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.server.Ssl;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 框架感知的 {@link ApplicationFactory} 实现。
 * <p>替代 SBA Client 的 {@code ServletApplicationFactory}，在不依赖 Servlet 容器的情况下
 * 从本框架配置中自动构建正确的 serviceUrl、managementUrl、healthUrl。</p>
 *
 * <p>URL 构造优先级：</p>
 * <ol>
 *     <li>用户显式配置（spring.boot.admin.client.instance.*）</li>
 *     <li>自动从 ServerProperties / ManagementServerProperties 计算</li>
 * </ol>
 *
 * @author huangcanda
 * @since 1.0.4
 */
public class PerfApplicationFactory implements ApplicationFactory {

    private final InstanceProperties instanceProperties;
    private final ManagementServerProperties managementServerProperties;
    private final ServerProperties serverProperties;
    private final WebEndpointProperties webEndpointProperties;
    private final Environment environment;

    public PerfApplicationFactory(InstanceProperties instanceProperties,
                                  ManagementServerProperties managementServerProperties,
                                  ServerProperties serverProperties,
                                  WebEndpointProperties webEndpointProperties,
                                  Environment environment) {
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

        // 优先使用用户显式配置的 health-url
        if (instanceProperties.getHealthUrl() != null) {
            healthUrl = instanceProperties.getHealthUrl();
        }

        return Application.create(name)
                .healthUrl(healthUrl)
                .managementUrl(managementUrl)
                .serviceUrl(serviceUrl)
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
        return resolveScheme(serverProperties.getSsl()) + "://" + resolveServiceHost() + ":" + resolveServerPort();
    }

    private String resolveServicePath() {
        if (instanceProperties.getServicePath() != null) {
            return instanceProperties.getServicePath();
        }
        String ctxPath = serverProperties.getServlet().getContextPath();
        return (ctxPath != null && !ctxPath.isEmpty() && !"/".equals(ctxPath)) ? ctxPath : "";
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
        return resolveScheme(managementServerProperties.getSsl())
                + "://" + resolveManagementHost() + ":" + resolveManagementPort();
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
        Integer port = serverProperties.getPort();
        return port != null ? port : 8080;
    }

    private int resolveManagementPort() {
        Integer port = managementServerProperties.getPort();
        return port != null ? port : resolveServerPort();
    }

    // ---- Scheme ----

    private static String resolveScheme(Ssl ssl) {
        if (ssl != null && ssl.isEnabled()) {
            return "https";
        }
        return "http";
    }
}
