package io.springperf.web.support.servlet.filter;

import io.springperf.web.filter.WebFilterRegistry;

public class SupportWebFilterRegistry extends WebFilterRegistry {

    public SupportWebFilterRegistry() {
        super();
        autoRegisterWebComponent(javax.servlet.Filter.class, filter -> wrapFilterToRegistration(new FilterWrapper(filter)));
    }
}
