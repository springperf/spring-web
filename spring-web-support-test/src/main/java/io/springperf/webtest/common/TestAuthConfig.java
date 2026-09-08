package io.springperf.webtest.common;

import io.springperf.web.support.servlet.Authenticator;
import io.springperf.web.support.servlet.PerfHttpPrincipal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.Principal;

/**
 * 测试专用认证器：接受固定凭据 user/secret，返回带 admin 角色的 Principal。
 * <p>仅用于 E2E 验证 {@code HttpServletRequest.login()} 的会话固定防护行为。</p>
 */
@Configuration
public class TestAuthConfig {

    @Bean
    public Authenticator testAuthenticator() {
        return (username, password) -> {
            if ("user".equals(username) && "secret".equals(password)) {
                return new PerfHttpPrincipal(username, java.util.Collections.singleton("admin"));
            }
            return null;
        };
    }
}