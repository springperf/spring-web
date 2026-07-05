package io.springperf.example.shiro.config;

import io.springperf.web.core.filter.FilterChain;
import io.springperf.web.core.filter.WebFilter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.http.WebServerHttpResponseWrapper;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.mgt.DefaultSessionManager;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.io.IOException;

@Configuration
public class ShiroConfig {

    @Bean
    public AuthorizingRealm realm() {
        return new AuthorizingRealm() {

            @Override
            protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) {
                UsernamePasswordToken upToken = (UsernamePasswordToken) token;
                String username = upToken.getUsername();
                if ("admin".equals(username)) {
                    return new SimpleAuthenticationInfo("admin", "admin123", getName());
                }
                if ("user".equals(username)) {
                    return new SimpleAuthenticationInfo("user", "user123", getName());
                }
                return null;
            }

            @Override
            protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
                String username = (String) principals.getPrimaryPrincipal();
                SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
                if ("admin".equals(username)) {
                    info.addRole("admin");
                    info.addStringPermission("user:create");
                    info.addStringPermission("user:read");
                }
                if ("user".equals(username)) {
                    info.addRole("user");
                    info.addStringPermission("user:read");
                }
                return info;
            }
        };
    }

    @Bean
    public SecurityManager securityManager(AuthorizingRealm realm) {
        DefaultWebSecurityManager manager = new DefaultWebSecurityManager();
        manager.setRealm(realm);
        manager.setSessionManager(new DefaultSessionManager());
        return manager;
    }

    @Bean
    public WebFilter shiroWebFilter(SecurityManager securityManager) {
        return new ShiroWebFilter(securityManager);
    }

    private static class ShiroWebFilter implements WebFilter {

        private final SecurityManager securityManager;

        ShiroWebFilter(SecurityManager securityManager) {
            this.securityManager = securityManager;
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 100;
        }

        @Override
        public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response, FilterChain chain)
                throws Exception {

            String requestSessionId = extractSessionId(request);

            SecurityUtils.setSecurityManager(securityManager);
            Subject subject = requestSessionId != null
                    ? new Subject.Builder(securityManager).sessionId(requestSessionId).buildSubject()
                    : new Subject.Builder(securityManager).buildSubject();

            ThreadContext.bind(subject);

            WebServerHttpResponse wrappedResponse = new WebServerHttpResponseWrapper(response) {
                private boolean cookieSet;

                @Override
                public void flush() throws IOException {
                    setCookieIfNeeded();
                    super.flush();
                }

                @Override
                public void flush(boolean chunked) throws IOException {
                    setCookieIfNeeded();
                    super.flush(chunked);
                }

                private void setCookieIfNeeded() {
                    if (cookieSet) return;
                    Session shiroSession = SecurityUtils.getSubject().getSession(false);
                    if (shiroSession != null && requestSessionId == null && !getResponse().isCommitted()) {
                        getResponse().getHeaders().add("Set-Cookie",
                                "JSESSIONID=" + shiroSession.getId() + "; Path=/; HttpOnly");
                        cookieSet = true;
                    }
                }
            };

            try {
                chain.doFilter(request, wrappedResponse);
            } finally {
                ThreadContext.unbindSubject();
            }
        }

        private static String extractSessionId(WebServerHttpRequest request) {
            String cookieHeader = request.getHeaders().getFirst("Cookie");
            if (cookieHeader == null) return null;
            for (String part : cookieHeader.split(";")) {
                part = part.trim();
                if (part.startsWith("JSESSIONID=")) {
                    return part.substring("JSESSIONID=".length());
                }
            }
            return null;
        }
    }
}