package io.springperf.example.shiro.config;

import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import javax.servlet.Filter;
import java.util.LinkedHashMap;
import java.util.Map;

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
        return manager;
    }

    @Bean
    public ShiroFilterFactoryBean shiroFilterFactoryBean(SecurityManager securityManager) {
        ShiroFilterFactoryBean factoryBean = new ShiroFilterFactoryBean();
        factoryBean.setSecurityManager(securityManager);
        factoryBean.setLoginUrl("/shiro/login");

        // 过滤链：login 放行，其余通过编程式校验（保持 anon 以便所有请求到达 controller）
        Map<String, String> filterChainDefinitions = new LinkedHashMap<>();
        filterChainDefinitions.put("/shiro/login", "anon");
        filterChainDefinitions.put("/shiro/**", "anon");
        factoryBean.setFilterChainDefinitionMap(filterChainDefinitions);

        return factoryBean;
    }

    @Bean
    public FilterRegistrationBean<Filter> shiroFilterRegistration(ShiroFilterFactoryBean shiroFilterFactoryBean)
            throws Exception {
        Filter filter = shiroFilterFactoryBean.getObject();
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/shiro/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        registration.setName("shiroFilter");
        return registration;
    }
}