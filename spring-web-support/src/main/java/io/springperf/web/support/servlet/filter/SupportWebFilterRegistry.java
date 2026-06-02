package io.springperf.web.support.servlet.filter;

import io.springperf.web.context.WebContext;
import io.springperf.web.filter.WebFilter;
import io.springperf.web.filter.WebFilterRegistry;

public class SupportWebFilterRegistry extends WebFilterRegistry {

    public SupportWebFilterRegistry() {
        super();
        autoRegisterWebComponent(javax.servlet.Filter.class, FilterWrapper::new);
    }

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        initRealComponentList(filters, WebFilter.class);
    }
}
