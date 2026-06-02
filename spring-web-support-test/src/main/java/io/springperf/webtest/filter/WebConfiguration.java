package io.springperf.webtest.filter;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebConfiguration {
    @Bean(name = "healthFilter")
    public FilterRegistrationBean reqRespFilter() {
        HealthFilter healthFilter = new HealthFilter();
        FilterRegistrationBean registration = new FilterRegistrationBean();
        registration.setFilter(healthFilter);
        registration.addUrlPatterns("/health");
        return registration;
    }
}
