package io.springperf.example.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public UserDetailsService userDetailsService() {
        InMemoryUserDetailsManager manager = new InMemoryUserDetailsManager();
        manager.createUser(User.withDefaultPasswordEncoder()
                .username("admin").password("admin").roles("ADMIN").build());
        manager.createUser(User.withDefaultPasswordEncoder()
                .username("user").password("user").roles("USER").build());
        return manager;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeRequests(auth -> auth
                        // session 相关端点放行
                        .requestMatchers("/security/session-set").permitAll()
                        .requestMatchers("/security/session-get").permitAll()
                        .requestMatchers("/security/session-info").permitAll()
                        // admin 端点仅 ADMIN 角色可访问
                        .requestMatchers("/security/admin").hasRole("ADMIN")
                        // user 端点仅 USER 角色可访问
                        .requestMatchers("/security/user").hasRole("USER")
                        // 其余请求需认证
                        .anyRequest().authenticated()
                )
                // 启用 HTTP Basic 认证，方便测试
                .httpBasic()
                .and()
                .csrf().disable();

        return http.build();
    }
}