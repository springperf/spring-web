package io.springperf.web.support.mvc.retval;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.model.ModelContext;
import io.springperf.web.core.retval.ReturnValueResolver;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.RedirectView;
import io.springperf.web.view.View;
import io.springperf.web.view.ViewResolverRegistry;
import io.springperf.web.view.retval.ViewReturnValueResolver;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

public class ModelAndViewReturnValueResolver extends BaseWebComponent implements ReturnValueResolver {

    private ViewResolverRegistry viewResolverRegistry;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        viewResolverRegistry = webContext.getWebComponentWithDefault(ViewResolverRegistry.class,
                new ViewResolverRegistry());
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        return ModelAndView.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        if (!(returnValue instanceof ModelAndView)) {
            return false;
        }
        ModelAndView mav = (ModelAndView) returnValue;
        return mav.isReference() && !mav.wasCleared();
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req,
            WebServerHttpResponse resp) throws Exception {
        ModelAndView mav = (ModelAndView) returnValue;
        String viewName = mav.getViewName();
        if (viewName == null) {
            return;
        }

        HttpStatus status = mav.getStatus();
        if (status != null) {
            resp.setStatusCode(status);
        }

        View view = resolveView(viewName, status, req);
        if (view == null) {
            // 与 String 视图名链路保持一致：不得静默产出空白 200
            throw new IllegalArgumentException("Unable to resolve view name '" + viewName
                    + "' with any registered ViewResolver (thymeleaf/freemarker/beetl/jsp)");
        }

        ViewReturnValueResolver.applyContentType(view, resp);
        resp.setHandled();

        Map<String, Object> model = mav.getModel();
        if (model != null && !model.isEmpty()) {
            ModelContext.getOrCreate(req).addAllAttributes(model);
        }
        view.render(ModelContext.getOrCreate(req), req, resp);
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE - 200;
    }

    private View resolveView(String viewName, HttpStatus status, WebServerHttpRequest req) {
        if (viewName.startsWith("redirect:")) {
            HttpStatusCode redirectStatus = status != null && status.is3xxRedirection() ? status : HttpStatus.FOUND;
            return new RedirectView(viewName.substring(9), redirectStatus);
        }
        return viewResolverRegistry.resolve(viewName, req);
    }
}
