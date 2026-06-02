package io.springperf.web.context;

public abstract class BaseWebComponent implements LifecycleWebComponent {
    protected WebContext webContext;

    public void initWithWebContext(WebContext webContext) {
        this.webContext = webContext;
    }

    public WebContext getWebContext() {
        return webContext;
    }
}
