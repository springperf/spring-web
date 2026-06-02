package io.springperf.web.context;

import org.springframework.core.Ordered;

public interface WebComponent extends Ordered {

    default String getComponentName() {
        return getClass().getSimpleName();
    }

    default void initWithWebContext(WebContext webContext) {

    }

    default int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10000;
    }
}
