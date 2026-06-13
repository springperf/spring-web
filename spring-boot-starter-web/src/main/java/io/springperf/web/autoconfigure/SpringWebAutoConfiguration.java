package io.springperf.web.autoconfigure;

import io.netty.handler.ssl.SslContext;
import io.springperf.web.autoconfigure.actuator.server.SslContextFactory;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.server.NettyHttpServer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.OptionalValidatorFactoryBean;

import java.util.List;

@ConditionalOnClass(DispatcherHandler.class)
public class SpringWebAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    public DispatcherHandler dispatcherHandler() { return new DispatcherHandler(); }

    @Bean @ConditionalOnMissingBean
    public ApplicationProperties applicationProperties() { return new ApplicationProperties(); }

    @Bean
    public WebContext webContext(List<DispatcherHandler> dispatcherHandlers, ApplicationProperties props) {
        assertNoSpringMvcConflict();
        return new WebContext(dispatcherHandlers.get(0), props);
    }

    @Bean
    public NettyHttpServer nettyHttpServer(WebContext webContext, Environment environment) {
        boolean http2Enabled = environment.getProperty("server.http2.enabled", boolean.class, false);
        SslContext sslContext = SslContextFactory.createServerSslContext(environment, "server.ssl.", http2Enabled);
        return new NettyHttpServer(webContext, sslContext);
    }

    @Bean @ConditionalOnMissingBean @ConditionalOnClass(name = "javax.validation.Validator")
    public Validator validator() { return new OptionalValidatorFactoryBean(); }

    private static void assertNoSpringMvcConflict() {
        try { Class.forName("org.springframework.web.servlet.DispatcherServlet"); }
        catch (ClassNotFoundException e) { return; }
        throw new IllegalStateException(
                "Detected spring-boot-starter-web on the classpath. " +
                        "Perf Actuator integration conflicts with Spring MVC Actuator integration.");
    }
}