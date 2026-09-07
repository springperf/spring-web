package io.springperf.web.view;

import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.ModelMap;

public final class ModelSupport {

    private static final RequestAttribute<ModelMap> MODEL_KEY =
            RequestAttribute.createAttribute(ModelMap.class);

    private ModelSupport() {
    }

    public static ModelMap getOrCreate(WebServerHttpRequest req) {
        RequestContext ctx = req.getRequestContext();
        ModelMap model = ctx.getAttribute(MODEL_KEY);
        if (model == null) {
            model = new ExtendedModelMap();
            ctx.setAttribute(MODEL_KEY, model);
        }
        return model;
    }
}