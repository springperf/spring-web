package io.springperf.webtest.common;

import io.springperf.web.support.servlet.Authenticator;
import io.springperf.web.support.servlet.PerfHttpPrincipal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.Principal;

/**
 * 娴嬭瘯涓撶敤璁よ瘉鍣細鎺ュ彈鍥哄畾鍑嵁 user/secret锛岃繑鍥炲甫 admin 瑙掕壊鐨?Principal銆?
 * <p>浠呯敤浜?E2E 楠岃瘉 {@code HttpServletRequest.login()} 鐨勪細璇濆浐瀹氶槻鎶よ涓恒€?/p>
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