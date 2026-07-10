package io.springperf.benchmark.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

@Configuration
public class WebFluxConfig {

    @Bean
    public WebFilter webFluxBenchFilter1() { return passThrough(); }

    @Bean
    public WebFilter webFluxBenchFilter2() { return passThrough(); }

    @Bean
    public WebFilter webFluxBenchFilter3() { return passThrough(); }

    @Bean
    public WebFilter webFluxBenchFilter4() { return passThrough(); }

    @Bean
    public WebFilter webFluxBenchFilter5() { return passThrough(); }

    @Bean
    public WebFilter webFluxBenchFilter6() { return passThrough(); }

    @Bean
    public WebFilter webFluxBenchFilter7() { return passThrough(); }

    @Bean
    public WebFilter webFluxBenchFilter8() { return passThrough(); }

    private static WebFilter passThrough() {
        return (ServerWebExchange exchange, WebFilterChain chain) -> chain.filter(exchange);
    }
}