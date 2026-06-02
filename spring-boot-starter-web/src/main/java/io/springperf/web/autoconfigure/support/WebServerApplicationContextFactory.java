package io.springperf.web.autoconfigure.support;

import org.springframework.boot.ApplicationContextFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.Order;

@Order(-10000)
public class WebServerApplicationContextFactory implements ApplicationContextFactory {
    @Override
    public ConfigurableApplicationContext create(WebApplicationType webApplicationType) {
        return new AnnotationConfigApplicationContext();
    }
}
