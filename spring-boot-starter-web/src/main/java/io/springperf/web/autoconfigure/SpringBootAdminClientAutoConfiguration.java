package io.springperf.web.autoconfigure;

import de.codecentric.boot.admin.client.config.InstanceProperties;
import de.codecentric.boot.admin.client.registration.ApplicationFactory;
import io.springperf.web.autoconfigure.admin.PerfApplicationFactory;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Perf 框架的 Spring Boot Admin Client 桥接自动配置。
 * <p>当 classpath 中存在 SBA Client 的 {@link ApplicationFactory} 时自动生效。
 * 提供框架感知的 {@link PerfApplicationFactory} 替换 SBA Client 默认的
 * {@code DefaultApplicationFactory} / {@code ServletApplicationFactory}，
 * 使 serviceUrl/managementUrl/healthUrl 从本框架配置中自动计算，
 * 无需依赖 Servlet 容器。</p>
 *
 * <p>需要用户在项目中自行添加 {@code spring-boot-admin-starter-client} 依赖，
 * 并排除 {@code spring-boot-starter-web}（避免 Tomcat 冲突）。</p>
 *
 * @author huangcanda
 * @since 1.0.4
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "de.codecentric.boot.admin.client.registration.ApplicationFactory")
@AutoConfigureBefore(name = "de.codecentric.boot.admin.client.config.AdminClientAutoConfiguration")
public class SpringBootAdminClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ApplicationFactory.class)
    public PerfApplicationFactory perfApplicationFactory(
            InstanceProperties instanceProperties,
            ManagementServerProperties managementServerProperties,
            ServerProperties serverProperties,
            WebEndpointProperties webEndpointProperties,
            Environment environment) {
        return new PerfApplicationFactory(instanceProperties, managementServerProperties,
                serverProperties, webEndpointProperties, environment);
    }

}
