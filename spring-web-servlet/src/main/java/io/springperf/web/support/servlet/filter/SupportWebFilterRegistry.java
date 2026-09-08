package io.springperf.web.support.servlet.filter;

import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.filter.WebFilterRegistry;

public class SupportWebFilterRegistry extends WebFilterRegistry {

    public SupportWebFilterRegistry(DispatcherHandler dispatcherHandler) {
        super(dispatcherHandler);
        autoRegisterWebComponent(javax.servlet.Filter.class, filter -> wrapFilterToRegistration(new FilterWrapper(filter)));
    }
}
