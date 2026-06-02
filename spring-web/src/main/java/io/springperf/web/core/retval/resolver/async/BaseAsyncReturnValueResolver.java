package io.springperf.web.core.retval.resolver.async;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.core.retval.ReturnValueResolver;

public abstract class BaseAsyncReturnValueResolver implements ReturnValueResolver {

    protected AsyncSupportRegistry asyncSupportRegistry;

    @Override
    public void initWithWebContext(WebContext webContext) {
        asyncSupportRegistry = webContext.getWebComponentWithDefault(AsyncSupportRegistry.class, new AsyncSupportRegistry());
    }
}
